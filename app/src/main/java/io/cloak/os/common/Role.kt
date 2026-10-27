package io.cloak.os.common

import android.content.Context
import android.os.UserManager

enum class Role {
    MAIN, AGENT;

    companion object {
        fun detect(context: Context): Role =
            if (context.getSystemService(UserManager::class.java).isManagedProfile) AGENT else MAIN
    }
}