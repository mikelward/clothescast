package app.clothescast.insight

import android.content.res.Resources
import app.clothescast.R
import app.clothescast.core.domain.model.Garment
import java.util.Locale

/**
 * Joins a list of clothes items into a single comma/and-separated phrase
 * suitable for the "Wear …" sentence and the calendar extras. Article picking
 * is grammar-specific (English needs "a" / "an" only on the first item; German
 * articles depend on grammatical gender; languages with no articles need
 * nothing) so each language plugs in its own [ClothesPhraser]. Unknown locales
 * fall through to [ResourceClothesPhraser], which translates known garment keys
 * via the current locale's `garment_*` string resources and joins with the
 * locale's `insight_clothes_join_*` templates.
 */
internal interface ClothesPhraser {
    /** Article-prefixed form of a single item, used by the calendar extras clause. */
    fun withArticle(item: String): String

    /**
     * Join multiple items into the body of a "Wear …" sentence. When
     * [leadingArticle] is false the first item's article is suppressed (used by
     * the wear-preamble drop, which removes "Wear" *and* the leading article so
     * "a sweater and jacket" becomes "sweater and jacket"); a trailing
     * Oxford-comma article is unaffected. Honoured by the English phraser only —
     * locales without leading articles ignore it.
     */
    fun joinItems(items: List<String>, leadingArticle: Boolean = true): String

    companion object {
        fun forLocale(resources: Resources, locale: Locale): ClothesPhraser =
            when (locale.language) {
                "en" -> EnglishClothesPhraser(resources)
                "de" -> GermanClothesPhraser(resources)
                else -> ResourceClothesPhraser(resources)
            }
    }
}

/**
 * English article picker.
 *  - Stored item keys ("sweater", "pants", "t-shirt") are translated through
 *    the locale's `garment_*` resources first so en-GB / en-AU prose says
 *    "Wear a jumper" / en-GB "Wear trousers" while en-US still says
 *    "Wear a sweater" / "Wear pants". Items not in the [Garment] catalog
 *    (e.g. "umbrella", user-typed extras) pass through unchanged.
 *  - Items ending in 's' are treated as plural (shorts, boots, gloves) and take
 *    no article.
 *  - Items starting with a vowel letter take "an"; everything else takes "a".
 *
 * For the join, "a"/"an" lands on (a) the first item that takes an article,
 * and (b) in an Oxford-comma list of three or more, also on the *last* item
 * if it takes one. Middle items render bare, and plurals never take an
 * article anywhere. Concretely:
 *  - "Wear a sweater."
 *  - "Wear a sweater and jacket." (two items — first only)
 *  - "Wear shorts and a t-shirt." (first article-taking item)
 *  - "Wear a sweater, jacket, and a coat." (three items — first + last)
 *  - "Wear a sweater, jacket, scarf, and gloves." (last is plural, so no
 *    trailing article)
 *  - "Wear shorts, gloves, and a scarf." (first article-taking item is also
 *    the Oxford-last, so just one article)
 *
 * This is the existing two-item "Wear a sweater and jacket" style extended
 * to the Oxford case, so the final singular in a longer list keeps its
 * article rather than reading as a bare noun ("…, and jacket.").
 */
internal class EnglishClothesPhraser(private val resources: Resources) : ClothesPhraser {
    override fun withArticle(item: String): String = prefixArticle(translate(item))

    override fun joinItems(items: List<String>, leadingArticle: Boolean): String {
        val translated = items.asSequence()
            .map(::translate)
            .filter { it.isNotBlank() }
            .toList()
        if (translated.isEmpty()) return ""
        val firstArticleIdx = translated.indexOfFirst(::takesArticle)
        val lastIdx = translated.lastIndex
        val rendered = translated.mapIndexed { i, item ->
            val articleHere = when {
                // Suppressed only when the article is genuinely *leading* —
                // i.e. the first item takes one, so dropping "Wear" leaves
                // "A sweater and jacket" → "Sweater and jacket". A first
                // article-taking item further down the list ("shorts and a
                // t-shirt") isn't adjacent to the removed preamble and keeps
                // its article; the Oxford-comma trailing article likewise
                // still applies.
                i == firstArticleIdx -> leadingArticle || i > 0
                translated.size >= 3 && i == lastIdx && takesArticle(item) -> true
                else -> false
            }
            if (articleHere) prefixArticle(item) else item
        }
        return when (rendered.size) {
            1 -> rendered[0]
            2 -> resources.getString(R.string.insight_clothes_join_two, rendered[0], rendered[1])
            else -> resources.getString(
                R.string.insight_clothes_join_many,
                rendered[0],
                rendered.subList(1, rendered.size - 1).joinToString(", "),
                rendered.last(),
            )
        }
    }

