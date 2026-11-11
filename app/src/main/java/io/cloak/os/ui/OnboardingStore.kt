package io.cloak.os.ui

import android.content.Context

object OnboardingRules {
    /** Steps after the welcome screen: lock, Play Store, apps, protection. */
    const val STEP_COUNT = 4

    fun clamp(step: Int): Int = step.coerceIn(0, STEP_COUNT - 1)
    fun next(step: Int): Int = clamp(step + 1)
    fun previous(step: Int): Int = clamp(step - 1)
    fun isLast(step: Int): Boolean = clamp(step) == STEP_COUNT - 1

    /** A space that existed before onboarding was added counts as finished. */
    fun initialDone(spaceExists: Boolean): Boolean = spaceExists
}

object OnboardingStore {
    private const val KEY_INIT = "initialized"
    private const val KEY_DONE = "done"
    private const val KEY_STEP = "step"

    private fun prefs(context: Context) = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    /** Decides once, on the first run of this version, whether the user already has a space. */
    fun initialize(context: Context, spaceExists: Boolean) {
        val p = prefs(context)
        if (p.getBoolean(KEY_INIT, false)) return
        p.edit()
            .putBoolean(KEY_INIT, true)
            .putBoolean(KEY_DONE, OnboardingRules.initialDone(spaceExists))
            .putInt(KEY_STEP, 0)
            .apply()
    }

    fun isDone(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, true)

    fun step(context: Context): Int = OnboardingRules.clamp(prefs(context).getInt(KEY_STEP, 0))

    fun setStep(context: Context, step: Int) {
        prefs(context).edit().putInt(KEY_STEP, OnboardingRules.clamp(step)).apply()
    }

    fun finish(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).putInt(KEY_STEP, 0).apply()
    }

    /** Called when a new space is created, so a second space gets onboarding too. */
    fun reset(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, false).putInt(KEY_STEP, 0).apply()
    }
}