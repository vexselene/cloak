package io.cloak.os.common

import android.util.Log
import io.cloak.os.BuildConfig

object Logx {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, msg, t)
        else Log.e(tag, if (t == null) msg else "$msg (${t.javaClass.simpleName})")
    }
}