    private fun takesArticle(display: String): Boolean =
        !display.endsWith("s", ignoreCase = true)

    private fun prefixArticle(display: String): String = when {
        display.endsWith("s", ignoreCase = true) -> display
        display.firstOrNull()?.let { it.lowercaseChar() in "aeiou" } == true ->
            resources.getString(R.string.insight_clothes_article_an, display)
        else -> resources.getString(R.string.insight_clothes_article_a, display)
    }

    // Lowercased so the noun reads naturally mid-sentence ("Wear a jumper.")
    // — the `garment_*` resources are title-cased for the dropdown UI, and the
    // article picker also wants to see the *display* string (en-GB "trousers"
    // ends in 's' → bare; en-GB "umbrella" stays vowel-led → "an umbrella").
    private fun translate(item: String): String =
        resources.localizedGarmentLabel(item)?.lowercase(Locale.ENGLISH)
            ?: item.trim().lowercase(Locale.ENGLISH)
}

/**
 * German list join: items are emitted bare because we don't carry grammatical
 * gender on each clothes rule (der/die/das ⇒ einen/eine/ein for "Trag …"), so
 * any guessed article is wrong half the time. Items are joined with commas
 * and a final "und"; no Oxford comma. The calendar extras ("Denk an …")
 * likewise emits the item bare.
 *
 * Items are passed through [translate] first so the default English-keyed
 * `ClothesRule` set ("sweater", "jacket", "shorts", "umbrella") doesn't leak
 * into otherwise-German prose ("Trag sweater und jacket." → "Trag Pullover
 * und Jacke."). Items not in the table fall through unchanged — better to
 * echo the source key than guess wrong.
 *
 * A future PR could thread a gender hint through `ClothesRule` so we can
 * pick a real article — until then, "Trag Pullover, Jacke und Regenschirm."
 * reads naturally enough.
 */
internal class GermanClothesPhraser(private val resources: Resources) : ClothesPhraser {
    override fun withArticle(item: String): String = translate(item)

    // German emits items bare regardless, so [leadingArticle] is a no-op here.
    override fun joinItems(items: List<String>, leadingArticle: Boolean): String {
        val translated = items.asSequence()
            .map(::translate)
            .filter { it.isNotBlank() }
            .toList()
        return when (translated.size) {
            0 -> ""
            1 -> translated[0]
            2 -> resources.getString(R.string.insight_clothes_join_two, translated[0], translated[1])
            else -> resources.getString(
                R.string.insight_clothes_join_many,
                translated[0],
                translated.subList(1, translated.size - 1).joinToString(", "),
                translated.last(),
            )
        }
    }

    /**
     * Maps the most common English clothes / weather-gear keywords to their
     * German equivalents. Lookup is case-insensitive; output preserves German
     * capitalization (German nouns are always capitalised). Anything not in
     * the table falls through unchanged.
     */
    private fun translate(item: String): String =
        EN_TO_DE[item.trim().lowercase(Locale.ROOT)] ?: item.trim()

    private companion object {
        // Defaults shipped in ClothesRule.DEFAULTS plus the obvious extras a user
        // is likely to add (coat, scarf, gloves, hat, boots …). Kept tight rather
        // than exhaustive — adding more is a one-line change. The canonical
        // source key for the cold-weather top is "sweater" (matches the
        // today_outfit_top_sweater label); en-GB / en-AU say "Jumper" only as
        // a display override on that label, never as a stored item key.
        private val EN_TO_DE: Map<String, String> = mapOf(
            "sweater" to "Pullover",
            "hoodie" to "Hoodie",
            "jacket" to "Jacke",
            "thin-jacket" to "leichte Jacke",
            "coat" to "Mantel",
            "puffer" to "Steppjacke",
            "raincoat" to "Regenjacke",
            "rain-jacket" to "Regenjacke",
            "shirt" to "Hemd",
            "t-shirt" to "T-Shirt",
            "tshirt" to "T-Shirt",
            "shorts" to "kurze Hose",
            // Lowercase "kurze" / "lange" because the prose embeds them
            // mid-sentence ("Trag kurze Hose und Pullover."); only the noun
            // "Hose" stays capitalised. The dropdown labels in
            // values-de/strings.xml (garment_*) use sentence-style capitals
            // since they stand alone as a UI label.
            "pants" to "lange Hose",
            "trousers" to "lange Hose",
            "jeans" to "Jeans",
            "polo" to "Polo",
            "skirt" to "Rock",
            "short-skirt" to "Minirock",
            "umbrella" to "Regenschirm",
            "hat" to "Hut",
            "cap" to "Kappe",
            "beanie" to "Mütze",
            "scarf" to "Schal",
            "gloves" to "Handschuhe",
            "mittens" to "Fäustlinge",
            "boots" to "Stiefel",
            "socks" to "Socken",
            "sunglasses" to "Sonnenbrille",
            "sunscreen" to "Sonnencreme",
        )
    }
}

