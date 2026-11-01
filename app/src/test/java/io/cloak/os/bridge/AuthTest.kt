package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AuthTest {
    private val secret = Auth.generateSecret()
    private val otherSecret = Auth.generateSecret()

    private fun sig(cmd: String = "lock_now", arg: String = "", seq: Long = 5L, key: String = secret) =
        Auth.mac(key, cmd, arg, seq)

    private fun valid(
        cmd: String = "lock_now",
        arg: String = "",
        seq: Long = 5L,
        sig: String? = sig(),
        lastSeq: Long = 0L,
        key: String = secret
    ) = Auth.isValid(key, lastSeq, cmd, arg, seq, sig)

    // --- accepting ---

    @Test fun validSignatureIsAccepted() {
        assertTrue(valid())
    }

    @Test fun validSignatureWithArgumentIsAccepted() {
        val s = sig(cmd = "freeze", arg = "com.example.app")
        assertTrue(valid(cmd = "freeze", arg = "com.example.app", sig = s))
    }

    @Test fun sequenceAboveLastSeenIsAccepted() {
        assertTrue(valid(seq = 6L, sig = sig(seq = 6L), lastSeq = 5L))
    }

    @Test fun largeGapInSequenceIsAccepted() {
        assertTrue(valid(seq = 1_000_000L, sig = sig(seq = 1_000_000L), lastSeq = 5L))
    }

    @Test fun unicodeArgumentIsAccepted() {
        val arg = "héllo wörld ✓"
        assertTrue(valid(arg = arg, sig = sig(arg = arg)))
    }

    // --- replay protection ---

    @Test fun sameSequenceIsRejectedAsReplay() {
        assertFalse(valid(seq = 5L, sig = sig(seq = 5L), lastSeq = 5L))
    }

    @Test fun olderSequenceIsRejected() {
        assertFalse(valid(seq = 4L, sig = sig(seq = 4L), lastSeq = 5L))
    }

    @Test fun sequenceZeroIsRejectedOnFreshState() {
        assertFalse(valid(seq = 0L, sig = sig(seq = 0L), lastSeq = 0L))
    }

    @Test fun negativeSequenceIsRejected() {
        assertFalse(valid(seq = -1L, sig = sig(seq = -1L), lastSeq = 0L))
    }

    // --- tampering ---

    @Test fun changedCommandIsRejected() {
        assertFalse(valid(cmd = "delete_space", sig = sig(cmd = "lock_now")))
    }

    @Test fun changedArgumentIsRejected() {
        val s = sig(cmd = "freeze", arg = "com.example.a")
        assertFalse(valid(cmd = "freeze", arg = "com.example.b", sig = s))
    }

    @Test fun changedSequenceIsRejected() {
        assertFalse(valid(seq = 6L, sig = sig(seq = 5L)))
    }

    @Test fun signatureFromDifferentSecretIsRejected() {
        assertFalse(valid(sig = sig(key = otherSecret)))
    }

    @Test fun nullSignatureIsRejected() {
        assertFalse(valid(sig = null))
    }

    @Test fun emptySignatureIsRejected() {
        assertFalse(valid(sig = ""))
    }

    @Test fun garbageSignatureIsRejected() {
        assertFalse(valid(sig = "not-a-real-signature"))
    }

    @Test fun truncatedSignatureIsRejected() {
        assertFalse(valid(sig = sig().dropLast(4)))
    }

    @Test fun corruptSecretIsRejectedWithoutThrowing() {
        assertFalse(Auth.isValid("!!! not base64 !!!", 0L, "lock_now", "", 5L, "anything"))
    }

    // --- signing format ---

    @Test fun macIsDeterministic() {
        assertEquals(sig(), sig())
    }

    @Test fun macChangesWithEveryField() {
        val base = sig(cmd = "freeze", arg = "com.example.app", seq = 5L)
        assertNotEquals(base, sig(cmd = "unfreeze", arg = "com.example.app", seq = 5L))
        assertNotEquals(base, sig(cmd = "freeze", arg = "com.example.other", seq = 5L))
        assertNotEquals(base, sig(cmd = "freeze", arg = "com.example.app", seq = 6L))
        assertNotEquals(base, sig(cmd = "freeze", arg = "com.example.app", seq = 5L, key = otherSecret))
    }

    @Test fun movingTextBetweenCommandAndArgumentChangesTheMac() {
        assertNotEquals(sig(cmd = "ab", arg = "c"), sig(cmd = "a", arg = "bc"))
    }

    @Test fun separatorCharactersCannotBeUsedToForgeAnotherMessage() {
        assertNotEquals(sig(cmd = "a|1:b", arg = "c"), sig(cmd = "a", arg = "b|1:c"))
    }

    // --- secret generation ---

    @Test fun generatedSecretIs32Bytes() {
        assertEquals(32, Base64.getDecoder().decode(Auth.generateSecret()).size)
    }

    @Test fun generatedSecretsDiffer() {
        assertNotEquals(Auth.generateSecret(), Auth.generateSecret())
    }
}