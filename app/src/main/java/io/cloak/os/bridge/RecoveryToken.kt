package io.cloak.os.bridge

import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RecoveryToken {
    const val DATA_LEN = 26
    const val CHECK_LEN = 2
    const val TOTAL_LEN = DATA_LEN + CHECK_LEN

    /** 32 symbols, no 0/O/1/I, so a handwritten token cannot be misread. */
    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    private const val MAX_INPUT = 128
    private const val KEY_LABEL = "cloak/recovery/key/v1"
    private val DASHED = Regex("(?:[2-9A-HJ-NP-Z]{4}-){6}[2-9A-HJ-NP-Z]{4}")

    fun generate(random: Random = SecureRandom()): String {
        val data = buildString {
            repeat(DATA_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        return data + check(data)
    }

    /** Two weighted sums mod 32: catches any single wrong character and any adjacent swap. */
    private fun check(data: String): String {
        var sum = 0
        var weighted = 0
        data.forEachIndexed { i, c ->
            val v = ALPHABET.indexOf(c)
            sum = (sum + v) % 32
            weighted = (weighted + (i + 1) * v) % 32
        }
        return "${ALPHABET[sum]}${ALPHABET[weighted]}"
    }

    fun format(token: String): String = token.chunked(4).joinToString("-")

    /** Typed or pasted input. Ignores case, spaces and dashes. Returns the clean token or null. */
    fun parse(input: String): String? {
        if (input.length > MAX_INPUT) return null
        val cleaned = input.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != TOTAL_LEN || cleaned.any { it !in ALPHABET }) return null
        val data = cleaned.substring(0, DATA_LEN)
        return if (check(data) == cleaned.substring(DATA_LEN)) cleaned else null
    }

    /** Text read from a file: finds the dashed token, ignoring any note around it. */
    fun extract(text: String): String? {
        val upper = text.uppercase()
        DASHED.findAll(upper).forEach { m -> parse(m.value)?.let { return it } }
        return if (upper.length <= 64) parse(upper) else null
    }

    fun exportText(token: String): String =
        "Second Space recovery token\n" +
                "Keep this file private. With this token and your work PIN, " +
                "anyone can take over your Second Space.\n\n" +
                format(token) + "\n"

    /** What the work copy stores. Base64 of 32 bytes. The token itself never leaves the user. */
    fun deriveKey(token: String): String {
        val clean = requireNotNull(parse(token)) { "invalid token" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clean.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(KEY_LABEL.toByteArray(Charsets.UTF_8)))
    }

    fun isValidKey(key: String): Boolean =
        key.length == 44 && runCatching { Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false)
}