/**
 * Generic phraser for locales with no complex article grammar (Chinese, Hindi,
 * Japanese, Korean, French, Spanish, etc.). Translates known garment keys via
 * the current locale's `garment_*` Android string resources, applies the
 * locale's `insight_clothes_article_a` template for [withArticle] (a no-op
 * passthrough `%1$s` for languages without articles), and joins using the
 * locale's `insight_clothes_join_*` templates.
 *
 * Unknown garment keys fall through unchanged — better to echo the source key
 * than guess wrong. Replaces [BareClothesPhraser] in [ClothesPhraser.forLocale]
 * so all non-en/de locales get translated garment names and locale-correct list
 * punctuation rather than raw English keys comma-joined.
 */
internal class ResourceClothesPhraser(private val resources: Resources) : ClothesPhraser {
    override fun withArticle(item: String): String =
        resources.getString(R.string.insight_clothes_article_a, translate(item))

    // Generic locales have no leading-article rule, so [leadingArticle] is a no-op.
    override fun joinItems(items: List<String>, leadingArticle: Boolean): String {
        val translated = items.asSequence()
            .map(::translate)
            .filter { it.isNotBlank() }
            .toList()
        return when (translated.size) {
            0 -> ""
            1 -> translated[0]
            2 -> resources.getString(R.string.insight_clothes_join_two, translated[0], translated[1])
            else -> resources.getString(
                R.string.insight_clothes_join_many,
                translated[0],
                translated.subList(1, translated.size - 1).joinToString(", "),
                translated.last(),
            )
        }
    }

    private fun translate(item: String): String =
        resources.localizedGarmentLabel(item)?.lowercase(resources.currentLocale()) ?: item.trim()
}

/**
 * Bare comma-join with no translation. Used in tests; not returned from
 * [ClothesPhraser.forLocale] — [ResourceClothesPhraser] handles the generic
 * case there.
 */
internal class BareClothesPhraser : ClothesPhraser {
    override fun withArticle(item: String): String = item

    override fun joinItems(items: List<String>, leadingArticle: Boolean): String = items.joinToString(", ")
}

private val GARMENT_RES_IDS: Map<Garment, Int> = mapOf(
    Garment.SWEATER to R.string.garment_sweater,
    Garment.HOODIE to R.string.garment_hoodie,
    Garment.JACKET to R.string.garment_jacket,
    Garment.COAT to R.string.garment_coat,
    Garment.PUFFER to R.string.garment_puffer,
    Garment.THIN_JACKET to R.string.garment_thin_jacket,
    Garment.TSHIRT to R.string.garment_tshirt,
    Garment.POLO to R.string.garment_polo,
    Garment.SHIRT to R.string.garment_shirt,
    Garment.SHORTS to R.string.garment_shorts,
    Garment.SHORT_SKIRT to R.string.garment_short_skirt,
    Garment.SKIRT to R.string.garment_skirt,
    Garment.PANTS to R.string.garment_pants,
    Garment.JEANS to R.string.garment_jeans,
    Garment.GLOVES to R.string.garment_gloves,
    Garment.UMBRELLA to R.string.garment_umbrella,
    Garment.RAIN_JACKET to R.string.garment_rain_jacket,
)

/**
 * Look up the locale-specific display label for a stored garment key
 * (e.g. "sweater" → "Sweater" / "Jumper" / "Pullover" depending on resources).
 * Returns null when the key isn't in the catalog (caller should fall back to
 * the raw item — this preserves user-typed extras like "umbrella" or "boots").
 */
internal fun Resources.localizedGarmentLabel(item: String): String? {
    val garment = Garment.fromKey(item) ?: return null
    val resId = GARMENT_RES_IDS[garment] ?: return null
    return getString(resId)
}

private fun Resources.currentLocale(): Locale {
    val locales = configuration.locales
    if (!locales.isEmpty) return locales[0]
    @Suppress("DEPRECATION")
    return configuration.locale
}
