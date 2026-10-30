package io.cloak.os.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.UserHandle
import kotlin.and
import kotlin.toString

data class SpaceApp(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Drawable,
    val suspended: Boolean
)

object SpaceApps {
    fun profile(context: Context): UserHandle? =
        context.getSystemService(CrossProfileApps::class.java).targetUserProfiles.firstOrNull()

    fun list(context: Context): List<SpaceApp> {
        val user = profile(context) ?: return emptyList()
        val la = context.getSystemService(LauncherApps::class.java)
        return la.getActivityList(null, user)
            .filter { it.applicationInfo.packageName != context.packageName }
            .distinctBy { it.applicationInfo.packageName }
            .map {
                SpaceApp(
                    it.label.toString(),
                    it.applicationInfo.packageName,
                    it.componentName,
                    it.getBadgedIcon(0),
                    (it.applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, app: SpaceApp): Result<Unit> = runCatching {
        val user = profile(context) ?: error("no space")
        context.getSystemService(LauncherApps::class.java).startMainActivity(app.component, user, null, null)
    }
}