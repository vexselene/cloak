package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RecoveryProofTest {
    private val key = RecoveryToken.deriveKey(RecoveryToken.generate(Random(1)))
    private val otherKey = RecoveryToken.deriveKey(RecoveryToken.generate(Random(2)))
    private val secret = Auth.generateSecret()
    private val ts = 1_700_000_000_000L

    private fun proof(k: String = key, s: String = secret, t: Long = ts) = RecoveryProof.compute(k, s, t)

    @Test fun matchingProofIsAccepted() {
        assertTrue(RecoveryProof.matches(key, secret, ts, proof()))
    }

    @Test fun proofIsDeterministic() {
        assertEquals(proof(), proof())
    }

    @Test fun proofChangesWithEveryInput() {
        val base = proof()
        assertNotEquals(base, proof(k = otherKey))
        assertNotEquals(base, proof(s = Auth.generateSecret()))
        assertNotEquals(base, proof(t = ts + 1))
    }

    @Test fun wrongKeyIsRejected() {
        assertFalse(RecoveryProof.matches(key, secret, ts, proof(k = otherKey)))
    }

    @Test fun tamperedSecretOrTimestampIsRejected() {
        val p = proof()
        assertFalse(RecoveryProof.matches(key, Auth.generateSecret(), ts, p))
        assertFalse(RecoveryProof.matches(key, secret, ts + 1, p))
    }

    @Test fun garbageProofIsRejected() {
        assertFalse(RecoveryProof.matches(key, secret, ts, ""))
        assertFalse(RecoveryProof.matches(key, secret, ts, "not-a-proof"))
        assertFalse(RecoveryProof.matches(key, secret, ts, proof().dropLast(3)))
    }

    @Test fun corruptKeyIsRejectedWithoutThrowing() {
        assertFalse(RecoveryProof.matches("!!! not base64 !!!", secret, ts, "anything"))
    }

    @Test fun proofDoesNotContainKeyOrSecret() {
        val p = proof()
        assertFalse(p.contains(key))
        assertFalse(p.contains(secret))
    }

    @Test fun secretShapeIsValidated() {
        assertTrue(RecoveryProof.isValidSecret(Auth.generateSecret()))
        assertFalse(RecoveryProof.isValidSecret(""))
        assertFalse(RecoveryProof.isValidSecret("short"))
        assertFalse(RecoveryProof.isValidSecret("x".repeat(44)))
    }

    @Test fun freshnessAcceptsInWindowNewerTimestamps() {
        assertTrue(RecoveryProof.isFresh(ts, 0L, ts))
        assertTrue(RecoveryProof.isFresh(ts, ts - 1, ts + RecoveryProof.WINDOW_MS))
        assertTrue(RecoveryProof.isFresh(ts, 0L, ts - RecoveryProof.WINDOW_MS))
    }

    @Test fun freshnessRejectsOldFutureAndReplayedTimestamps() {
        assertFalse(RecoveryProof.isFresh(ts, 0L, ts + RecoveryProof.WINDOW_MS + 1))
        assertFalse(RecoveryProof.isFresh(ts, 0L, ts - RecoveryProof.WINDOW_MS - 1))
        assertFalse(RecoveryProof.isFresh(ts, ts, ts))
        assertFalse(RecoveryProof.isFresh(ts, ts + 1, ts))
        assertFalse(RecoveryProof.isFresh(-5L, 0L, 0L))
    }
}