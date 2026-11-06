package io.cloak.os.transfer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class SourceSnap(val files: Int, val bytes: Long)

class LedgerEntry(
    val id: String,
    val createdAt: Long,
    val sources: List<Uri>,
    val snaps: List<SourceSnap>
)

/** What the sending side picked and how big it was then. Originals are only deleted if this still holds. */
object MoveLedger {
    private const val KEY = "e_"
    private const val MAX_AGE_MS = 7L * 24 * 3_600_000L

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("transfer_ledger", Context.MODE_PRIVATE)

    fun save(context: Context, e: LedgerEntry): Boolean {
        val json = JSONObject()
            .put("t", e.createdAt)
            .put("u", JSONArray(e.sources.map { it.toString() }))
            .put("f", JSONArray(e.snaps.map { it.files }))
            .put("b", JSONArray(e.snaps.map { it.bytes }))
        return prefs(context).edit().putString(KEY + e.id, json.toString()).commit()
    }

    fun get(context: Context, id: String): LedgerEntry? {
        if (!TransferId.isValid(id)) return null
        val raw = prefs(context).getString(KEY + id, null) ?: return null
        return runCatching {
            val j = JSONObject(raw)
            val u = j.getJSONArray("u")
            val f = j.getJSONArray("f")
            val b = j.getJSONArray("b")
            if (u.length() == 0 || u.length() != f.length() || u.length() != b.length()) {
                null
            } else {
                LedgerEntry(
                    id, j.getLong("t"),
                    List(u.length()) { Uri.parse(u.getString(it)) },
                    List(u.length()) { SourceSnap(f.getInt(it), b.getLong(it)) }
                )
            }
        }.getOrNull()
    }

    fun all(context: Context): List<LedgerEntry> =
        prefs(context).all.keys.filter { it.startsWith(KEY) }
            .mapNotNull { get(context, it.removePrefix(KEY)) }
            .sortedBy { it.createdAt }

    /** Forgets an entry and gives back the access that was kept for it. */
    fun remove(context: Context, id: String) {
        get(context, id)?.sources?.forEach { Grants.release(context, it) }
        prefs(context).edit().remove(KEY + id).commit()
    }

    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        all(context).filter { it.createdAt < cutoff }.forEach { remove(context, it.id) }
    }
}