package com.mushussubabylon.gitdropuploader

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language of the app: the phone's language by default, or any of the ten languages GitDrop is
 * translated into, picked on the first screen. The pick is the only thing GitDrop keeps on the phone.
 */
object LanguageSettings {

    class Language(val code: String, val nativeName: String)

    /**
     * Every language written in its own script, so it can be found whatever language is shown now.
     * Each code except "en" needs a matching res/values-<code> folder (English is the default resources).
     */
    val all: List<Language> = listOf(
        Language("en", "English"),
        Language("ar", "العربية"),
        Language("fa", "فارسی"),
        Language("ru", "Русский"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("de", "Deutsch"),
        Language("tr", "Türkçe"),
        Language("zh", "中文"),
        Language("pt", "Português")
    )

    private const val PREFS = "gitdrop_settings"
    private const val KEY_LANGUAGE = "language"

    /** The language the person picked, or null while the app follows the phone's language. */
    fun saved(context: Context): String? {
        val code = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        } catch (e: Exception) {
            null
        }
        return if (code != null && all.any { it.code == code }) code else null
    }

    /** Remembers the pick; null goes back to following the phone's language. */
    fun save(context: Context, code: String?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (code == null) edit.remove(KEY_LANGUAGE) else edit.putString(KEY_LANGUAGE, code)
        edit.apply()
    }

    /**
     * [base] with the picked language (and its reading direction) applied, for an activity's
     * attachBaseContext. Unchanged while the app follows the phone's language.
     */
    fun wrap(base: Context): Context {
        val code = saved(base) ?: return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** The language the app is showing now. English when the phone's language has no translation. */
    fun current(context: Context): Language {
        val locales = context.resources.configuration.locales
        val lang = if (locales.isEmpty) Locale.getDefault().language else locales.get(0).language
        return all.firstOrNull { it.code == lang } ?: all[0]
    }
}
