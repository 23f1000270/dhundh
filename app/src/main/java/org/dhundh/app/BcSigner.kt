package org.dhundh.app

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.dhundh.core.Signer
import java.security.SecureRandom

/**
 * Ed25519 via BouncyCastle, with a fresh key every few minutes so that
 * warnings from the same car cannot be linked into a track for long.
 */
class BcSigner(private val rotateMs: Long = 5 * 60_000L) : Signer {

    private val random = SecureRandom()
    private var key = Ed25519PrivateKeyParameters(random)
    private var createdAt = System.currentTimeMillis()

    /** Rotation happens only here, when a new message is being built, so the
     *  key a message carries is always the key that then signs it. */
    @Synchronized
    override fun publicKey(): ByteArray {
        if (System.currentTimeMillis() - createdAt > rotateMs) {
            key = Ed25519PrivateKeyParameters(random)
            createdAt = System.currentTimeMillis()
        }
        return key.generatePublicKey().encoded
    }

    @Synchronized
    override fun sign(message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, key)
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        s.update(message, 0, message.size)
        s.verifySignature(signature)
    } catch (e: Exception) {
        false
    }
}
