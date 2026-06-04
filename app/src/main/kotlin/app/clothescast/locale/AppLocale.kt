package app.clothescast.locale

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import app.clothescast.core.domain.model.Region
import java.util.Locale

/**
 * Single source of truth for "what language is the app rendering in?"
 *
 * The user's [Region] setting carries a BCP-47 tag (e.g. `de-AT`); this helper
 * pushes that locale into the three places it actually matters:
 *  - [Locale.setDefault] — for code paths that ask for the default locale
 *    (date / number formatting, TTS resolution, library defaults).
 *  - [LocaleManager.setApplicationLocales] on API 33+ — Android persists this
 *    across launches and applies it to all Activity / Application Resources,
 *    so the entire UI re-renders in the chosen language without us having to
 *    wrap each Context.
 *  - SharedPreferences cache + [Configuration.setLocale] override applied via
 *    [attachBaseContext] on API ≤32 — Android predates per-app language
 *    preferences, so we wrap each Context with a Configuration whose locale
 *    is the persisted choice and recreate Activities when it changes.
 *
 * Without this, the user's Region choice only reaches the InsightFormatter
 * (via `forRegion`'s explicit `createConfigurationContext`) and the TTS voice
 * picker — the rest of the UI keeps rendering in the device's default locale,
 * and on Android 13+ even the InsightFormatter path has been observed to fall
 * back to the device locale once `android:localeConfig` is declared (yielding
 * the "Today will be cold to mild. Wear Pullover and Jacke." mixed output).
 *
 * [Region.SYSTEM] (`bcp47 == null`) clears the override so device locale wins.
 */
object AppLocale {
    private const val PREFS_NAME = "app_locale"
    private const val KEY_TAG = "tag"

    /** Persist [region]'s locale and apply it process-wide. */
    fun apply(context: Context, region: Region) {
        val tag = region.bcp47
        if (tag == null) {
            clear(context)
            return
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList(locale)
        } else {
            context.prefs().edit { putString(KEY_TAG, tag) }
        }
    }

    /** Drop the per-app override so the device locale takes effect. */
    fun clear(context: Context) {
        // Reset the JVM default to the device locale; otherwise switching from
        // a non-system region back to SYSTEM in the same process leaves
        // Locale.getDefault() stuck on the previous app locale (formatting,
        // TTS fallbacks, etc. would keep behaving as the old region until
        // process restart).
        Locale.setDefault(systemLocale(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.getEmptyLocaleList()
        } else {
            context.prefs().edit { remove(KEY_TAG) }
        }
    }

    /**
     * The device-level locale, ignoring any per-app override we've installed.
     * On API 33+, [LocaleManager.getSystemLocales] is used because the OS
     * applies per-app overrides at the process level, which causes
     * [Resources.getSystem] to reflect the app locale rather than the device
     * locale on those versions.
     */
    private fun systemLocale(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.systemLocales
            if (locales != null && !locales.isEmpty) return locales[0]
        }
        val locales = Resources.getSystem().configuration.locales
        return if (!locales.isEmpty) locales[0] else Locale.ROOT
    }

    /**
     * Wrap [base] with a Configuration whose locale is the persisted choice,
     * for use from `attachBaseContext` overrides. Returns [base] unchanged on
     * API 33+ (the system already routes the right locale through
     * [Context.createConfigurationContext]) and when no override has been
     * stored yet.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.prefs().getString(KEY_TAG, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    private fun Context.prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
