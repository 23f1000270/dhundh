package org.dhundh.core;

/**
 * Ed25519 signing with raw 32-byte public keys and 64-byte signatures.
 * The core library stays free of crypto dependencies; Android supplies an
 * implementation backed by BouncyCastle, and {@link JdkSigner} works on any
 * Java 15+ runtime.
 */
public interface Signer {
    /** Raw 32-byte public key of the current signing key. */
    byte[] publicKey();

    byte[] sign(byte[] message);

    boolean verify(byte[] publicKey, byte[] message, byte[] signature);
}
