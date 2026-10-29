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

final class PhoneSigningKey {
    private static final String KEYSTORE = "AndroidKeyStore";

    private PhoneSigningKey() { }

    static String publicKeyBase64(String alias) throws Exception {
        KeyPair pair = getOrCreate(alias);
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    static Signature newSignature(String alias) throws Exception {
        KeyPair pair = getOrCreate(alias);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign((PrivateKey) pair.getPrivate());
        return signature;
    }

    static void delete(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
    }

    private static KeyPair getOrCreate(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) {
            return new KeyPair(keyStore.getCertificate(alias).getPublicKey(),
                    (PrivateKey) keyStore.getKey(alias, null));
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build();
        generator.initialize(spec);
        return generator.generateKeyPair();
    }
}
