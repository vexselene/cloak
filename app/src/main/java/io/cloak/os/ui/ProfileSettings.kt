package io.cloak.os.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

private val CANDIDATES = listOf(
    "android.settings.MANAGED_PROFILE_SETTINGS",
    Settings.ACTION_SYNC_SETTINGS,
    Settings.ACTION_SETTINGS
)

/** Opens the most specific system screen the phone offers. Returns false if none opened. */
fun openProfileSettings(context: Context): Boolean =
    CANDIDATES.any { action ->
        runCatching { context.startActivity(Intent(action)) }.isSuccess
    }