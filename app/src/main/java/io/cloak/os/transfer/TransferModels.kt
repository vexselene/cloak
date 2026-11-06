package io.cloak.os.transfer

import android.net.Uri
import java.util.Locale

enum class ConflictPolicy { RENAME, REPLACE, SKIP }

enum class Op { COPY, MOVE }

enum class Phase { PREPARING, COPYING, SETTLING, DONE, CANCELLED, FAILED }

class TransferJob(
    val sources: List<Uri>,
    val destTree: Uri,
    val policy: ConflictPolicy,
    val op: Op = Op.COPY,
    /** Set for in-app transfers. Names the move so the report can be matched to its originals. */
    val id: String? = null,
    /** Persisted grants taken for this job. They are released when it ends. */
    val releaseAfter: List<Uri> = emptyList()
)

data class TransferState(
    val phase: Phase,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val copied: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val current: String = "",
    val recent: List<String> = emptyList(),
    val interrupted: Boolean = false,
    val op: Op = Op.COPY,
    /** A finished in-app move: the originals are removed when Cloak is next opened. */
    val needsSettle: Boolean = false,
    /** This state describes removing originals, not copying. */
    val settle: Boolean = false
) {
    val running: Boolean
        get() = phase == Phase.PREPARING || phase == Phase.COPYING || phase == Phase.SETTLING

    val fraction: Float?
        get() = when {
            bytesTotal > 0L -> (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
            filesTotal > 0 -> (filesDone.toFloat() / filesTotal).coerceIn(0f, 1f)
            else -> null
        }
}

object TransferFormat {
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    /** A file name that is safe to hand to a document provider. */
    fun safeName(raw: String?, fallback: String): String {
        val cleaned = (raw ?: "")
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
        val name = if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
        return name.take(200)
    }
}