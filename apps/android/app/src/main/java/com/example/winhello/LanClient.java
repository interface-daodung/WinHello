package com.example.winhello;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class LanClient {
    static final String PHONE_ID_KEY = "phone_device_id";
    private static final int DISCOVERY_PORT = 45876;
    private static final String PING = "WINHELLO_DISCOVER_V1";
    private static final String PONG = "WINHELLO_PONG_V1";

    private final Context context;

    LanClient(Context context) {
        this.context = context.getApplicationContext();
    }

    String phoneDeviceId() {
        var prefs = context.getSharedPreferences("winhello", Context.MODE_PRIVATE);
        String existing = prefs.getString(PHONE_ID_KEY, null);
        if (existing != null) return existing;
        String created = java.util.UUID.randomUUID().toString();
        prefs.edit().putString(PHONE_ID_KEY, created).apply();
        return created;
    }

    List<Pc> discover() throws Exception {
        Map<String, Pc> found = new ConcurrentHashMap<>();
        byte[] request = PING.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(350);
            socket.send(new DatagramPacket(
                    request,
                    request.length,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT));

            long deadline = System.currentTimeMillis() + 1800;
            byte[] buffer = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                    Pc pc = parsePong(response);
                    if (pc != null) found.put(pc.deviceId, pc);
                } catch (java.net.SocketTimeoutException ignored) {
                    // Continue until the overall discovery window expires.
                }
            }
        }
        for (Pc pc : found.values()) {
            if (hasEnrollment(pc.deviceId)) {
                remember(pc);
                try {
                    pc.sessionState = getJson(pc, "/health", false).optString("sessionState", "unknown");
                } catch (Exception ignored) {
                    pc.sessionState = "unknown";
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private void remember(Pc pc) {
        context.getSharedPreferences("winhello", Context.MODE_PRIVATE).edit()
                .putString("name_" + pc.deviceId, pc.name)
                .putString("address_" + pc.deviceId, pc.address.getHostAddress())
                .apply();
    }

    List<SavedPc> savedPcs() {
        var prefs = context.getSharedPreferences("winhello", Context.MODE_PRIVATE);
        List<SavedPc> saved = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("totp_")) continue;
            String id = key.substring(5);
            String name = prefs.getString("name_" + id, "Windows PC");
            saved.add(new SavedPc(id, name, prefs.getString("address_" + id, ""),
                    prefs.getBoolean("paired_" + id, false)));
        }
        Collections.sort(saved, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return saved;
    }

    boolean isPaired(Pc pc) {
        return context.getSharedPreferences("winhello", Context.MODE_PRIVATE)
                .getBoolean("paired_" + pc.deviceId, false);
    }

    String keyAlias(Pc pc) {
        return "winhello_" + pc.deviceId.replace("-", "");
    }

    void saveEnrollment(String pcId, String secret, String certificatePin) throws Exception {
        TotpSecretStore.save(context, pcId, secret);
        context.getSharedPreferences("winhello", Context.MODE_PRIVATE).edit()
                .putString("enrolled_pc", pcId)
                .putString("pin_" + pcId, certificatePin)
                .remove("paired_" + pcId)
                .apply();
    }

    boolean hasEnrollment(String pcId) {
        return context.getSharedPreferences("winhello", Context.MODE_PRIVATE)
                .contains("totp_" + pcId);
    }

    String enrolledDeviceId() {
        return context.getSharedPreferences("winhello", Context.MODE_PRIVATE)
                .getString("enrolled_pc", null);
    }

    void pair(Pc pc) throws Exception {
        String secret = TotpSecretStore.load(context, pc.deviceId);
        String enrolledPin = enrolledPin(pc.deviceId);
        if (secret == null || enrolledPin == null)
            throw new IllegalStateException("Scan the setup QR from this PC first.");
        if (!enrolledPin.equalsIgnoreCase(pc.certificatePin))
            throw new IllegalStateException("The PC certificate pin differs from the setup QR.");
        JSONObject body = new JSONObject();
        body.put("deviceId", phoneDeviceId());
        body.put("totpCode", TotpSecretStore.code(secret, System.currentTimeMillis() / 1000));
        body.put("publicKey", PhoneSigningKey.publicKeyBase64(keyAlias(pc)));
        JSONObject result = postJson(pc, "/v1/pair", body);
        if (!result.optBoolean("paired", false))
            throw new IllegalStateException("The PC rejected the TOTP enrollment code. Check phone/PC time and wait for the next code.");
        context.getSharedPreferences("winhello", Context.MODE_PRIVATE)
                .edit().putBoolean("paired_" + pc.deviceId, true).apply();
    }

    void unpair(Pc pc) throws Exception {
        String secret = TotpSecretStore.load(context, pc.deviceId);
        if (secret == null) throw new IllegalStateException("This PC has no saved TOTP enrollment.");
        JSONObject body = new JSONObject();
        body.put("deviceId", phoneDeviceId());
        body.put("totpCode", TotpSecretStore.code(secret, System.currentTimeMillis() / 1000));
        JSONObject result = postJson(pc, "/v1/unpair", body);
        if (!result.optBoolean("unpaired", false))
            throw new IllegalStateException("The PC did not revoke this phone enrollment.");

        String active = enrolledDeviceId();
        TotpSecretStore.delete(context, pc.deviceId);
        PhoneSigningKey.delete(keyAlias(pc));
        if (pc.deviceId.equals(active)) {
            context.getSharedPreferences("winhello", Context.MODE_PRIVATE).edit()
                    .remove("enrolled_pc").apply();
        }
        context.getSharedPreferences("winhello", Context.MODE_PRIVATE).edit()
                .remove("name_" + pc.deviceId)
                .remove("address_" + pc.deviceId)
                .apply();
    }

    Challenge getPendingChallenge(Pc pc) throws Exception {
        String path = "/v1/demo/pending?deviceId=" +
                URLEncoder.encode(phoneDeviceId(), StandardCharsets.UTF_8.name());
        JSONObject result = getJson(pc, path, true);
        if (result == null) return null;
        return new Challenge(
                result.getString("requestId"),
                result.getString("deviceId"),
                result.getString("nonce"));
    }

    void approve(Pc pc, Challenge challenge, byte[] signature) throws Exception {
        String secret = TotpSecretStore.load(context, pc.deviceId);
        if (secret == null) throw new IllegalStateException("This PC has no saved enrollment QR.");
        JSONObject body = new JSONObject();
        body.put("deviceId", phoneDeviceId());
        body.put("requestId", challenge.requestId);
        body.put("signature", Base64.getEncoder().encodeToString(signature));
        body.put("totpCode", TotpSecretStore.code(secret, System.currentTimeMillis() / 1000));
        JSONObject result = postJson(pc, "/v1/demo/approve", body);
        if (!result.optBoolean("approved", false))
            throw new IllegalStateException("The PC rejected this approval.");
    }

    private JSONObject postJson(Pc pc, String path, JSONObject body) throws Exception {
        HttpsURLConnection connection = open(pc, path, "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        return readJson(connection, false);
    }

    private JSONObject getJson(Pc pc, String path, boolean missingIsEmpty) throws Exception {
        HttpsURLConnection connection = open(pc, path, "GET");
        return readJson(connection, missingIsEmpty);
    }

    private HttpsURLConnection open(Pc pc, String path, String method) throws Exception {
        URL url = new URL("https://" + pc.address.getHostAddress() + ":" + pc.httpsPort + path);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        connection.setRequestMethod(method);
        connection.setSSLSocketFactory(pinContext(enrolledPin(pc.deviceId) == null
                ? pc.certificatePin : enrolledPin(pc.deviceId)).getSocketFactory());
        HostnameVerifier pinnedHostVerifier = (hostname, session) -> true;
        connection.setHostnameVerifier(pinnedHostVerifier);
        return connection;
    }

    private String enrolledPin(String pcId) {
        return context.getSharedPreferences("winhello", Context.MODE_PRIVATE)
                .getString("pin_" + pcId, null);
    }

    private JSONObject readJson(HttpsURLConnection connection, boolean missingIsEmpty) throws Exception {
        int code = connection.getResponseCode();
        if (missingIsEmpty && code == 404) {
            connection.disconnect();
            return null;
        }
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            connection.disconnect();
            throw new IllegalStateException("PC returned HTTP " + code);
        }
        byte[] bytes;
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        connection.disconnect();
        if (code < 200 || code >= 300)
            throw new IllegalStateException("PC returned HTTP " + code + ": " + new String(bytes, StandardCharsets.UTF_8));
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private SSLContext pinContext(String expectedPin) throws Exception {
        String normalizedPin = expectedPin.replace(":", "").toUpperCase(java.util.Locale.ROOT);
        X509TrustManager trustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0)
                    throw new java.security.cert.CertificateException("PC supplied no certificate.");
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    StringBuilder hex = new StringBuilder(digest.length * 2);
                    for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02X", value));
                    String actual = hex.toString();
                    if (!actual.equals(normalizedPin))
                        throw new java.security.cert.CertificateException("TLS certificate pin mismatch.");
                } catch (java.security.cert.CertificateException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new java.security.cert.CertificateException("Could not validate the PC certificate.", ex);
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustManager}, null);
        return context;
    }

    private Pc parsePong(DatagramPacket packet) throws Exception {
        String[] fields = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8)
                .split("\\|", 6);
        if (fields.length != 6 || !PONG.equals(fields[0])) return null;
        return new Pc(
                fields[1],
                fields[2],
                packet.getAddress(),
                Integer.parseInt(fields[3]),
                "1".equals(fields[4]),
                fields[5]);
    }

    static final class Pc {
        final String deviceId;
        final String name;
        final InetAddress address;
        final int httpsPort;
        final boolean pending;
        final String certificatePin;
        String sessionState = "unknown";

        Pc(String deviceId, String name, InetAddress address, int httpsPort, boolean pending, String certificatePin) {
            this.deviceId = deviceId;
            this.name = name;
            this.address = address;
            this.httpsPort = httpsPort;
            this.pending = pending;
            this.certificatePin = certificatePin;
        }
    }

    static final class SavedPc {
        final String deviceId;
        final String name;
        final String address;
        final boolean paired;

        SavedPc(String deviceId, String name, String address, boolean paired) {
            this.deviceId = deviceId;
            this.name = name;
            this.address = address;
            this.paired = paired;
        }
    }

    static final class Challenge {
        final String requestId;
        final String pcDeviceId;
        final String nonceBase64;

        Challenge(String requestId, String pcDeviceId, String nonceBase64) {
            this.requestId = requestId;
            this.pcDeviceId = pcDeviceId;
            this.nonceBase64 = nonceBase64;
        }
    }
}
