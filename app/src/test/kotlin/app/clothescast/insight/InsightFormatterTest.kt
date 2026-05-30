package app.clothescast.insight

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.CalendarTieInClause
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.RainAccessory
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WeekAheadClause
import app.clothescast.core.domain.model.WeekAheadInsight
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Verifies InsightFormatter still emits the same English prose after string
 * extraction. Runs under Robolectric so the formatter can resolve real
 * `R.string.*` resources from the bundled strings.xml — we want the test to
 * fail if a translator (or a future refactor) silently changes the English
 * template, not just compare against a hand-rolled copy that would drift.
 */
@RunWith(AndroidJUnit4::class)
class InsightFormatterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val subject = InsightFormatter.forContext(context, Locale.ENGLISH)

    // Card / visual surface: the Speech-only period preamble drops the lead on
    // the default VISUAL surface — the old `includeLead = false` behaviour.
    private val cardSubject =
        InsightFormatter.forContext(context, Locale.ENGLISH, periodPreamble = PreambleVisibility.SPEECH_ONLY)

    private fun summary(
        period: ForecastPeriod = ForecastPeriod.TODAY,
        band: BandClause = BandClause(TemperatureBand.MILD, TemperatureBand.MILD),
        alert: AlertClause? = null,
        delta: DeltaClause? = null,
        clothes: ClothesClause? = null,
        precip: PrecipClause? = null,
        calendarTieIn: CalendarTieInClause? = null,
        eveningEventTieIn: EveningEventTieInClause? = null,
        // Mirrors RenderInsightSummary: carried accessories ride independently of
        // the wear clause. Defaults to the accessories in [clothes] so existing
        // umbrella-in-clothes cases work; override to exercise the gating-
        // independent path (clothes == null but the umbrella still surfaces).
        carriedAccessories: List<String> = clothes?.items.orEmpty().filter { Garment.isAccessoryKey(it) },
    ) = InsightSummary(
        period, band, alert, delta, clothes, precip, calendarTieIn, eveningEventTieIn, carriedAccessories,
    )

    @Test
    fun `band-only insight emits the lead-in and single feels-like temp`() {
        // BandClause(MILD, MILD) defaults both temps to MILD's midpoint (21°C)
        // so the rendered prose lands on "Today, it will be 21°."
        subject.format(summary()) shouldBe "Today, it will be 21°."
    }

    @Test
    fun `band emits a low-to-high range when min and max round to different ints`() {
        subject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD, 14.0, 20.0)),
        ) shouldBe "Today, it will be 14° to 20°."
    }

    @Test
    fun `band collapses to single form when rounded extremes land on the same int`() {
        // 14.2 and 14.4 both round to 14 — the formatter folds them into the
        // single-value template rather than emitting "14° to 14°".
        subject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.COOL, 14.2, 14.4)),
        ) shouldBe "Today, it will be 14°."
    }

    @Test
    fun `tonight period switches the lead-in`() {
        subject.format(summary(period = ForecastPeriod.TONIGHT)) shouldBe "Tonight, it will be 21°."
    }

    @Test
    fun `isFutureDay swaps the today lead-in for tomorrow`() {
        subject.format(summary(), isFutureDay = true) shouldBe "Tomorrow, it will be 21°."
    }

    @Test
    fun `isFutureDay leaves the tonight lead-in alone`() {
        // The evening worker only ever pairs a primary insight with tomorrow's
        // daytime, so a TONIGHT payload with isFutureDay=true isn't a real
        // path — but if it ever arrived we'd rather say "Tonight" than
        // accidentally promote it to tomorrow.
        subject.format(summary(period = ForecastPeriod.TONIGHT), isFutureDay = true) shouldBe
            "Tonight, it will be 21°."
    }

    @Test
    fun `visual surface drops the lead-in and opens on the single temp`() {
        // The Today card carries its own "Today" header, so the card prose
        // omits the redundant "Today, it will be …" lead.
        cardSubject.format(summary()) shouldBe "21°."
    }

    @Test
    fun `visual surface drops the lead-in on a range`() {
        cardSubject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD, 14.0, 20.0)),
        ) shouldBe "14° to 20°."
    }

    @Test
    fun `visual surface keeps following clauses after the bare temp`() {
        cardSubject.format(
            summary(
                clothes = ClothesClause(listOf("sweater")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        ) shouldBe "21°. Wear a sweater. Rain at 3pm."
    }

    @Test
    fun `visual surface drops the tonight lead-in too`() {
        cardSubject.format(summary(period = ForecastPeriod.TONIGHT)) shouldBe "21°."
    }

    @Test
    fun `speech surface keeps the lead even under Speech-only`() {
        // The spoken briefing has no period header to lean on, so Speech-only
        // keeps the "Today, it will be …" lead on the SPEECH surface.
        cardSubject.format(summary(), surface = InsightSurface.SPEECH) shouldBe "Today, it will be 21°."
    }

    @Test
    fun `visual surface drops the lead-in for non-English locales too`() {
        // Every locale now ships insight_*_no_lead templates, so the no-lead
        // surgery applies across languages: the German visual surface drops
        // "Heute wird es …" down to the bare measurement, while the speech
        // surface keeps the full lead-in.
        val germanSubject =
            InsightFormatter.forContext(context, Locale.GERMAN, periodPreamble = PreambleVisibility.SPEECH_ONLY)
        germanSubject.format(summary(), surface = InsightSurface.VISUAL) shouldBe "21°."
        germanSubject.format(summary(), surface = InsightSurface.SPEECH) shouldBe "Heute wird es 21°."
    }

    // --- Period preamble (PreambleVisibility) ---

    @Test
    fun `period preamble Always keeps the lead on the visual surface`() {
        val always =
            InsightFormatter.forContext(context, Locale.ENGLISH, periodPreamble = PreambleVisibility.ALWAYS)
        always.format(summary(), surface = InsightSurface.VISUAL) shouldBe "Today, it will be 21°."
    }

    @Test
    fun `period preamble Never drops the lead even on the speech surface`() {
        val never =
            InsightFormatter.forContext(context, Locale.ENGLISH, periodPreamble = PreambleVisibility.NEVER)
        never.format(summary(), surface = InsightSurface.SPEECH) shouldBe "21°."
    }

    @Test
    fun `period preamble Speech-only parenthesises the lead on the settings preview`() {
        cardSubject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD, 14.0, 20.0)),
            surface = InsightSurface.SETTINGS_PREVIEW,
        ) shouldBe "(Today, it will be) 14° to 20°."
    }

    @Test
    fun `period preamble preview parens carry over following clauses`() {
        cardSubject.format(
            summary(clothes = ClothesClause(listOf("sweater"))),
            surface = InsightSurface.SETTINGS_PREVIEW,
        ) shouldBe "(Today, it will be) 21°. Wear a sweater."
    }

    // --- Wear preamble (PreambleVisibility) ---

    @Test
    fun `wear preamble Never drops Wear and the leading article`() {
        val wearNever =
            InsightFormatter.forContext(context, Locale.ENGLISH, wearPreamble = PreambleVisibility.NEVER)
        wearNever.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today, it will be 21°. Sweater."
    }

    @Test
    fun `wear preamble Never keeps only the leading article off a multi-item list`() {
        val wearNever =
            InsightFormatter.forContext(context, Locale.ENGLISH, wearPreamble = PreambleVisibility.NEVER)
        wearNever.format(summary(clothes = ClothesClause(listOf("sweater", "jacket", "coat")))) shouldBe
            "Today, it will be 21°. Sweater, jacket, and a coat."
    }

    @Test
    fun `wear preamble Never on a plural-first item has no article to drop`() {
        val wearNever =
            InsightFormatter.forContext(context, Locale.ENGLISH, wearPreamble = PreambleVisibility.NEVER)
        wearNever.format(summary(clothes = ClothesClause(listOf("shorts")))) shouldBe
            "Today, it will be 21°. Shorts."
    }

    @Test
    fun `wear preamble Speech-only drops Wear on the visual surface`() {
        val wearSpeechOnly =
            InsightFormatter.forContext(context, Locale.ENGLISH, wearPreamble = PreambleVisibility.SPEECH_ONLY)
        wearSpeechOnly.format(
            summary(clothes = ClothesClause(listOf("sweater"))),
            surface = InsightSurface.VISUAL,
        ) shouldBe "Today, it will be 21°. Sweater."
    }

    @Test
    fun `wear preamble Speech-only keeps Wear on the speech surface`() {
        val wearSpeechOnly =
            InsightFormatter.forContext(context, Locale.ENGLISH, wearPreamble = PreambleVisibility.SPEECH_ONLY)
        wearSpeechOnly.format(
            summary(clothes = ClothesClause(listOf("sweater"))),
            surface = InsightSurface.SPEECH,
        ) shouldBe "Today, it will be 21°. Wear a sweater."
    }

    @Test
    fun `wear preamble Speech-only parenthesises Wear plus article on the preview`() {
        val wearSpeechOnly =
            InsightFormatter.forContext(context, Locale.ENGLISH, wearPreamble = PreambleVisibility.SPEECH_ONLY)
        wearSpeechOnly.format(
            summary(clothes = ClothesClause(listOf("sweater"))),
            surface = InsightSurface.SETTINGS_PREVIEW,
        ) shouldBe "Today, it will be 21°. (Wear a) sweater."
    }

    @Test
    fun `wear preamble drop applies to non-English locales too`() {
        // insight_clothes_bare ("%1$s.") ships in every locale, so dropping
        // "Wear" works in German: "Trag Pullover." collapses to "Pullover."
        // (the band lead stays — only the wear preamble is set to Never here).
        val germanWearNever = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            wearPreamble = PreambleVisibility.NEVER,
        )
        germanWearNever.format(
            summary(clothes = ClothesClause(listOf("sweater"))),
            surface = InsightSurface.VISUAL,
        ) shouldBe "Heute wird es 21°. Pullover."
    }

    @Test
    fun `delta clause emits warmer with rounded degrees`() {
        val out = subject.format(summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER)))
        out shouldBe "Today, it will be 21°. 5° warmer than yesterday."
    }

    @Test
    fun `delta clause emits cooler`() {
        val out = subject.format(summary(delta = DeltaClause(6, DeltaClause.Direction.COOLER)))
        out shouldBe "Today, it will be 21°. 6° cooler than yesterday."
    }

    @Test
    fun `band-style delta replaces the temperature sentence with today's band`() {
        // The degree range (21°) is gone — the band callout IS the temperature
        // sentence, with no extra "it will be" clause appended.
        val out = subject.format(
            summary(
                delta = DeltaClause(
                    degrees = 4,
                    direction = DeltaClause.Direction.WARMER,
                    band = TemperatureBand.HOT,
                    style = DeltaClause.Style.BANDS,
                ),
            ),
        )
        out shouldBe "Today, it will be hot."
    }

    @Test
    fun `band-style delta names a cooler band the same way`() {
        val out = subject.format(
            summary(
                delta = DeltaClause(
                    degrees = 5,
                    direction = DeltaClause.Direction.COOLER,
                    band = TemperatureBand.COOL,
                    style = DeltaClause.Style.BANDS,
                ),
            ),
        )
        out shouldBe "Today, it will be cool."
    }

    @Test
    fun `band-style delta names the band word regardless of fahrenheit — a band is a band`() {
        // No degrees in the output, so the unit setting doesn't matter: the
        // callout reads the same in °F as in °C.
        val fahrenheitSubject = InsightFormatter.forContext(
            context = context,
            locale = Locale.ENGLISH,
            temperatureUnit = app.clothescast.core.domain.model.TemperatureUnit.FAHRENHEIT,
        )
        fahrenheitSubject.format(
            summary(
                band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL, 8.0, 14.0),
                delta = DeltaClause(
                    degrees = 5,
                    direction = DeltaClause.Direction.WARMER,
                    band = TemperatureBand.MILD,
                    style = DeltaClause.Style.BANDS,
                ),
            ),
        ) shouldBe "Today, it will be mild."
    }

    @Test
    fun `band-style delta drops the lead on the card surface`() {
        // Period preamble off (the Today card): just the capitalised band word,
        // no "it will be".
        cardSubject.format(
            summary(
                delta = DeltaClause(
                    degrees = 4,
                    direction = DeltaClause.Direction.WARMER,
                    band = TemperatureBand.HOT,
                    style = DeltaClause.Style.BANDS,
                ),
            ),
        ) shouldBe "Hot."
    }

    @Test
    fun `formatter renders fahrenheit when the user's unit is FAHRENHEIT`() {
        val fahrenheitSubject = InsightFormatter.forContext(
            context = context,
            locale = Locale.ENGLISH,
            temperatureUnit = app.clothescast.core.domain.model.TemperatureUnit.FAHRENHEIT,
        )
        // 8°C → 46.4°F → rounds to 46; 14°C → 57.2°F → rounds to 57.
        fahrenheitSubject.format(
            summary(band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL, 8.0, 14.0)),
        ) shouldBe "Today, it will be 46° to 57°."
    }

    @Test
    fun `fahrenheit delta is the Celsius value times 9 over 5 — no mixed units in one briefing`() {
        // Regression for a mixed-unit briefing: with the band rendered in
        // Fahrenheit, the delta clause used to ship the Celsius degree count
        // beside it ("46° to 57°. 5° warmer than yesterday.") — both numbers
        // are degrees, so the listener couldn't tell they meant different
        // things. The formatter now converts the delta on the same axis.
        val fahrenheitSubject = InsightFormatter.forContext(
            context = context,
            locale = Locale.ENGLISH,
            temperatureUnit = app.clothescast.core.domain.model.TemperatureUnit.FAHRENHEIT,
        )
        // 5°C of warming = 9°F (ratio only, no +32 offset on a delta).
        fahrenheitSubject.format(
            summary(
                band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL, 8.0, 14.0),
                delta = DeltaClause(5, DeltaClause.Direction.WARMER),
            ),
        ) shouldBe "Today, it will be 46° to 57°. 9° warmer than yesterday."
    }

    @Test
    fun `clothes with a single article-able item emits 'a sweater'`() {
        subject.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today, it will be 21°. Wear a sweater."
    }

    @Test
    fun `clothes containing only an umbrella renders no clothes sentence`() {
        // Accessories (umbrella) are filtered out of the rendered prose
        // entirely until the accessory catalog lands. The rule still fired
        // (and a precip clause would still warn about rain elsewhere), but
        // there's no garment to "Wear" so the clothes sentence drops out.
        subject.format(summary(clothes = ClothesClause(listOf("umbrella")))) shouldBe
            "Today, it will be 21°."
    }

    @Test
    fun `clothes normalizes capitalized english items mid-sentence`() {
        subject.format(summary(clothes = ClothesClause(listOf("Sweater", "Jacket")))) shouldBe
            "Today, it will be 21°. Wear a sweater and jacket."
    }

    @Test
    fun `clothes drops the article on plural-looking items`() {
        subject.format(summary(clothes = ClothesClause(listOf("shorts")))) shouldBe
            "Today, it will be 21°. Wear shorts."
    }

    @Test
    fun `clothes joins two items with 'and' and only the first article-taking item gets an article`() {
        subject.format(summary(clothes = ClothesClause(listOf("sweater", "jacket")))) shouldBe
            "Today, it will be 21°. Wear a sweater and jacket."
    }

    @Test
    fun `clothes articles the first article-taking item when a plural comes first`() {
        // Drives the warm-day shorts-rule + t-shirt-fallback case: the older
        // "first item only" rule swallowed the article on "t-shirt" (it
        // wasn't first) and read as "Wear shorts and t-shirt."; the article
        // now lands on the first item that can take one.
        subject.format(summary(clothes = ClothesClause(listOf("shorts", "t-shirt")))) shouldBe
            "Today, it will be 21°. Wear shorts and a t-shirt."
    }

    @Test
    fun `clothes Oxford-joins three garments articling the first and Oxford-last`() {
        // Three-or-more lists also article the last item (if it takes one),
        // so the Oxford-last reads as a noun ("…and a coat.") rather than a
        // bare fragment ("…and coat.").
        subject.format(summary(clothes = ClothesClause(listOf("sweater", "jacket", "coat")))) shouldBe
            "Today, it will be 21°. Wear a sweater, jacket, and a coat."
    }

    @Test
    fun `clothes Oxford-joins four garments`() {
        subject.format(
            summary(clothes = ClothesClause(listOf("sweater", "jacket", "shorts", "coat"))),
        ) shouldBe "Today, it will be 21°. Wear a sweater, jacket, shorts, and a coat."
    }

    @Test
    fun `clothes Oxford-last plural emits no trailing article`() {
        // Last item is plural so the Oxford-last position picks nothing —
        // we don't reach into the middle for a singular to article.
        subject.format(
            summary(clothes = ClothesClause(listOf("sweater", "jacket", "scarf", "gloves"))),
        ) shouldBe "Today, it will be 21°. Wear a sweater, jacket, scarf, and gloves."
    }

    @Test
    fun `clothes with garments and umbrella keeps only the garments`() {
        // Umbrella is silenced; the wear sentence carries the surviving
        // garments.
        subject.format(summary(clothes = ClothesClause(listOf("sweater", "jacket", "umbrella")))) shouldBe
            "Today, it will be 21°. Wear a sweater and jacket."
    }

    // ClothesFormat.LAYER_COUNT collapses the firing tops to a perceived-warmth
    // count (see Garment.layerCount) — the heaviest single top defines the
    // count, not the sum. Bottoms are suppressed in this mode (see
    // InsightFormatter.format) so the wear clause is a single warmth signal;
    // an only-bottom firing therefore emits no wear clause at all.
    private val layerCountSubject = InsightFormatter.forContext(
        context = context,
        locale = Locale.ENGLISH,
        clothesFormat = ClothesFormat.LAYER_COUNT,
    )

    @Test
    fun `layer-count format renders a t-shirt as 1 layer (singular)`() {
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("t-shirt")))) shouldBe
            "Today, it will be 21°. Wear 1 layer."
    }

    @Test
    fun `layer-count format renders a sweater as 2 layers`() {
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today, it will be 21°. Wear 2 layers."
    }

    @Test
    fun `layer-count format renders a puffer as 3 layers`() {
        // A puffer is a warm shell, not a literal fourth layer — layer-count mode
        // tops out at 3. "Extra cold" is signalled by gloves, not a higher count.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("puffer")))) shouldBe
            "Today, it will be 21°. Wear 3 layers."
    }

    @Test
    fun `layer-count format appends gloves after the layer count`() {
        // Gloves are the "extra cold" escalation past a full layer stack: they
        // ride along after the count rather than inflating it.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("coat", "gloves")))) shouldBe
            "Today, it will be 21°. Wear 3 layers and gloves."
    }

    @Test
    fun `layer-count format takes max across stacked tops, not the sum`() {
        // sweater (2) under a jacket (3) is 3, not 5 — the heavier garment
        // defines the warmth tier in this mental model.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("sweater", "jacket")))) shouldBe
            "Today, it will be 21°. Wear 3 layers."
    }

    @Test
    fun `layer-count format suppresses bottoms alongside the layer count`() {
        // The trailing "and shorts" adds nothing on top of the warmth count, so
        // layer-count mode drops bottoms entirely — leaving just the count.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("sweater", "shorts")))) shouldBe
            "Today, it will be 21°. Wear 2 layers."
    }

    @Test
    fun `layer-count format suppresses bottoms on warm-day t-shirt plus shorts`() {
        // The warm-day fallback scenario in layer-count mode: t-shirt from
        // the default top, shorts from the user's threshold rule — bottoms
        // are filtered before the wear clause runs.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("t-shirt", "shorts")))) shouldBe
            "Today, it will be 21°. Wear 1 layer."
    }

    @Test
    fun `layer-count format drops the wear clause when only a bottom fires`() {
        // No top means no warmth count to render, and bottoms are suppressed
        // in this mode — so the whole wear sentence disappears rather than
        // falling through to the items rendering ("Wear shorts.").
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("shorts")))) shouldBe
            "Today, it will be 21°."
    }

    @Test
    fun `layer-count format ignores umbrellas the same way items mode does`() {
        // Umbrellas are accessories — filtered out before formatClothesWear
        // runs in both modes.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("sweater", "umbrella")))) shouldBe
            "Today, it will be 21°. Wear 2 layers."
    }

    @Test
    fun `layer-count format falls back to items mode for unrecognised garments`() {
        // A user-typed item that isn't in the Garment catalog has no
        // layerCount; rather than mis-render, fall through to the items
        // phrasing for the whole list so the user still hears something.
        layerCountSubject.format(summary(clothes = ClothesClause(listOf("cape")))) shouldBe
            "Today, it will be 21°. Wear a cape."
    }

    // BottomsFormat covers three states orthogonal to ClothesFormat: ALWAYS,
    // IF_GARMENTS (default — bottoms appear in items mode and are dropped in
    // layer-count mode), and NEVER. The two subjects below mirror the default
    // and layer-count subjects with explicit BottomsFormat overrides.
    private val neverBottomsSubject = InsightFormatter.forContext(
        context = context,
        locale = Locale.ENGLISH,
        bottomsFormat = BottomsFormat.NEVER,
    )
    private val alwaysBottomsLayerCountSubject = InsightFormatter.forContext(
        context = context,
        locale = Locale.ENGLISH,
        clothesFormat = ClothesFormat.LAYER_COUNT,
        bottomsFormat = BottomsFormat.ALWAYS,
    )

    @Test
    fun `bottoms format NEVER strips bottoms from items-mode wear clause`() {
        // The classic warm-day stack (default top + shorts rule) becomes a
        // top-only wear clause when the user opts out of bottoms.
        neverBottomsSubject.format(summary(clothes = ClothesClause(listOf("sweater", "shorts")))) shouldBe
            "Today, it will be 21°. Wear a sweater."
    }

    @Test
    fun `bottoms format NEVER drops the wear clause when only a bottom fires`() {
        // No top survives the filter, so the whole wear sentence disappears
        // — the user explicitly asked never to be told what bottoms to wear.
        neverBottomsSubject.format(summary(clothes = ClothesClause(listOf("shorts")))) shouldBe
            "Today, it will be 21°."
    }

    @Test
    fun `bottoms format NEVER keeps unrecognised items as tops`() {
        // ClothesClause.tops treats unknown items as tops, so a user-typed
        // "cape" is preserved by the NEVER filter.
        neverBottomsSubject.format(summary(clothes = ClothesClause(listOf("cape", "shorts")))) shouldBe
            "Today, it will be 21°. Wear a cape."
    }

    @Test
    fun `bottoms format ALWAYS appends bottoms to the layer-count clause`() {
        // ALWAYS overrides layer-count's historical bottoms-suppression so
        // the user hears their shorts named alongside the warmth signal.
        alwaysBottomsLayerCountSubject.format(summary(clothes = ClothesClause(listOf("sweater", "shorts")))) shouldBe
            "Today, it will be 21°. Wear 2 layers and shorts."
    }

    @Test
    fun `bottoms format ALWAYS does not insert article into singular layer count`() {
        // Regression: "1 layer" + "shorts" used to flow through the
        // English phraser's article logic as if "1 layer" were a noun, so
        // takesArticle returned true (no trailing 's') and the output
        // became "Wear a 1 layer and shorts." Join via the localized
        // template directly so the count phrase keeps its plural form
        // untouched.
        alwaysBottomsLayerCountSubject.format(summary(clothes = ClothesClause(listOf("t-shirt", "shorts")))) shouldBe
            "Today, it will be 21°. Wear 1 layer and shorts."
    }

    @Test
    fun `bottoms format ALWAYS still emits bare count when no bottom fires`() {
        // No bottom in the rule list means the appended bottoms list is empty
        // and the clause reads as the bare layer count, same as IF_GARMENTS.
        alwaysBottomsLayerCountSubject.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today, it will be 21°. Wear 2 layers."
    }

    @Test
    fun `precip clause emits with spoken peak hour and capitalised type`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)))) shouldBe
            "Today, it will be 21°. Rain at 3pm."
    }

    @Test
    fun `precip clause says 'noon' for 12-00`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.DRIZZLE, LocalTime.NOON))) shouldBe
            "Today, it will be 21°. Drizzle at noon."
    }

    @Test
    fun `precip clause says 'overnight' for early-morning peak`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(2, 0)))) shouldBe
            "Today, it will be 21°. Rain overnight."
    }

    @Test
    fun `precip clause says 'overnight' for midnight peak`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.SNOW, LocalTime.MIDNIGHT))) shouldBe
            "Today, it will be 21°. Snow overnight."
    }

    @Test
    fun `precip clause uses 12-hour pm form for late-evening peak`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(23, 0)))) shouldBe
            "Today, it will be 21°. Rain at 11pm."
    }

    @Test
    fun `precip clause renders non-zero minutes`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 30)))) shouldBe
            "Today, it will be 21°. Rain at 3:30pm."
    }

    @Test
    fun `precip clause hedges with 'Chance of rain' when likelihood is POSSIBLE`() {
        subject.format(
            summary(
                precip = PrecipClause(
                    WeatherCondition.RAIN,
                    LocalTime.of(15, 0),
                    PrecipLikelihood.POSSIBLE,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Chance of rain at 3pm."
    }

    @Test
    fun `precip clause downcases the condition mid-sentence in the hedged form`() {
        // Drizzle would otherwise read "Chance of Drizzle at noon." — title-
        // cased mid-sentence. The formatter lowercases the condition for the
        // POSSIBLE template so it sits naturally after "Chance of".
        subject.format(
            summary(
                precip = PrecipClause(
                    WeatherCondition.DRIZZLE,
                    LocalTime.NOON,
                    PrecipLikelihood.POSSIBLE,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Chance of drizzle at noon."
    }

    @Test
    fun `alert clause emits before the band`() {
        val out = subject.format(summary(alert = AlertClause("Tornado Warning")))
        out shouldBe "Alert: Tornado Warning. Today, it will be 21°."
    }

    private val omitSubject = InsightFormatter.forContext(
        context,
        Locale.ENGLISH,
        rangeFormat = RangeFormat.NONE,
    )

    // Speech-only period preamble: the default VISUAL surface drops the lead.
    private val omitCardSubject = InsightFormatter.forContext(
        context,
        Locale.ENGLISH,
        rangeFormat = RangeFormat.NONE,
        periodPreamble = PreambleVisibility.SPEECH_ONLY,
    )

    private val bandsSubject = InsightFormatter.forContext(
        context,
        Locale.ENGLISH,
        rangeFormat = RangeFormat.BANDS,
    )

    private val bandsCardSubject = InsightFormatter.forContext(
        context,
        Locale.ENGLISH,
        rangeFormat = RangeFormat.BANDS,
        periodPreamble = PreambleVisibility.SPEECH_ONLY,
    )

    @Test
    fun `bands format renders a low-to-high band-word range`() {
        bandsSubject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD, 14.0, 20.0)),
        ) shouldBe "Today, it will be cool to mild."
    }

    @Test
    fun `bands format collapses to a single band word when low and high match`() {
        bandsSubject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.COOL, 14.0, 16.0)),
        ) shouldBe "Today, it will be cool."
    }

    @Test
    fun `bands format on the visual surface capitalises the leading band word`() {
        bandsCardSubject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD, 14.0, 20.0)),
        ) shouldBe "Cool to mild."
    }

    @Test
    fun `bands format on the visual surface on a single band word`() {
        bandsCardSubject.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.COOL, 14.0, 16.0)),
        ) shouldBe "Cool."
    }

    @Test
    fun `omit range on the visual surface capitalises the first clause and skips the lead`() {
        omitCardSubject.format(
            summary(
                clothes = ClothesClause(listOf("sweater")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        ) shouldBe "Wear a sweater. Rain at 3pm."
    }

    @Test
    fun `omit range on the visual surface gives the leading delta a capitalised it-will-be`() {
        omitCardSubject.format(
            summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER)),
        ) shouldBe "It will be 5° warmer than yesterday."
    }

    @Test
    fun `omit range on the visual surface on the unchanged line drops the lead`() {
        omitCardSubject.format(summary()) shouldBe
            "It will be the same as yesterday."
    }

    @Test
    fun `omit range folds the lead into the clothes clause`() {
        omitSubject.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today, wear a sweater."
    }

    @Test
    fun `omit range keeps following clauses after the merged lead`() {
        omitSubject.format(
            summary(
                clothes = ClothesClause(listOf("sweater")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        ) shouldBe "Today, wear a sweater. Rain at 3pm."
    }

    @Test
    fun `omit range on tonight uses the tonight lead`() {
        omitSubject.format(
            summary(period = ForecastPeriod.TONIGHT, clothes = ClothesClause(listOf("jacket"))),
        ) shouldBe "Tonight, wear a jacket."
    }

    @Test
    fun `omit range gives the leading delta an it-will-be subject`() {
        // With the band sentence dropped, the delta is the first temperature
        // field, so it introduces itself rather than reading as a bare
        // fragment ("Today, 5° warmer than yesterday.").
        omitSubject.format(summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER))) shouldBe
            "Today, it will be 5° warmer than yesterday."
    }

    @Test
    fun `omit range keeps the leading delta localized for non-English`() {
        // The leading delta uses the locale's own self-introducing fragment
        // (German insight_delta_warmer_lead), never the English "it will be …"
        // clause spliced into otherwise-localized prose.
        val germanOmit = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            rangeFormat = RangeFormat.NONE,
        )
        germanOmit.format(summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER)))
            .shouldNotContain("it will be")
    }

    @Test
    fun `omit range folds the period lead in front of the leading delta`() {
        // Range omitted: the delta leads, using the self-introducing German
        // fragment ("es wird … wärmer als gestern.") with the period lead
        // folded in front via insight_lead_continues — "Heute, es wird …",
        // mirroring the English "Today, it will be …". The lead appears exactly
        // once (no "Heute, heute …" doubling).
        val germanOmit = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            rangeFormat = RangeFormat.NONE,
        )
        germanOmit.format(summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER))) shouldBe
            "Heute, es wird 5° wärmer als gestern."
    }

    @Test
    fun `omit range with no other clause shows the unchanged line by default`() {
        // A lone period word ("Today.") carries no information; display surfaces
        // get a full "the same as yesterday." line instead so the card isn't blank.
        omitSubject.format(summary()) shouldBe "Today, it will be the same as yesterday."
    }

    @Test
    fun `omit range with no other clause compares to last night for tonight`() {
        omitSubject.format(summary(period = ForecastPeriod.TONIGHT)) shouldBe
            "Tonight, it will be the same as last night."
    }

    @Test
    fun `omit range with no other clause compares to today on a future day`() {
        omitSubject.format(summary(), isFutureDay = true) shouldBe
            "Tomorrow, it will be the same as today."
    }

    @Test
    fun `omit range with no other clause emits nothing when placeholder is opted out`() {
        // The TTS path opts out so spoken playback stays silent rather than
        // reading out a content-free filler line.
        omitSubject.format(summary(), placeholderWhenEmpty = false) shouldBe ""
    }

    @Test
    fun `omit range with no clause but an alert still emits the alert`() {
        omitSubject.format(summary(alert = AlertClause("Tornado Warning"))) shouldBe
            "Alert: Tornado Warning."
    }

    @Test
    fun `omit range keeps the alert ahead of the merged lead`() {
        omitSubject.format(
            summary(alert = AlertClause("Tornado Warning"), clothes = ClothesClause(listOf("sweater"))),
        ) shouldBe "Alert: Tornado Warning. Today, wear a sweater."
    }

    @Test
    fun `omit range does not double the lead when only a self-leading tie-in remains`() {
        // The evening tie-in already fronts "Tonight, …"; folding "Today," in
        // would read "Today, tonight, bring a jacket." With no daytime clause to
        // introduce, the tie-in stands on its own.
        omitSubject.format(
            summary(eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket"))),
        ) shouldBe "Tonight, bring a jacket."
    }

    @Test
    fun `omit range folds the lead into daytime content but leaves the tie-in intact`() {
        omitSubject.format(
            summary(
                clothes = ClothesClause(listOf("sweater")),
                eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket")),
            ),
        ) shouldBe "Today, wear a sweater. Tonight, bring a jacket."
    }

    @Test
    fun `umbrella-with-rain on TONIGHT folds the carried umbrella into the precip clause`() {
        // The umbrella rides in via the recommended items (a fired umbrella
        // rule). It's filtered out of the wear sentence (carried, not worn) and
        // the calendar tie-in skips it as a dupe, but the precip clause folds it
        // in so rain and umbrella travel together: "Rain at 9pm, bring an
        // umbrella." — one mention, not three sentences.
        val out = subject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(21, 0)),
                calendarTieIn = CalendarTieInClause("umbrella"),
            ),
        )
        out shouldBe "Tonight, it will be 21°. Rain at 9pm, bring an umbrella."
    }

    @Test
    fun `calendar tie-in on TONIGHT uses the no-prefix bring template`() {
        // The band lead already says "Tonight, it will be …" so the tie-in
        // doesn't repeat the "Tonight" intro — the resource resolves to
        // insight_tie_in_at_night ("Bring …") instead of insight_tie_in
        // ("Tonight, bring …").
        val out = subject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause("sweater"),
            ),
        )
        out shouldBe "Tonight, it will be 21°. Bring a sweater."
    }

    @Test
    fun `tie-in is suppressed when its item is already in the clothes list`() {
        // Both clauses naming "sweater" would emit "Wear a sweater. Bring a
        // sweater tonight." The dedup drops the tie-in since it adds no new
        // vocabulary.
        val out = subject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                clothes = ClothesClause(listOf("sweater")),
                calendarTieIn = CalendarTieInClause("sweater"),
            ),
        )
        out shouldBe "Tonight, it will be 21°. Wear a sweater."
    }

    @Test
    fun `evening event tie-in renders bare rain when items are empty and rain time is set`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = emptyList(),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, rain at 9pm."
    }

    @Test
    fun `evening event tie-in hedges to chance when likelihood is POSSIBLE`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = emptyList(),
                    rainTime = LocalTime.of(21, 0),
                    likelihood = PrecipLikelihood.POSSIBLE,
                ),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, chance of rain at 9pm."
    }

    @Test
    fun `evening event tie-in is omitted when items are empty and rain time is null too`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = emptyList(), rainTime = null),
            ),
        )
        out shouldBe "Today, it will be 21°."
    }

    @Test
    fun `evening event tie-in renders 'Tonight, bring' when no evening rain`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket")),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, bring a jacket."
    }

    @Test
    fun `evening event tie-in joins multiple delta items into one sentence`() {
        // When the night triggers more than one item the day didn't already
        // announce ("a jacket and coat"), both surface in the same tie-in
        // sentence.
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket", "coat")),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, bring a jacket and coat."
    }

    @Test
    fun `evening event tie-in Oxford-joins three delta items`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = listOf("sweater", "jacket", "scarf")),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, bring a sweater, jacket, and a scarf."
    }

    @Test
    fun `evening event tie-in folds rain time leading the items when present`() {
        // Rain leads the item ("Tonight, rain at 9pm, bring a jacket.") so
        // the tie-in reads weather-then-action, same shape as the day insight
        // (precip clause precedes the carry).
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket"), rainTime = LocalTime.of(21, 0)),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring a jacket."
    }

    @Test
    fun `evening event tie-in silences a lone umbrella and falls through to bare rain`() {
        // Umbrella is filtered out (accessories are silenced); with no items
        // left to name and a rain time still set, the clause collapses to
        // the bare-rain prose.
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("umbrella"),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, rain at 9pm."
    }

    @Test
    fun `evening event tie-in silences umbrella in a multi-item list and keeps the others`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket", "umbrella"),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring a jacket."
    }

    @Test
    fun `evening event tie-in silences a lone umbrella with no rain time too`() {
        // No rain to fall through to, no garment to name → the tie-in drops
        // entirely.
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = listOf("umbrella")),
            ),
        )
        out shouldBe "Today, it will be 21°."
    }

    @Test
    fun `evening event tie-in hedges item-led wording when likelihood is POSSIBLE`() {
        // Matches the bare-rain path's hedge: a clothes rule triggered (the
        // user has a jacket rule and the evening is cool) but only one
        // per-model series flagged the rain, so the per-model tier was
        // POSSIBLE rather than LIKELY.
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket"),
                    rainTime = LocalTime.of(21, 0),
                    likelihood = PrecipLikelihood.POSSIBLE,
                ),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, chance of rain at 9pm, bring a jacket."
    }

    @Test
    fun `evening event tie-in coexists with morning band and clothes`() {
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf("shorts")),
                eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket")),
            ),
        )
        out shouldBe "Today, it will be 21°. Wear shorts. Tonight, bring a jacket."
    }

    // ---------------------------------------------------------------------
    // RainAccessory.UMBRELLA — opts in to "bring an umbrella" alongside the
    // existing rain mention. The accessory rides on the precip clause and
    // the evening event tie-in (whenever a rain time is present); it's
    // never injected when rain isn't being mentioned.
    // ---------------------------------------------------------------------

    private val umbrellaSubject = InsightFormatter.forContext(
        context,
        Locale.ENGLISH,
        rainAccessory = RainAccessory.UMBRELLA,
    )

    @Test
    fun `umbrella accessory appends 'bring an umbrella' to the LIKELY precip clause`() {
        umbrellaSubject.format(
            summary(
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        ) shouldBe "Today, it will be 21°. Rain at 3pm, bring an umbrella."
    }

    @Test
    fun `umbrella accessory appends 'bring an umbrella' to the POSSIBLE precip clause`() {
        umbrellaSubject.format(
            summary(
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(
                    WeatherCondition.RAIN,
                    LocalTime.of(15, 0),
                    PrecipLikelihood.POSSIBLE,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Chance of rain at 3pm, bring an umbrella."
    }

    @Test
    fun `umbrella accessory appends to the overnight-collapsed precip clause`() {
        umbrellaSubject.format(
            summary(
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(2, 0)),
            ),
        ) shouldBe "Today, it will be 21°. Rain overnight, bring an umbrella."
    }

    @Test
    fun `umbrella accessory rides a drizzle precip clause`() {
        // DRIZZLE is rain-like enough that an umbrella still reads correctly.
        umbrellaSubject.format(
            summary(
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(WeatherCondition.DRIZZLE, LocalTime.NOON),
            ),
        ) shouldBe "Today, it will be 21°. Drizzle at noon, bring an umbrella."
    }

    @Test
    fun `umbrella accessory is suppressed when the precip is snow`() {
        // Regression: "Snow overnight, bring an umbrella." reads wrong — the
        // accessory is rain-keyed by name and an umbrella isn't the right tool
        // for snow.
        umbrellaSubject.format(
            summary(precip = PrecipClause(WeatherCondition.SNOW, LocalTime.MIDNIGHT)),
        ) shouldBe "Today, it will be 21°. Snow overnight."
    }

    @Test
    fun `umbrella accessory is suppressed when the precip is a thunderstorm`() {
        // Lightning + umbrella is bad practice; the precip clause already
        // names the thunderstorm, the listener doesn't need an unsafe carry.
        umbrellaSubject.format(
            summary(precip = PrecipClause(WeatherCondition.THUNDERSTORM, LocalTime.of(15, 0))),
        ) shouldBe "Today, it will be 21°. Thunderstorm at 3pm."
    }

    @Test
    fun `umbrella accessory is suppressed on a snow chance-of clause`() {
        // Same condition-gating applies to the POSSIBLE-tier template — a
        // hedged snow mention shouldn't pick up the accessory either.
        umbrellaSubject.format(
            summary(
                precip = PrecipClause(
                    WeatherCondition.SNOW,
                    LocalTime.of(15, 0),
                    PrecipLikelihood.POSSIBLE,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Chance of snow at 3pm."
    }

    @Test
    fun `umbrella accessory does not surface when there is no precip clause`() {
        umbrellaSubject.format(
            summary(clothes = ClothesClause(listOf("sweater"))),
        ) shouldBe "Today, it will be 21°. Wear a sweater."
    }

    @Test
    fun `umbrella accessory is injected alongside items in the evening tie-in with rain`() {
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket", "umbrella"),
                    rainTime = LocalTime.of(21, 0),
                    precipCondition = WeatherCondition.RAIN,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring a jacket and umbrella."
    }

    @Test
    fun `umbrella accessory promotes the bare-rain evening tie-in to the item-led template`() {
        // With RainAccessory.NONE this path renders "Tonight, rain at 9pm.".
        // The umbrella choice promotes it to the item-led template with the
        // accessory as the lone item.
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("umbrella"),
                    rainTime = LocalTime.of(21, 0),
                    precipCondition = WeatherCondition.RAIN,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring an umbrella."
    }

    @Test
    fun `umbrella accessory rides the hedged evening tie-in when likelihood is POSSIBLE`() {
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("umbrella"),
                    rainTime = LocalTime.of(21, 0),
                    likelihood = PrecipLikelihood.POSSIBLE,
                    precipCondition = WeatherCondition.RAIN,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, chance of rain at 9pm, bring an umbrella."
    }

    @Test
    fun `umbrella accessory is not injected into the evening tie-in when rain time is null`() {
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(items = listOf("jacket")),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, bring a jacket."
    }

    @Test
    fun `umbrella accessory dedupes — a stray umbrella in items list stays silenced before injection`() {
        // The filter still strips every accessory from the incoming items
        // list; injection happens after. So an umbrella-keyed ClothesRule
        // that fired into items doesn't double-up with the format-option
        // injection.
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket", "umbrella"),
                    rainTime = LocalTime.of(21, 0),
                    precipCondition = WeatherCondition.RAIN,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring a jacket and umbrella."
    }

    @Test
    fun `umbrella accessory is suppressed in the evening tie-in when the peak is snow`() {
        // The clause's rainTime field gets populated from any precip peak
        // (DeriveInsight aliases it across condition types), so we explicitly
        // check the precipCondition before injecting. SNOW → bare-rain
        // template, no umbrella ride-along.
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = emptyList(),
                    rainTime = LocalTime.of(21, 0),
                    precipCondition = WeatherCondition.SNOW,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, rain at 9pm."
    }

    @Test
    fun `umbrella accessory is suppressed in the evening tie-in when the peak is thunderstorm`() {
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket"),
                    rainTime = LocalTime.of(21, 0),
                    precipCondition = WeatherCondition.THUNDERSTORM,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring a jacket."
    }

    @Test
    fun `umbrella accessory is suppressed in the evening tie-in when precipCondition is null`() {
        // Pre-field cached payloads land with precipCondition=null. Treat
        // that as "unknown, skip" rather than guess — next forecast cycle
        // populates the field.
        umbrellaSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("jacket"),
                    rainTime = LocalTime.of(21, 0),
                    precipCondition = null,
                ),
            ),
        ) shouldBe "Today, it will be 21°. Tonight, rain at 9pm, bring a jacket."
    }

    @Test
    fun `de — umbrella accessory renders Regenschirm via the German phraser`() {
        val germanUmbrellaSubject = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            rainAccessory = RainAccessory.UMBRELLA,
        )
        germanUmbrellaSubject.format(
            summary(
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        ) shouldBe "Heute wird es 21°. Regen um 15 Uhr, denk an Regenschirm."
    }

    @Test
    fun `umbrella still surfaces in the precip clause when the wear clause is suppressed`() {
        // Clothes = Never (or an unchanged outfit under If changed) nulls the
        // wear clause, but a carried accessory rides on carriedAccessories
        // independently, so an opted-in umbrella still gets named on a rainy day.
        subject.format(
            summary(
                clothes = null,
                carriedAccessories = listOf("umbrella"),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        ) shouldBe "Today, it will be 21°. Rain at 3pm, bring an umbrella."
    }

    @Test
    fun `full insight composes alert + band + delta + clothes + precip in order`() {
        // The umbrella is filtered out of the wear sentence (carried, not worn)
        // but folds into the precip clause, so "Wear a sweater." names the worn
        // garment and "Rain at 3pm, bring an umbrella." carries the carry.
        val out = subject.format(
            summary(
                alert = AlertClause("Flood Warning"),
                band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD),
                delta = DeltaClause(6, DeltaClause.Direction.WARMER),
                clothes = ClothesClause(listOf("sweater", "umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        )
        out shouldBe "Alert: Flood Warning. Today, it will be 15° to 21°. 6° warmer than yesterday. " +
            "Wear a sweater. Rain at 3pm, bring an umbrella."
    }

    @Test
    fun `umbrella with no precip clause is silenced — only the surviving garments remain`() {
        // Even when no precip clause is present to implicitly cover the
        // umbrella, the accessory is filtered out. The user's umbrella rule
        // is silent in the rendered prose until the accessory catalog lands.
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf("sweater", "umbrella")),
            ),
        )
        out shouldBe "Today, it will be 21°. Wear a sweater."
    }

    @Test
    fun `region system follows the context locale instead of process default`() {
        val originalDefault = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            val deConfig = Configuration(context.resources.configuration).apply {
                setLocale(Locale.GERMANY)
            }
            val germanContext = context.createConfigurationContext(deConfig)

            InsightFormatter.forRegion(germanContext, Region.SYSTEM).format(summary()) shouldBe
                "Heute wird es 21°."
        } finally {
            Locale.setDefault(originalDefault)
        }
    }

    @Test
    fun `de-AT region on an en-GB base context produces fully German prose`() {
        // Regression: device locale en-GB, Region set to DE_AT used to render
        // "Tonight will be cold to cool. Wear Pullover and Jacke." on Android
        // 13+ — the InsightFormatter's locale-bound Locale picked German for
        // the phraser while createConfigurationContext silently fell back to
        // the device locale for the templates. The runtime fix lives in
        // AppLocale (Locale.setDefault + LocaleManager.setApplicationLocales /
        // attachBaseContext wrap); this test pins the formatter's own
        // forRegion path as a belt-and-suspenders unit check.
        val enGbConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("en-GB"))
        }
        val enGbContext = context.createConfigurationContext(enGbConfig)

        InsightFormatter.forRegion(enGbContext, Region.DE_AT).format(
            summary(
                period = ForecastPeriod.TONIGHT,
                band = BandClause(TemperatureBand.COLD, TemperatureBand.COOL),
                clothes = ClothesClause(listOf("sweater", "jacket")),
            ),
        ) shouldBe "Heute Abend wird es 8° bis 15°. Trag Pullover und Jacke."
    }

    // ---------------------------------------------------------------------
    // British / Australian English — picks up the en-rGB / en-rAU vocabulary
    // overrides on `garment_*` resources so the rendered prose matches the
    // dropdown labels (en-GB user reads "Wear a jumper.", not "Wear a sweater.").
    // ---------------------------------------------------------------------

    private val britishSubject = InsightFormatter.forContext(context, Locale.forLanguageTag("en-GB"))
    private val australianSubject = InsightFormatter.forContext(context, Locale.forLanguageTag("en-AU"))

    @Test
    fun `en-GB — sweater key renders as 'a jumper'`() {
        britishSubject.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today, it will be 21°. Wear a jumper."
    }

    @Test
    fun `en-GB — pants key renders bare as 'trousers' (plural ending)`() {
        britishSubject.format(summary(clothes = ClothesClause(listOf("pants")))) shouldBe
            "Today, it will be 21°. Wear trousers."
    }

    @Test
    fun `en-GB — multi-item list translates each item and articles each singular`() {
        // Umbrella is silenced; the surviving garments stay joined with en-GB
        // vocabulary. "Trousers" reads as plural so it picks no article — the
        // jumper still gets its "a" without dragging one onto the trousers.
        britishSubject.format(
            summary(clothes = ClothesClause(listOf("sweater", "pants", "umbrella"))),
        ) shouldBe "Today, it will be 21°. Wear a jumper and trousers."
    }

    @Test
    fun `en-GB — calendar tie-in translates the item`() {
        val out = britishSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause("sweater"),
            ),
        )
        out shouldBe "Tonight, it will be 21°. Bring a jumper."
    }

    @Test
    fun `en-AU — sweater renders as 'a jumper' and pants stays 'pants'`() {
        // values-en-rAU/strings.xml explicitly overrides garment_pants to
        // "Pants" to block the en-rGB inheritance — Android's API 24+
        // resource resolver would otherwise pick values-en-rGB ("Trousers")
        // as a closer match for en-AU than the en-US default, since en-AU
        // and en-GB share the en-001 CLDR ancestor. The override pins the
        // natural Aussie usage.
        australianSubject.format(
            summary(clothes = ClothesClause(listOf("sweater", "pants"))),
        ) shouldBe "Today, it will be 21°. Wear a jumper and pants."
    }

    // ---------------------------------------------------------------------
    // German locale — picks up values-de/strings.xml + GermanClothesPhraser.
    // Spot-checks rather than mirroring every English case: the goal is to
    // confirm the localized resources are actually wired through and that
    // GermanClothesPhraser's bare-with-und join produces idiomatic prose.
    // ---------------------------------------------------------------------

    private val germanSubject = InsightFormatter.forContext(context, Locale.GERMAN)

    @Test
    fun `de — single band reads 'Heute wird es 21°'`() {
        germanSubject.format(summary()) shouldBe "Heute wird es 21°."
    }

    @Test
    fun `de — band range uses 'bis'`() {
        germanSubject.format(summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD))) shouldBe
            "Heute wird es 15° bis 21°."
    }

    @Test
    fun `de — tonight lead-in becomes 'Heute Abend'`() {
        germanSubject.format(summary(period = ForecastPeriod.TONIGHT)) shouldBe
            "Heute Abend wird es 21°."
    }

    @Test
    fun `de — clothes list is bare comma + und with no articles`() {
        germanSubject.format(
            summary(clothes = ClothesClause(listOf("Pullover", "Jacke", "Regenschirm"))),
        ) shouldBe "Heute wird es 21°. Trag Pullover, Jacke und Regenschirm."
    }

    @Test
    fun `de — translates default English keywords to German`() {
        // The DEFAULTS ship as English keys (sweater / jacket / shorts) so the
        // outfit-card labels and the German prose stay in sync; the phraser's
        // translation table maps them at format time. Anything not in the
        // table (a user-typed custom item like "fluffy hat") would pass
        // through unchanged.
        germanSubject.format(
            summary(clothes = ClothesClause(listOf("sweater", "jacket", "shorts"))),
        ) shouldBe "Heute wird es 21°. Trag Pullover, Jacke und kurze Hose."
    }

    @Test
    fun `de — gloves localizes through the phraser map, not the raw key`() {
        // Guards the GARMENT_RES_IDS wiring for the freezing-day default: a
        // gloves key missing from the map would fall through as the raw English
        // "gloves" in otherwise-German prose. Must read "Handschuhe".
        germanSubject.format(
            summary(clothes = ClothesClause(listOf("coat", "gloves"))),
        ) shouldBe "Heute wird es 21°. Trag Mantel und Handschuhe."
    }

    @Test
    fun `de — precip uses 'um' between condition and time`() {
        germanSubject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)))) shouldBe
            "Heute wird es 21°. Regen um 15 Uhr."
    }

    @Test
    fun `de — calendar tie-in is suppressed when it duplicates a clothes item`() {
        // Pre-fix this rendered "Trag Regenschirm. Denk an Regenschirm für
        // heute Abend." — the tie-in repeated the wear sentence's item. Dedup
        // suppresses it.
        val out = germanSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                clothes = ClothesClause(listOf("Regenschirm")),
                calendarTieIn = CalendarTieInClause("Regenschirm"),
            ),
        )
        out shouldBe "Heute Abend wird es 21°. Trag Regenschirm."
    }

    @Test
    fun `de — calendar tie-in renders the German no-prefix template when it doesn't duplicate`() {
        // Without a clothes line to dedup against, the tie-in surfaces. On
        // TONIGHT it uses insight_tie_in_at_night (German "Denk an %1$s.")
        // since the band lead has already established the night context.
        val out = germanSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause("Regenschirm"),
            ),
        )
        out shouldBe "Heute Abend wird es 21°. Denk an Regenschirm."
    }

    @Test
    fun `de — evening event tie-in folds rain time via German positional placeholders`() {
        val out = germanSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("Regenschirm"),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )
        out shouldBe "Heute wird es 21°. Denk an Regenschirm für heute Abend, Regen um 21 Uhr."
    }

    @Test
    fun `de — no-lead single band drops 'Heute wird es'`() {
        val germanNoLead =
            InsightFormatter.forContext(context, Locale.GERMAN, periodPreamble = PreambleVisibility.NEVER)
        germanNoLead.format(summary()) shouldBe "21°."
    }

    @Test
    fun `de — no-lead band range keeps the German 'bis' connector`() {
        val germanNoLead =
            InsightFormatter.forContext(context, Locale.GERMAN, periodPreamble = PreambleVisibility.NEVER)
        germanNoLead.format(
            summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD)),
        ) shouldBe "15° bis 21°."
    }

    @Test
    fun `de — no-lead unchanged placeholder is the brief German fragment`() {
        // RangeFormat.NONE with nothing else firing falls back to the
        // "unchanged" placeholder; the no-lead form drops the whole "Heute wird
        // es genauso …" scaffold down to the bare comparison, so it stays terse
        // and matches the card header.
        val germanNoLead = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            rangeFormat = RangeFormat.NONE,
            periodPreamble = PreambleVisibility.NEVER,
        )
        germanNoLead.format(summary()) shouldBe "Wie gestern."
    }

    @Test
    fun `de — settings preview parenthesises the German lead for the unchanged line`() {
        // Because the no-lead form is a clean suffix of the full sentence, the
        // Speech-only preview recovers the dropped lead and parenthesises it
        // ("(Heute wird es) genauso wie gestern.") rather than falling back to
        // the un-parenthesised full sentence.
        val germanPreview = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            rangeFormat = RangeFormat.NONE,
            periodPreamble = PreambleVisibility.SPEECH_ONLY,
        )
        germanPreview.format(summary(), surface = InsightSurface.SETTINGS_PREVIEW) shouldBe
            "(Heute wird es genauso) wie gestern."
    }

    @Test
    fun `de — no-lead leading delta uses the self-introducing German fragment`() {
        // Range omitted + a numeric delta: the delta leads the temperature
        // content, so it uses insight_delta_warmer_lead ("es wird … wärmer als
        // gestern.") capitalised, rather than the trailing "Heute wird es …"
        // fragment.
        val germanNoLead = InsightFormatter.forContext(
            context,
            Locale.GERMAN,
            rangeFormat = RangeFormat.NONE,
            periodPreamble = PreambleVisibility.NEVER,
        )
        germanNoLead.format(
            summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER)),
        ) shouldBe "Es wird 5° wärmer als gestern."
    }

    // ---------------------------------------------------------------------
    // Spanish locale — picks up values-es/strings.xml + ResourceClothesPhraser.
    // Tie-in tests pin the Spanish templates that previously fell through to
    // the German strings ("Denk an … Grad …"), surfacing as "sieben Grad" in
    // TTS for a Spanish-region user.
    // ---------------------------------------------------------------------

    private val spanishSubject = InsightFormatter.forContext(context, Locale.forLanguageTag("es-ES"))

    @Test
    fun `es — calendar tie-in renders the Spanish no-prefix template on TONIGHT`() {
        // Period=TONIGHT uses insight_tie_in_at_night ("Lleva %1$s.") so the
        // tie-in doesn't repeat the band lead's "Esta noche".
        val out = spanishSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause("paraguas"),
            ),
        )
        out shouldBe "Esta noche hará 21°. Lleva paraguas."
    }

    @Test
    fun `es — evening event tie-in with rain renders the Spanish template`() {
        val out = spanishSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf("paraguas"),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )
        out shouldBe "Hoy hará 21°. Lleva paraguas esta noche, lluvia a las 21."
    }

    @Test
    fun `es — catalog keys still translate when source casing and whitespace are messy`() {
        val out = spanishSubject.format(
            summary(
                clothes = ClothesClause(listOf(" Sweater ", "JACKET")),
            ),
        )
        out shouldBe "Hoy hará 21°. Lleva jersey y chaqueta."
    }

    @Test
    fun `clothes clause ignores blank entries instead of speaking empty nouns`() {
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf(" ", "sweater", "")),
            ),
        )
        out shouldBe "Today, it will be 21°. Wear a sweater."
    }

    @Test
    fun `clothes clause is omitted when all entries are blank`() {
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf(" ", "\t", "")),
            ),
        )
        out shouldBe "Today, it will be 21°."
    }

    @Test
    fun `calendar tie-in is omitted when item is blank`() {
        val out = subject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause(" "),
            ),
        )
        out shouldBe "Tonight, it will be 21°."
    }

    @Test
    fun `evening event tie-in falls through to bare rain when items resolve to blank`() {
        // Defensive: if the renderer ever passes a list of blank items the
        // phraser would join to nothing, the rain time is still real signal —
        // emit the bare-rain prose rather than silently dropping the clause.
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(
                    items = listOf(" "),
                    rainTime = LocalTime.of(21, 0),
                ),
            ),
        )
        out shouldBe "Today, it will be 21°. Tonight, rain at 9pm."
    }

    @Test
    fun `week-ahead rain-only headline renders solo`() {
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                rain = WeekAheadClause.Rain(
                    date = LocalDate.of(2026, 6, 1), // Monday
                    isTomorrow = false,
                    condition = WeatherCondition.RAIN,
                ),
            ),
        )
        out shouldBe "Rain Monday."
    }

    @Test
    fun `week-ahead temperature-only headline renders solo`() {
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                firstCooler = WeekAheadClause.Cooler(
                    date = LocalDate.of(2026, 5, 27),
                    isTomorrow = true,
                ),
            ),
        )
        out shouldBe "Cooler tomorrow."
    }

    @Test
    fun `week-ahead joins temperature and rain chronologically when temp lands first`() {
        // Cooler tomorrow, rain Monday — cooler clause sorts first.
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                firstCooler = WeekAheadClause.Cooler(
                    date = LocalDate.of(2026, 5, 27),
                    isTomorrow = true,
                ),
                rain = WeekAheadClause.Rain(
                    date = LocalDate.of(2026, 6, 1),
                    isTomorrow = false,
                    condition = WeatherCondition.RAIN,
                ),
            ),
        )
        out shouldBe "Cooler tomorrow, rain Monday."
    }

    @Test
    fun `week-ahead joins temperature and rain chronologically when rain lands first`() {
        // Rain Wednesday, warmer Saturday — rain clause sorts first.
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                firstWarmer = WeekAheadClause.Warmer(
                    date = LocalDate.of(2026, 5, 30), // Saturday
                    isTomorrow = false,
                ),
                rain = WeekAheadClause.Rain(
                    date = LocalDate.of(2026, 5, 27), // Wednesday
                    isTomorrow = false,
                    condition = WeatherCondition.RAIN,
                ),
            ),
        )
        out shouldBe "Rain Wednesday, warmer Saturday."
    }

    @Test
    fun `week-ahead joins first-warmer and first-cooler chronologically`() {
        // Warmer tomorrow, cooler Sunday — the headline that motivated the
        // first-warmer / first-cooler split.
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                firstWarmer = WeekAheadClause.Warmer(
                    date = LocalDate.of(2026, 5, 28), // Thursday — tomorrow
                    isTomorrow = true,
                ),
                firstCooler = WeekAheadClause.Cooler(
                    date = LocalDate.of(2026, 5, 31), // Sunday
                    isTomorrow = false,
                ),
            ),
        )
        out shouldBe "Warmer tomorrow, cooler Sunday."
    }

    @Test
    fun `week-ahead emits both clauses even when rain and temp shift land on the same day`() {
        val sameDay = LocalDate.of(2026, 6, 1) // Monday
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                firstCooler = WeekAheadClause.Cooler(
                    date = sameDay,
                    isTomorrow = false,
                ),
                rain = WeekAheadClause.Rain(
                    date = sameDay,
                    isTomorrow = false,
                    condition = WeatherCondition.RAIN,
                ),
            ),
        )
        out shouldBe "Cooler Monday, rain Monday."
    }

    @Test
    fun `week-ahead joins persistence and rain with persistence first`() {
        val out = subject.formatWeekAhead(
            WeekAheadInsight(
                persistence = WeekAheadClause.StaysHot,
                rain = WeekAheadClause.Rain(
                    date = LocalDate.of(2026, 5, 29), // Friday
                    isTomorrow = false,
                    condition = WeatherCondition.THUNDERSTORM,
                ),
            ),
        )
        out shouldBe "Hot all week, rain Friday."
    }
}
