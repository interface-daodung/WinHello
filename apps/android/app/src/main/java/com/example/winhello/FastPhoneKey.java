package com.example.winhello;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Separate key for fast approval. Android Keystore permits use only while the device is unlocked. */
final class FastPhoneKey {
    private static final String KEYSTORE = "AndroidKeyStore";

    private FastPhoneKey() { }

    static String alias(String pcId) { return "winhello_fast_" + pcId.replace("-", ""); }

    static String publicKeyBase64(String pcId) throws Exception {
        return Base64.getEncoder().encodeToString(getOrCreate(pcId).getPublic().getEncoded());
    }

    static byte[] sign(String pcId, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign((PrivateKey) getOrCreate(pcId).getPrivate());
        signature.update(payload);
        return signature.sign();
    }

    static void delete(String pcId) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(alias(pcId))) store.deleteEntry(alias(pcId));
    }

    private static KeyPair getOrCreate(String pcId) throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        String alias = alias(pcId);
        if (store.containsAlias(alias))
            return new KeyPair(store.getCertificate(alias).getPublicKey(), (PrivateKey) store.getKey(alias, null));
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUnlockedDeviceRequired(true)
                .build());
        return generator.generateKeyPair();
    }
}
