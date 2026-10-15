package io.github.mydanyi.armadatf.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Small persisted-settings helper for the two user preferences the screen owns:
 * the explicit language choice and the "Show logs" toggle.
 *
 * The language follows the system when the user has never chosen one, so a
 * device whose system language is Chinese opens in Chinese and an English
 * device opens in English. An explicit choice is persisted across launches.
 */
object AppSettings {

    private const val PREFS = "armada_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_SHOW_LOGS = "show_logs"

    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    // --- language ------------------------------------------------------------

    /** The explicitly stored language, or `null` when following the system. */
    fun storedLanguage(context: Context): String? =
        prefs(context).getString(KEY_LANGUAGE, null)

    fun setLanguage(context: Context, language: String?) {
        val editor = prefs(context).edit()
        if (language == null) editor.remove(KEY_LANGUAGE) else editor.putString(KEY_LANGUAGE, language)
        editor.apply()
    }

    /** The effective language: the explicit choice, else the system language. */
    fun effectiveLanguage(context: Context): String {
        storedLanguage(context)?.let { return it }
        val system = systemLocale(context)
        return if (system.language == LANG_ZH) LANG_ZH else LANG_EN
    }

    /** Returns a context whose resources resolve the effective language. */
    fun wrap(base: Context): Context {
        val locale = Locale(effectiveLanguage(base))
        // Override locale/layout direction only, so orientation/size/density keep following the system.
        val configuration = Configuration()
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun systemLocale(context: Context): Locale {
        val configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
    }

    // --- logs ----------------------------------------------------------------

    fun showLogs(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_LOGS, false)

    fun setShowLogs(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_LOGS, show).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
