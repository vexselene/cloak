package io.cloak.os.bridge

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object Auth {
    const val EXTRA_TS = "ts"
    const val EXTRA_SIG = "sig"
    const val BUNDLE_SECRET = "secret"
    private const val KEY_SECRET = "secret"
    private const val KEY_LAST_TS = "last_ts"
    private const val MAX_SKEW_MS = 60_000L

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun generateSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun saveSecret(context: Context, secret: String) {
        prefs(context).edit().putString(KEY_SECRET, secret).commit()
    }

    private fun mac(secret: String, cmd: String, ts: Long): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(Base64.decode(secret, Base64.NO_WRAP), "HmacSHA256"))
        return Base64.encodeToString(m.doFinal("$cmd|$ts".toByteArray()), Base64.NO_WRAP)
    }

    fun sign(context: Context, cmd: String, ts: Long): String? {
        val secret = prefs(context).getString(KEY_SECRET, null) ?: return null
        return mac(secret, cmd, ts)
    }

    fun verify(context: Context, cmd: String, ts: Long, sig: String?): Boolean {
        val p = prefs(context)
        val secret = p.getString(KEY_SECRET, null) ?: return false
        if (sig == null) return false
        if (ts <= p.getLong(KEY_LAST_TS, 0L)) return false
        if (abs(System.currentTimeMillis() - ts) > MAX_SKEW_MS) return false
        val ok = MessageDigest.isEqual(mac(secret, cmd, ts).toByteArray(), sig.toByteArray())
        if (ok) p.edit().putLong(KEY_LAST_TS, ts).commit()
        return ok
    }
}