package io.cloak.os.transfer

import java.security.SecureRandom

data class SourceResult(val index: Int, val files: Int, val bytes: Long)

/** What the receiving side verified. The sender uses it to decide what it may delete. */
data class TransferReport(
    val id: String,
    val op: Op,
    val complete: Boolean,
    val results: List<SourceResult>
) {
    fun encode(): String =
        id + ";" + (if (op == Op.MOVE) "M" else "C") + ";" + (if (complete) "1" else "0") + ";" +
                results.joinToString(",") { "${it.index}:${it.files}:${it.bytes}" }

    companion object {
        private const val MAX_LEN = 16_384
        private const val MAX_RESULTS = 500

        fun decode(s: String?): TransferReport? {
            if (s == null || s.length > MAX_LEN) return null
            val parts = s.split(';', limit = 5)
            if (parts.size != 4) return null
            if (!TransferId.isValid(parts[0])) return null
            val op = when (parts[1]) {
                "M" -> Op.MOVE
                "C" -> Op.COPY
                else -> return null
            }
            val complete = when (parts[2]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            val results = ArrayList<SourceResult>()
            if (parts[3].isNotEmpty()) {
                val items = parts[3].split(',')
                if (items.size > MAX_RESULTS) return null
                for (item in items) {
                    val f = item.split(':')
                    if (f.size != 3) return null
                    val index = f[0].toIntOrNull() ?: return null
                    val files = f[1].toIntOrNull() ?: return null
                    val bytes = f[2].toLongOrNull() ?: return null
                    if (index < 0 || files < 0 || bytes < 0) return null
                    results.add(SourceResult(index, files, bytes))
                }
            }
            return TransferReport(parts[0], op, complete, results)
        }
    }
}

object TransferId {
    private val HEX = Regex("[0-9a-f]{16}")

    fun isValid(s: String): Boolean = HEX.matches(s)

    fun generate(): String =
        ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}