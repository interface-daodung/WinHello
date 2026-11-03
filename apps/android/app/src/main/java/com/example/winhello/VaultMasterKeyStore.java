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
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted hardware-backed store for the Master Key Vault (mkv) master key.
 * Used to unlock PC vaults over the local network via TLS pinning.
 */
final class VaultMasterKeyStore {
    private static final String KEY_ALIAS = "winhello-vault-master-key";
    private static final String PREFS = "winhello";
    private static final String PREF_KEY = "vault_master_key_v1";

    private VaultMasterKeyStore() { }

    static boolean hasKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_KEY);
    }

    static void save(Context context, String base32MasterKey) throws Exception {
        String clean = base32MasterKey.trim().toUpperCase(java.util.Locale.ROOT);
        // Validate Base32 format
        decodeBase32(clean);
        byte[] plaintext = clean.getBytes(StandardCharsets.US_ASCII);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] packed = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply();
    }

    static String load(Context context) throws Exception {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_KEY, null);
        if (value == null) return null;
        byte[] packed = Base64.decode(value, Base64.NO_WRAP);
        if (packed.length < 13) throw new IllegalStateException("Stored Vault Master Key is corrupted.");
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        byte[] iv = new byte[12];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.US_ASCII);
    }

    static void delete(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(PREF_KEY)
                .apply();
    }

    static String currentTotp(String base32Secret) {
        try {
            byte[] secret = decodeBase32(base32Secret);
            long counter = (System.currentTimeMillis() / 1000) / 30;
            byte[] message = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(message);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) |
                    ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (Exception ex) {
            return "------";
        }
    }

    private static byte[] decodeBase32(String value) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : value.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (character == '=' || character == ' ' || character == '-') continue;
            int index = alphabet.indexOf(character);
            if (index < 0) throw new IllegalArgumentException("The master key is not valid Base32: " + character);
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
