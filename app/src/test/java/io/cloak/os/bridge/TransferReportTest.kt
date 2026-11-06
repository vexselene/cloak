package io.cloak.os.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferReportTest {
    private val id = "0123456789abcdef"

    @Test fun reportSurvivesARoundTrip() {
        val r = TransferReport(id, Op.MOVE, true, listOf(SourceResult(0, 3, 1024), SourceResult(2, 0, 0)))
        assertEquals(r, TransferReport.decode(r.encode()))
    }

    @Test fun emptyResultsAndCopyOpRoundTrip() {
        val r = TransferReport(id, Op.COPY, false, emptyList())
        assertEquals(r, TransferReport.decode(r.encode()))
    }

    @Test fun largeNumbersRoundTrip() {
        val r = TransferReport(id, Op.MOVE, true, listOf(SourceResult(499, Int.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals(r, TransferReport.decode(r.encode()))
    }

    @Test fun badReportsAreRejected() {
        listOf(
            null, "", "X", "short;M;1;",
            "0123456789ABCDEF;M;1;",
            "$id;X;1;", "$id;M;2;", "$id;M;1",
            "$id;M;1;0:1", "$id;M;1;0:1:2:3", "$id;M;1;a:1:2",
            "$id;M;1;-1:1:2", "$id;M;1;0:-1:2", "$id;M;1;0:1:-2",
            "$id;M;1;0:1:2;extra", "$id;M;1;0:1:2,",
            "x".repeat(20_000)
        ).forEach { assertNull("'${it?.take(30)}'", TransferReport.decode(it)) }
    }

    @Test fun tooManyResultsAreRejected() {
        val many = (0 until 501).joinToString(",") { "$it:1:1" }
        assertNull(TransferReport.decode("$id;M;1;$many"))
        val ok = (0 until 500).joinToString(",") { "$it:1:1" }
        assertEquals(500, TransferReport.decode("$id;M;1;$ok")!!.results.size)
    }

    @Test fun idsAreSixteenLowercaseHexCharacters() {
        assertTrue(TransferId.isValid(id))
        listOf("", "0123", id + "0", id.uppercase(), "zzzzzzzzzzzzzzzz", id.dropLast(1) + "g")
            .forEach { assertFalse("'$it'", TransferId.isValid(it)) }
    }

    @Test fun generatedIdsAreValidAndDiffer() {
        val a = TransferId.generate()
        val b = TransferId.generate()
        assertTrue(TransferId.isValid(a))
        assertNotEquals(a, b)
    }

    @Test fun settlingCountsAsRunning() {
        assertTrue(TransferState(Phase.SETTLING).running)
    }
}