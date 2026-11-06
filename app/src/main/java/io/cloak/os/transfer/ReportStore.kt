package io.cloak.os.transfer

import android.content.Context

/** Reports kept by the receiving side. Main copy: waiting to be delivered. Space copy: waiting to be asked for. */
object ReportStore {
    private const val MAX = 20

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("transfer_reports", Context.MODE_PRIVATE)

    fun save(context: Context, report: TransferReport, deliver: Boolean) {
        prefs(context).edit()
            .putString("r_${report.id}", report.encode())
            .putLong("t_${report.id}", System.currentTimeMillis())
            .putBoolean("d_${report.id}", deliver)
            .commit()
        prune(context)
    }

    fun get(context: Context, id: String): TransferReport? {
        if (!TransferId.isValid(id)) return null
        return TransferReport.decode(prefs(context).getString("r_$id", null))
    }

    fun undelivered(context: Context): List<TransferReport> {
        val p = prefs(context)
        return p.all.keys.filter { it.startsWith("d_") && p.getBoolean(it, false) }
            .mapNotNull { get(context, it.removePrefix("d_")) }
    }

    fun markDelivered(context: Context, id: String) {
        prefs(context).edit().putBoolean("d_$id", false).commit()
    }

    private fun prune(context: Context) {
        val p = prefs(context)
        val ids = p.all.keys.filter { it.startsWith("t_") }.map { it.removePrefix("t_") }
        if (ids.size <= MAX) return
        val oldest = ids.sortedBy { p.getLong("t_$it", 0L) }.take(ids.size - MAX)
        val e = p.edit()
        oldest.forEach { e.remove("r_$it").remove("t_$it").remove("d_$it") }
        e.commit()
    }
}