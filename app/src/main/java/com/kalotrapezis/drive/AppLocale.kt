package com.kalotrapezis.drive

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Tetra's language (Settings › Appearance › Language; the user, 25 Oct): Android's per-app language, so Android's own
 * App languages page shows the same choice. A language is one file, res/values-<tag>/strings.xml; the build lists them in
 * [BuildConfig.LANGUAGES]. "" is the system's language.
 */
internal object AppLocale {
    val tags: List<String> = BuildConfig.LANGUAGES.split(',')

    fun get(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.let { if (it.isEmpty) "" else it[0].toLanguageTag() }
        else prefs(context).getString("tag", "").orEmpty()

    /** Set when the language changes: Tetra's screens start again in it, and open on Settings, where it was chosen. */
    var reopenSettings = false

    /** Android 13+ restarts Tetra's screens in the new language by itself. */
    fun set(context: Context, tag: String) {
        reopenSettings = true
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        else { prefs(context).edit().putString("tag", tag).apply(); (context as? Activity)?.recreate() }
    }

    /** A language in its own words ("Ελληνικά"). */
    fun name(tag: String): String = Locale.forLanguageTag(tag).let { l -> l.getDisplayName(l).replaceFirstChar { it.titlecase(l) } }

    /** What System default gives: the device's language when Tetra has it, else English. */
    fun systemTag(): String = android.content.res.Resources.getSystem().configuration.locales[0].language.let { if (it in tags) it else "en" }

    /** Before Android 13, each activity's context in the chosen language (its attachBaseContext). */
    // shortcut: before Android 13 notifications and the car's screens stay in the system's language; fine while the devices are 13+.
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = prefs(base).getString("tag", "").orEmpty().ifEmpty { return base }
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }

    private fun prefs(c: Context) = c.getSharedPreferences("language", Context.MODE_PRIVATE)
}
