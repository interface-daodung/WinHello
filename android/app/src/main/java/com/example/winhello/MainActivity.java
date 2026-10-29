package com.example.winhello;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public final class MainActivity extends Activity {
    public static final String ACTION_SCAN = "com.example.winhello.SCAN_LAN";

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private LanClient lanClient;
    private TextView status;
    private LinearLayout devices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lanClient = new LanClient(this);
        createLayout();
        if (ACTION_SCAN.equals(getIntent().getAction())) scanLan();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_SCAN.equals(intent.getAction())) scanLan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanLan();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    private void createLayout() {
        int pad = dp(20);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("WinHello LAN Demo");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(this);
        description.setText("Find PCs with one LAN broadcast. Pair once, then approve a pending sign-in using your phone biometric.");
        description.setTextSize(15);
        description.setPadding(0, dp(12), 0, dp(12));
        page.addView(description, new LinearLayout.LayoutParams(-1, -2));

        Button scan = new Button(this);
        scan.setText("Scan LAN for PCs");
        scan.setOnClickListener(view -> scanLan());
        page.addView(scan, new LinearLayout.LayoutParams(-1, -2));

        Button enroll = new Button(this);
        enroll.setText("Scan PC setup QR");
        enroll.setOnClickListener(view -> scanEnrollmentQr());
        page.addView(enroll, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Tap Scan. PCs answer the broadcast with a UDP pong.");
        status.setPadding(0, dp(12), 0, dp(8));
        page.addView(status, new LinearLayout.LayoutParams(-1, -2));

        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        page.addView(devices, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Fingerprint/biometric approval is only a demo signal. Windows authentication is not configured; no Windows password is stored or sent.");
        note.setTextSize(12);
        note.setPadding(0, dp(20), 0, 0);
        page.addView(note, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void scanLan() {
        status.setText("Sending one broadcast ping and listening for PC pongs…");
        devices.removeAllViews();
        background.execute(() -> {
            try {
                List<LanClient.Pc> found = lanClient.discover();
                runOnUiThread(() -> showDevices(found));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("LAN scan failed: " + error.getMessage()));
            }
        });
    }

    private void scanEnrollmentQr() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan the setup QR displayed by the WinHello PC installer")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) return;
        try {
            Uri uri = Uri.parse(result.getContents());
            String pcId = uri.getQueryParameter("pcid");
            String secret = uri.getQueryParameter("secret");
            String pin = uri.getQueryParameter("pin");
            if (!"winhello".equals(uri.getScheme()) || !"enroll".equals(uri.getHost()) ||
                    pcId == null || secret == null || pin == null || !pin.matches("(?i)[0-9a-f]{64}"))
                throw new IllegalArgumentException("This is not a valid WinHello setup QR.");
            java.util.UUID.fromString(pcId);
            if (!secret.matches("(?i)[A-Z2-7]{32,64}"))
                throw new IllegalArgumentException("The QR contains an invalid TOTP secret.");
            lanClient.saveEnrollment(pcId, secret, pin);
            status.setText("Setup QR saved encrypted. Scan the LAN to confirm this PC with TOTP.");
            scanLan();
        } catch (Exception error) {
            status.setText("QR enrollment failed: " + error.getMessage());
        }
    }

    private void showDevices(List<LanClient.Pc> found) {
        devices.removeAllViews();
        if (found.isEmpty()) {
            status.setText("No WinHello agent answered. Start the LAN agent on a PC and allow its discovery port through Windows Firewall.");
            return;
        }
        status.setText("Found " + found.size() + " PC(s). Compare the TLS pin before pairing.");
        for (LanClient.Pc pc : found) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));

            TextView name = new TextView(this);
            name.setText(pc.name + "  ·  " + pc.address.getHostAddress());
            name.setTextSize(17);
            card.addView(name, new LinearLayout.LayoutParams(-1, -2));

            TextView detail = new TextView(this);
            detail.setText("PC " + pc.deviceId + "\n" +
                    (pc.pending ? "Windows sign-in request is waiting" : "No pending Windows request") +
                    "\nTLS SHA-256: " + formatPin(pc.certificatePin));
            detail.setTextSize(11);
            detail.setPadding(0, dp(6), 0, dp(6));
            card.addView(detail, new LinearLayout.LayoutParams(-1, -2));

            Button action = new Button(this);
            boolean paired = lanClient.isPaired(pc);
            boolean enrolled = lanClient.hasEnrollment(pc.deviceId);
            action.setText(!enrolled ? "Scan this PC setup QR" : !paired ? "Confirm TOTP enrollment" : pc.pending ? "Approve sign-in request" : "Paired - refresh scan for request");
            action.setEnabled(!enrolled || !paired || pc.pending);
            action.setOnClickListener(view -> {
                if (!lanClient.hasEnrollment(pc.deviceId)) scanEnrollmentQr();
                else if (!lanClient.isPaired(pc)) pairPhone(pc);
                else approvePending(pc);
            });
            card.addView(action, new LinearLayout.LayoutParams(-1, -2));

            if (enrolled && paired) {
                Button forget = new Button(this);
                forget.setText("Remove phone pairing from this PC");
                forget.setOnClickListener(view -> new AlertDialog.Builder(this)
                        .setTitle("Remove phone pairing?")
                        .setMessage("This revokes the phone key on the PC and deletes the saved TOTP secret from this app. Re-enroll by scanning a newly generated setup QR.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (dialog, which) -> forgetPc(pc))
                        .show());
                card.addView(forget, new LinearLayout.LayoutParams(-1, -2));
            }

            ViewGroup.LayoutParams spacer = new LinearLayout.LayoutParams(-1, dp(10));
            devices.addView(card, new LinearLayout.LayoutParams(-1, -2));
            devices.addView(new android.view.View(this), spacer);
        }
    }

    private void pairPhone(LanClient.Pc pc) {
        status.setText("Confirming setup QR with the current TOTP code...");
        background.execute(() -> {
            try {
                lanClient.pair(pc);
                runOnUiThread(() -> {
                    status.setText("Phone enrollment confirmed. Future approvals require TOTP and biometric confirmation.");
                    Toast.makeText(this, "TOTP enrollment confirmed", Toast.LENGTH_SHORT).show();
                    scanLan();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Enrollment confirmation failed: " + error.getMessage()));
            }
        });
    }

    private void forgetPc(LanClient.Pc pc) {
        status.setText("Revoking the phone key on the PC...");
        background.execute(() -> {
            try {
                lanClient.unpair(pc);
                runOnUiThread(() -> {
                    status.setText("Pairing revoked on the PC and local TOTP removed.");
                    scanLan();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not revoke pairing: " + error.getMessage()));
            }
        });
    }
    private void approvePending(LanClient.Pc pc) {
        status.setText("Checking for a pending PC request…");
        background.execute(() -> {
            try {
                LanClient.Challenge challenge = lanClient.getPendingChallenge(pc);
                if (challenge == null) {
                    runOnUiThread(() -> status.setText("No pending request. Select Request phone approval on the Windows tile, then scan again."));
                    return;
                }
                Signature signature = PhoneSigningKey.newSignature(lanClient.keyAlias(pc));
                runOnUiThread(() -> showBiometricPrompt(pc, challenge, signature));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not prepare approval: " + error.getMessage()));
            }
        });
    }

    private void showBiometricPrompt(LanClient.Pc pc, LanClient.Challenge challenge, Signature signature) {
        BiometricManager biometricManager = getSystemService(BiometricManager.class);
        int availability = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Set up a strong fingerprint/biometric on this phone before approving.");
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle("Confirm your fingerprint")
                .setSubtitle("Approve sign-in to " + pc.name)
                .setDescription("The biometric unlocks a one-time signing key. It is not sent to the PC.")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancel", getMainExecutor(), (dialog, which) -> {
                    status.setText("Approval canceled.");
                })
                .setConfirmationRequired(true)
                .build();

        prompt.authenticate(
                new BiometricPrompt.CryptoObject(signature),
                new CancellationSignal(),
                getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        background.execute(() -> signAndApprove(pc, challenge, result));
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        status.setText("Approval canceled: " + errString);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        status.setText("Fingerprint was not recognized. Try again.");
                    }
                });
        status.setText("Waiting for fingerprint confirmation…");
    }

    private void signAndApprove(LanClient.Pc pc, LanClient.Challenge challenge,
                                BiometricPrompt.AuthenticationResult result) {
        try {
            Signature signing = result.getCryptoObject().getSignature();
            String payload = challenge.pcDeviceId + "\n" + challenge.requestId + "\n" + challenge.nonceBase64;
            signing.update(payload.getBytes(StandardCharsets.UTF_8));
            byte[] signature = signing.sign();
            lanClient.approve(pc, challenge, signature);
            runOnUiThread(() -> {
                status.setText("Phone approval accepted by " + pc.name + ". Windows sign-in is completing...");
                scanLan();
            });
        } catch (Exception error) {
            runOnUiThread(() -> status.setText("Approval failed: " + error.getMessage()));
        }
    }

    private static String formatPin(String pin) {
        return pin.replaceAll("(.{2})(?!$)", "$1:");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
