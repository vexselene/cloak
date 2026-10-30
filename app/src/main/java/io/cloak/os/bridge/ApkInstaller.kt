package io.cloak.os.admin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object InstallCoordinator {
    @Volatile private var latch: CountDownLatch? = null
    fun arm() { latch = CountDownLatch(1) }
    fun await(ms: Long) { latch?.await(ms, TimeUnit.MILLISECONDS) }
    fun release() { latch?.countDown() }
}

object ApkInstaller {
    private const val TAG = "SecondSpace/Installer"

    fun install(context: Context, packageName: String, uris: List<Uri>): Result<Unit> = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            uris.forEach { uri ->
                val name = uri.lastPathSegment ?: error("bad uri: $uri")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    session.openWrite(name, 0, -1).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val pi = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            InstallCoordinator.arm()
            session.commit(pi.intentSender)
        }
        Log.d(TAG, "session committed for $packageName with ${uris.size} files")
        Unit
    }.onFailure { Log.e(TAG, "install failed", it) }
}