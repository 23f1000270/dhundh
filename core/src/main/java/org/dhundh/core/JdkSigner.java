package org.dhundh.core;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** Ed25519 using the JDK's built-in provider (Java 15+). Used on the JVM and in tests. */
public final class JdkSigner implements Signer {

    // DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519; the raw key follows it.
    private static final byte[] X509_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private final KeyPair pair;

    public JdkSigner() {
        try {
            pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] publicKey() {
        byte[] enc = pair.getPublic().getEncoded();
        return Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    }

    @Override
    public byte[] sign(byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(byte[] rawPublicKey, byte[] message, byte[] signature) {
        try {
            byte[] der = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + 32);
            System.arraycopy(rawPublicKey, 0, der, X509_PREFIX.length, 32);
            PublicKey pk = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(pk);
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
