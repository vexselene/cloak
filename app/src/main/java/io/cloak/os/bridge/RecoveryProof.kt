package io.cloak.os.bridge

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object RecoveryProof {
    const val EXTRA_SECRET = "rsecret"
    const val EXTRA_TS = "rts"
    const val EXTRA_PROOF = "rproof"
    const val WINDOW_MS = 5 * 60 * 1000L
    const val MAX_PROOF_LEN = 64

    private const val LABEL = "cloak/recovery/proof/v1"

    /** The new pairing secret has the same shape as any other secret: 32 random bytes in Base64. */
    fun isValidSecret(secret: String): Boolean = RecoveryToken.isValidKey(secret)

    /** HMAC over the new secret and a timestamp, keyed by the key derived from the token. */
    fun compute(key: String, newSecret: String, ts: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"))
        val message = "$LABEL|${newSecret.length}:$newSecret|$ts"
        return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun matches(key: String, newSecret: String, ts: Long, proof: String): Boolean {
        val expected = runCatching { compute(key, newSecret, ts) }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            proof.toByteArray(Charsets.UTF_8)
        )
    }

    /** Newer than the last accepted proof, and close to the phone's clock. */
    fun isFresh(ts: Long, lastAccepted: Long, now: Long): Boolean =
        ts > lastAccepted && abs(now - ts) <= WINDOW_MS
}