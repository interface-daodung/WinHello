package com.example.winhello;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

final class TotpSecretStore {
    private static final String KEY_ALIAS = "winhello-totp-encryption-key";
    private static final String PREFS = "winhello";

    private TotpSecretStore() { }

    static void save(Context context, String pcId, String base32Secret) throws Exception {
        byte[] plaintext = base32Secret.getBytes(StandardCharsets.US_ASCII);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] packed = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("totp_" + pcId, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply();
    }

    static String load(Context context, String pcId) throws Exception {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("totp_" + pcId, null);
        if (value == null) return null;
        byte[] packed = Base64.decode(value, Base64.NO_WRAP);
        if (packed.length < 13) throw new IllegalStateException("Stored TOTP secret is invalid.");
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        byte[] iv = new byte[12];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.US_ASCII);
    }

    static void delete(Context context, String pcId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("totp_" + pcId)
                .remove("pin_" + pcId)
                .remove("paired_" + pcId)
                .apply();
    }

    static String code(String base32Secret, long epochSeconds) throws Exception {
        byte[] secret = decodeBase32(base32Secret);
        long counter = epochSeconds / 30;
        byte[] message = ByteBuffer.allocate(8).putLong(counter).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] hash = mac.doFinal(message);
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) |
                ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private static byte[] decodeBase32(String value) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : value.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            int index = alphabet.indexOf(character);
            if (index < 0) throw new IllegalArgumentException("The scanned TOTP secret is not valid Base32.");
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                result.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return result.toByteArray();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS))
            return (SecretKey) store.getKey(KEY_ALIAS, null);

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
