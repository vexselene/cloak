package io.cloak.os.admin

import android.content.Context
import android.os.UserManager
import io.cloak.os.bridge.Auth
import io.cloak.os.bridge.RecoveryLockout
import io.cloak.os.bridge.RecoveryProof
import io.cloak.os.bridge.RecoveryStore

enum class RecoveryOutcome {
    NOT_ENABLED, NOT_UNLOCKED, LOCKED_OUT, MALFORMED, EXPIRED, BAD_PROOF, ACCEPTED
}

object RecoveryGate {
    private const val PENDING_MS = 2 * 60 * 1000L
    private val lock = Any()

    /** Checks an unsigned recover request. On ACCEPTED the new secret is stored as pending. */
    fun evaluate(context: Context, newSecret: String?, ts: Long, proof: String?): RecoveryOutcome =
        synchronized(lock) {
            val key = RecoveryStore.loadKey(context) ?: return RecoveryOutcome.NOT_ENABLED
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
                return RecoveryOutcome.NOT_UNLOCKED
            }
            val now = System.currentTimeMillis()
            val failures = RecoveryStore.failures(context)
            if (RecoveryLockout.remainingMs(failures, RecoveryStore.lockedUntil(context), now) > 0L) {
                return RecoveryOutcome.LOCKED_OUT
            }
            if (newSecret == null || proof == null ||
                !RecoveryProof.isValidSecret(newSecret) ||
                proof.length > RecoveryProof.MAX_PROOF_LEN
            ) {
                return RecoveryOutcome.MALFORMED
            }
            if (!RecoveryProof.isFresh(ts, RecoveryStore.lastProofTs(context), now)) {
                return RecoveryOutcome.EXPIRED
            }
            // Count the attempt before comparing, so a crash cannot be used to skip the count.
            val next = failures + 1
            RecoveryStore.recordFailure(context, next, RecoveryLockout.lockedUntilAfterFailure(next, now))
            if (!RecoveryProof.matches(key, newSecret, ts, proof)) return RecoveryOutcome.BAD_PROOF

            RecoveryStore.recordAccepted(context, ts)
            RecoveryStore.setPending(context, newSecret, now + PENDING_MS)
            RecoveryOutcome.ACCEPTED
        }

    /** Call only after the work PIN was confirmed. Makes the pending secret the real one. */
    fun commit(context: Context): Boolean = synchronized(lock) {
        val secret = RecoveryStore.pendingSecret(context) ?: return false
        val until = RecoveryStore.pendingUntil(context)
        if (System.currentTimeMillis() > until) {
            RecoveryStore.clearPending(context)
            return false
        }
        val ok = Auth.replaceSecret(context, secret)
        RecoveryStore.clearPending(context)
        ok
    }

    fun discard(context: Context) = synchronized(lock) { RecoveryStore.clearPending(context) }
}