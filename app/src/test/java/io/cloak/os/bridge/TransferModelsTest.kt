package io.cloak.os.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferModelsTest {
    @Test fun bytesAreFormattedWithOneDecimal() {
        assertEquals("0 B", TransferFormat.bytes(0))
        assertEquals("1023 B", TransferFormat.bytes(1023))
        assertEquals("1.0 KB", TransferFormat.bytes(1024))
        assertEquals("1.5 KB", TransferFormat.bytes(1536))
        assertEquals("1.0 MB", TransferFormat.bytes(1L shl 20))
        assertEquals("1.0 GB", TransferFormat.bytes(1L shl 30))
    }

    @Test fun safeNameReplacesSeparatorsAndControlCharacters() {
        assertEquals("a_b", TransferFormat.safeName("a/b", "x"))
        assertEquals("a_b", TransferFormat.safeName("a\\b", "x"))
        assertEquals("a_b", TransferFormat.safeName("a\nb", "x"))
    }

    @Test fun safeNameFallsBackForEmptyAndDotNames() {
        listOf(null, "", "   ", ".", "..").forEach {
            assertEquals("fallback", TransferFormat.safeName(it, "fallback"))
        }
    }

    @Test fun safeNameIsCapped() {
        assertEquals(200, TransferFormat.safeName("a".repeat(500), "x").length)
    }

    @Test fun fractionPrefersBytesThenFilesThenNothing() {
        assertEquals(0.5f, TransferState(Phase.COPYING, bytesDone = 50, bytesTotal = 100).fraction!!, 0.0001f)
        assertEquals(0.25f, TransferState(Phase.COPYING, filesDone = 1, filesTotal = 4).fraction!!, 0.0001f)
        assertNull(TransferState(Phase.PREPARING).fraction)
    }

    @Test fun fractionIsClamped() {
        assertEquals(1f, TransferState(Phase.COPYING, bytesDone = 500, bytesTotal = 100).fraction!!, 0.0001f)
    }

    @Test fun onlyPreparingAndCopyingCountAsRunning() {
        assertTrue(TransferState(Phase.PREPARING).running)
        assertTrue(TransferState(Phase.COPYING).running)
        listOf(Phase.DONE, Phase.CANCELLED, Phase.FAILED).forEach {
            assertFalse("$it", TransferState(it).running)
        }
    }
}