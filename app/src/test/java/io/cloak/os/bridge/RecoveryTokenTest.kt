package io.cloak.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Random

class RecoveryTokenTest {
    private val token = RecoveryToken.generate(Random(42))

    // --- generation ---

    @Test fun generatedTokenHasExpectedShape() {
        assertEquals(RecoveryToken.TOTAL_LEN, token.length)
        assertTrue(token.all { it in RecoveryToken.ALPHABET })
        assertEquals(token, RecoveryToken.parse(token))
    }

    @Test fun alphabetHas32UniqueSymbolsWithoutLookAlikes() {
        assertEquals(32, RecoveryToken.ALPHABET.toSet().size)
        listOf('0', 'O', '1', 'I').forEach { assertFalse("$it", it in RecoveryToken.ALPHABET) }
    }

    @Test fun seededGenerationIsDeterministic() {
        assertEquals(RecoveryToken.generate(Random(7)), RecoveryToken.generate(Random(7)))
    }

    @Test fun randomTokensDiffer() {
        assertNotEquals(RecoveryToken.generate(), RecoveryToken.generate())
    }

    @Test fun manyGeneratedTokensAreAllValid() {
        for (seed in 0L until 200L) {
            val t = RecoveryToken.generate(Random(seed))
            assertEquals(t, RecoveryToken.parse(t))
        }
    }

    // --- parsing ---

    @Test fun formatGroupsInFours() {
        val f = RecoveryToken.format(token)
        assertEquals(7, f.split("-").size)
        assertTrue(f.split("-").all { it.length == 4 })
        assertEquals(token, RecoveryToken.parse(f))
    }

    @Test fun parseIgnoresCaseSpacesAndDashes() {
        val messy = " " + RecoveryToken.format(token).lowercase().replace("-", " ") + "\n"
        assertEquals(token, RecoveryToken.parse(messy))
    }

    @Test fun parseRejectsWrongLength() {
        assertNull(RecoveryToken.parse(token.dropLast(1)))
        assertNull(RecoveryToken.parse(token + "2"))
        assertNull(RecoveryToken.parse(""))
    }

    @Test fun parseRejectsSymbolsOutsideTheAlphabet() {
        listOf('0', 'O', '1', 'I').forEach { bad ->
            assertNull("$bad", RecoveryToken.parse(bad + token.drop(1)))
        }
    }

    @Test fun parseRejectsHugeAndUnicodeInput() {
        assertNull(RecoveryToken.parse("x".repeat(500)))
        assertNull(RecoveryToken.parse("٢".repeat(RecoveryToken.TOTAL_LEN)))
    }

    // --- typo detection (guaranteed, so tested exhaustively) ---

    @Test fun anySingleWrongCharacterIsRejected() {
        for (seed in 0L until 20L) {
            val t = RecoveryToken.generate(Random(seed))
            for (i in t.indices) {
                for (c in RecoveryToken.ALPHABET) {
                    if (c == t[i]) continue
                    val bad = t.substring(0, i) + c + t.substring(i + 1)
                    assertNull("seed=$seed pos=$i char=$c", RecoveryToken.parse(bad))
                }
            }
        }
    }

    @Test fun anyAdjacentSwapInTheDataIsRejected() {
        for (seed in 0L until 50L) {
            val t = RecoveryToken.generate(Random(seed))
            for (i in 0 until RecoveryToken.DATA_LEN - 1) {
                if (t[i] == t[i + 1]) continue
                val chars = t.toCharArray()
                chars[i] = t[i + 1]
                chars[i + 1] = t[i]
                assertNull("seed=$seed pos=$i", RecoveryToken.parse(String(chars)))
            }
        }
    }

    // --- extracting from files ---

    @Test fun extractFindsTheTokenInAnExportedFile() {
        assertEquals(token, RecoveryToken.extract(RecoveryToken.exportText(token)))
    }

    @Test fun extractWorksOnABareToken() {
        assertEquals(token, RecoveryToken.extract(token))
        assertEquals(token, RecoveryToken.extract(RecoveryToken.format(token)))
    }

    @Test fun extractIgnoresSurroundingText() {
        val text = "my notes\nfor later: ${RecoveryToken.format(token)}\nthanks"
        assertEquals(token, RecoveryToken.extract(text))
    }

    @Test fun extractRejectsLookalikesAndNoise() {
        val bad = RecoveryToken.format(token).dropLast(1) + (if (token.last() == '2') "3" else "2")
        assertNull(RecoveryToken.extract("here: $bad"))
        assertNull(RecoveryToken.extract("nothing useful in here"))
        assertNull(RecoveryToken.extract(""))
    }

    @Test fun exportTextContainsTheFormattedToken() {
        assertTrue(RecoveryToken.exportText(token).contains(RecoveryToken.format(token)))
    }

    // --- derived key ---

    @Test fun derivedKeyIsValidAndDeterministic() {
        val key = RecoveryToken.deriveKey(token)
        assertEquals(44, key.length)
        assertEquals(32, Base64.getDecoder().decode(key).size)
        assertTrue(RecoveryToken.isValidKey(key))
        assertEquals(key, RecoveryToken.deriveKey(RecoveryToken.format(token)))
    }

    @Test fun differentTokensGiveDifferentKeys() {
        assertNotEquals(
            RecoveryToken.deriveKey(token),
            RecoveryToken.deriveKey(RecoveryToken.generate(Random(43)))
        )
    }

    @Test fun derivedKeyDoesNotContainTheToken() {
        assertFalse(RecoveryToken.deriveKey(token).contains(token))
    }

    @Test(expected = IllegalArgumentException::class)
    fun derivingFromAnInvalidTokenFails() {
        RecoveryToken.deriveKey("not a token")
    }

    @Test fun keyValidationRejectsBadInput() {
        assertFalse(RecoveryToken.isValidKey(""))
        assertFalse(RecoveryToken.isValidKey("not base64 at all!!"))
        assertFalse(RecoveryToken.isValidKey(Base64.getEncoder().encodeToString(ByteArray(31))))
        assertFalse(RecoveryToken.isValidKey(Base64.getEncoder().encodeToString(ByteArray(33))))
        assertNotNull(RecoveryToken.deriveKey(token))
    }
}