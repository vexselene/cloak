package io.cloak.os.bridge

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import io.cloak.os.common.Logx
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Auth {
    const val EXTRA_SEQ = "seq"
    const val EXTRA_SIG = "sig"
    const val BUNDLE_SECRET = "secret"

    private const val TAG = "SecondSpace/Auth"
    private const val KEY_SECRET = "secret"
    private const val KEY_SEND_SEQ = "send_seq"
    private const val KEY_LAST_SEQ = "last_seq"
    private val lock = Any()

    class Signed(val seq: Long, val sig: String)

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun generateSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun saveSecret(context: Context, secret: String) {
        prefs(context).edit().putString(KEY_SECRET, secret).commit()
    }

    fun hasSecret(context: Context): Boolean =
        prefs(context).getString(KEY_SECRET, null) != null

    fun storeFromProvisioning(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        extras?.getString(BUNDLE_SECRET)?.let {
            saveSecret(context, it)
            Logx.d(TAG, "pairing secret stored")
        }
    }

    internal fun mac(secret: String, cmd: String, arg: String, seq: Long): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"))
        val message = "${cmd.length}:$cmd|${arg.length}:$arg|$seq"
        return Base64.getEncoder().encodeToString(m.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    internal fun isValid(
        secret: String, lastSeq: Long, cmd: String, arg: String, seq: Long, sig: String?
    ): Boolean {
        if (sig == null || seq <= lastSeq) return false
        val expected = runCatching { mac(secret, cmd, arg, seq) }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            sig.toByteArray(Charsets.UTF_8)
        )
    }

    fun sign(context: Context, cmd: String, arg: String): Signed? = synchronized(lock) {
        val p = prefs(context)
        val secret = p.getString(KEY_SECRET, null) ?: return null
        val seq = p.getLong(KEY_SEND_SEQ, 0L) + 1
        if (!p.edit().putLong(KEY_SEND_SEQ, seq).commit()) return null
        Signed(seq, mac(secret, cmd, arg, seq))
    }

    fun verify(context: Context, cmd: String, arg: String, seq: Long, sig: String?): Boolean =
        synchronized(lock) {
            val p = prefs(context)
            val secret = p.getString(KEY_SECRET, null) ?: return false
            val ok = isValid(secret, p.getLong(KEY_LAST_SEQ, 0L), cmd, arg, seq, sig)
            ok && p.edit().putLong(KEY_LAST_SEQ, seq).commit()
        }
}