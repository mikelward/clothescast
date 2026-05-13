package app.clothescast.insight

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.domain.model.AlertClause
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.CalendarTieInClause
import app.clothescast.core.domain.model.EveningEventTieInClause
import app.clothescast.core.domain.model.ClothesClause
import app.clothescast.core.domain.model.DeltaClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.PrecipClause
import app.clothescast.core.domain.model.PrecipLikelihood
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureBand
import app.clothescast.core.domain.model.WeatherCondition
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
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

    private fun summary(
        period: ForecastPeriod = ForecastPeriod.TODAY,
        band: BandClause = BandClause(TemperatureBand.MILD, TemperatureBand.MILD),
        alert: AlertClause? = null,
        delta: DeltaClause? = null,
        clothes: ClothesClause? = null,
        precip: PrecipClause? = null,
        calendarTieIn: CalendarTieInClause? = null,
        eveningEventTieIn: EveningEventTieInClause? = null,
    ) = InsightSummary(period, band, alert, delta, clothes, precip, calendarTieIn, eveningEventTieIn)

    @Test
    fun `band-only insight emits the lead-in and label`() {
        subject.format(summary()) shouldBe "Today will be mild."
    }

    @Test
    fun `band emits a low-to-high range when min and max fall in different bands`() {
        subject.format(summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD))) shouldBe
            "Today will be cool to mild."
    }

    @Test
    fun `tonight period switches the lead-in`() {
        subject.format(summary(period = ForecastPeriod.TONIGHT)) shouldBe "Tonight will be mild."
    }

    @Test
    fun `delta clause emits warmer with rounded degrees`() {
        val out = subject.format(summary(delta = DeltaClause(5, DeltaClause.Direction.WARMER)))
        out shouldBe "Today will be mild. It will be 5° warmer than yesterday."
    }

    @Test
    fun `delta clause emits cooler`() {
        val out = subject.format(summary(delta = DeltaClause(6, DeltaClause.Direction.COOLER)))
        out shouldBe "Today will be mild. It will be 6° cooler than yesterday."
    }

    @Test
    fun `clothes with a single article-able item emits 'a sweater'`() {
        subject.format(summary(clothes = ClothesClause(listOf("sweater")))) shouldBe
            "Today will be mild. Wear a sweater."
    }

    @Test
    fun `clothes picks 'an' before vowel-leading items`() {
        subject.format(summary(clothes = ClothesClause(listOf("umbrella")))) shouldBe
            "Today will be mild. Wear an umbrella."
    }

    @Test
    fun `clothes normalizes capitalized english items mid-sentence`() {
        subject.format(summary(clothes = ClothesClause(listOf("Sweater", "Jacket")))) shouldBe
            "Today will be mild. Wear a sweater and jacket."
    }

    @Test
    fun `clothes drops the article on plural-looking items`() {
        subject.format(summary(clothes = ClothesClause(listOf("shorts")))) shouldBe
            "Today will be mild. Wear shorts."
    }

    @Test
    fun `clothes joins two items with 'and' and only the first item gets an article`() {
        subject.format(summary(clothes = ClothesClause(listOf("sweater", "jacket")))) shouldBe
            "Today will be mild. Wear a sweater and jacket."
    }

    @Test
    fun `clothes Oxford-joins three items with article only on the first`() {
        subject.format(summary(clothes = ClothesClause(listOf("sweater", "jacket", "umbrella")))) shouldBe
            "Today will be mild. Wear a sweater, jacket, and umbrella."
    }

    @Test
    fun `clothes Oxford-joins four items`() {
        subject.format(
            summary(clothes = ClothesClause(listOf("sweater", "jacket", "shorts", "umbrella"))),
        ) shouldBe "Today will be mild. Wear a sweater, jacket, shorts, and umbrella."
    }

    @Test
    fun `precip clause emits with spoken peak hour and capitalised type`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)))) shouldBe
            "Today will be mild. Rain at 3pm."
    }

    @Test
    fun `precip clause says 'noon' for 12-00`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.DRIZZLE, LocalTime.NOON))) shouldBe
            "Today will be mild. Drizzle at noon."
    }

    @Test
    fun `precip clause says 'overnight' for early-morning peak`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(2, 0)))) shouldBe
            "Today will be mild. Rain overnight."
    }

    @Test
    fun `precip clause says 'overnight' for midnight peak`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.SNOW, LocalTime.MIDNIGHT))) shouldBe
            "Today will be mild. Snow overnight."
    }

    @Test
    fun `precip clause uses 12-hour pm form for late-evening peak`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(23, 0)))) shouldBe
            "Today will be mild. Rain at 11pm."
    }

    @Test
    fun `precip clause renders non-zero minutes`() {
        subject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 30)))) shouldBe
            "Today will be mild. Rain at 3:30pm."
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
        ) shouldBe "Today will be mild. Chance of rain at 3pm."
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
        ) shouldBe "Today will be mild. Chance of drizzle at noon."
    }

    @Test
    fun `alert clause emits before the band`() {
        val out = subject.format(summary(alert = AlertClause("Tornado Warning")))
        out shouldBe "Alert: Tornado Warning. Today will be mild."
    }

    @Test
    fun `calendar tie-in renders 'Bring a item tonight' with no event title`() {
        val out = subject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                clothes = ClothesClause(listOf("umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(21, 0)),
                calendarTieIn = CalendarTieInClause("umbrella"),
            ),
        )
        out shouldBe "Tonight will be mild. Wear an umbrella. Rain at 9pm. Bring an umbrella tonight."
    }

    @Test
    fun `evening event tie-in renders 'Bring a item tonight' when no evening rain`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause("jacket"),
            ),
        )
        out shouldBe "Today will be mild. Bring a jacket tonight."
    }

    @Test
    fun `evening event tie-in folds rain time into the same sentence when present`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause("umbrella", rainTime = LocalTime.of(21, 0)),
            ),
        )
        out shouldBe "Today will be mild. Bring an umbrella tonight, rain at 9pm."
    }

    @Test
    fun `evening event tie-in coexists with morning band and clothes`() {
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf("shorts")),
                eveningEventTieIn = EveningEventTieInClause("jacket"),
            ),
        )
        out shouldBe "Today will be mild. Wear shorts. Bring a jacket tonight."
    }

    @Test
    fun `full insight composes alert + band + delta + clothes + precip in order`() {
        val out = subject.format(
            summary(
                alert = AlertClause("Flood Warning"),
                band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD),
                delta = DeltaClause(6, DeltaClause.Direction.WARMER),
                clothes = ClothesClause(listOf("sweater", "umbrella")),
                precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)),
            ),
        )
        out shouldBe "Alert: Flood Warning. Today will be cool to mild. It will be 6° warmer than yesterday. " +
            "Wear a sweater and umbrella. Rain at 3pm."
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
                "Heute wird es mild."
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
        ) shouldBe "Heute Abend wird es kalt bis kühl. Trag Pullover und Jacke."
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
            "Today will be mild. Wear a jumper."
    }

    @Test
    fun `en-GB — pants key renders bare as 'trousers' (plural ending)`() {
        britishSubject.format(summary(clothes = ClothesClause(listOf("pants")))) shouldBe
            "Today will be mild. Wear trousers."
    }

    @Test
    fun `en-GB — multi-item list translates each item, article only on the first`() {
        britishSubject.format(
            summary(clothes = ClothesClause(listOf("sweater", "pants", "umbrella"))),
        ) shouldBe "Today will be mild. Wear a jumper, trousers, and umbrella."
    }

    @Test
    fun `en-GB — calendar tie-in translates the item`() {
        val out = britishSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause("sweater"),
            ),
        )
        out shouldBe "Tonight will be mild. Bring a jumper tonight."
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
        ) shouldBe "Today will be mild. Wear a jumper and pants."
    }

    // ---------------------------------------------------------------------
    // German locale — picks up values-de/strings.xml + GermanClothesPhraser.
    // Spot-checks rather than mirroring every English case: the goal is to
    // confirm the localized resources are actually wired through and that
    // GermanClothesPhraser's bare-with-und join produces idiomatic prose.
    // ---------------------------------------------------------------------

    private val germanSubject = InsightFormatter.forContext(context, Locale.GERMAN)

    @Test
    fun `de — single band reads 'Heute wird es mild'`() {
        germanSubject.format(summary()) shouldBe "Heute wird es mild."
    }

    @Test
    fun `de — band range uses 'bis'`() {
        germanSubject.format(summary(band = BandClause(TemperatureBand.COOL, TemperatureBand.MILD))) shouldBe
            "Heute wird es kühl bis mild."
    }

    @Test
    fun `de — tonight lead-in becomes 'Heute Abend'`() {
        germanSubject.format(summary(period = ForecastPeriod.TONIGHT)) shouldBe
            "Heute Abend wird es mild."
    }

    @Test
    fun `de — clothes list is bare comma + und with no articles`() {
        germanSubject.format(
            summary(clothes = ClothesClause(listOf("Pullover", "Jacke", "Regenschirm"))),
        ) shouldBe "Heute wird es mild. Trag Pullover, Jacke und Regenschirm."
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
        ) shouldBe "Heute wird es mild. Trag Pullover, Jacke und kurze Hose."
    }

    @Test
    fun `de — precip uses 'um' between condition and time`() {
        germanSubject.format(summary(precip = PrecipClause(WeatherCondition.RAIN, LocalTime.of(15, 0)))) shouldBe
            "Heute wird es mild. Regen um 15 Uhr."
    }

    @Test
    fun `de — tie-in renders the German template with item only`() {
        val out = germanSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                clothes = ClothesClause(listOf("Regenschirm")),
                calendarTieIn = CalendarTieInClause("Regenschirm"),
            ),
        )
        out shouldBe "Heute Abend wird es mild. Trag Regenschirm. " +
            "Denk an Regenschirm für heute Abend."
    }

    @Test
    fun `de — evening event tie-in folds rain time via German positional placeholders`() {
        val out = germanSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause("Regenschirm", rainTime = LocalTime.of(21, 0)),
            ),
        )
        out shouldBe "Heute wird es mild. Denk an Regenschirm für heute Abend, Regen um 21 Uhr."
    }

    // ---------------------------------------------------------------------
    // Spanish locale — picks up values-es/strings.xml + ResourceClothesPhraser.
    // Tie-in tests pin the Spanish templates that previously fell through to
    // the German strings ("Denk an … Grad …"), surfacing as "sieben Grad" in
    // TTS for a Spanish-region user.
    // ---------------------------------------------------------------------

    private val spanishSubject = InsightFormatter.forContext(context, Locale.forLanguageTag("es-ES"))

    @Test
    fun `es — calendar tie-in renders the Spanish template, not a German fallback`() {
        val out = spanishSubject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause("paraguas"),
            ),
        )
        out shouldBe "Esta noche hará templado. Lleva paraguas esta noche."
    }

    @Test
    fun `es — evening event tie-in with rain renders the Spanish template`() {
        val out = spanishSubject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause("paraguas", rainTime = LocalTime.of(21, 0)),
            ),
        )
        out shouldBe "Hoy hará templado. Lleva paraguas esta noche, lluvia a las 21."
    }

    @Test
    fun `es — catalog keys still translate when source casing and whitespace are messy`() {
        val out = spanishSubject.format(
            summary(
                clothes = ClothesClause(listOf(" Sweater ", "JACKET")),
            ),
        )
        out shouldBe "Hoy hará templado. Lleva jersey y chaqueta."
    }

    @Test
    fun `clothes clause ignores blank entries instead of speaking empty nouns`() {
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf(" ", "sweater", "")),
            ),
        )
        out shouldBe "Today will be mild. Wear a sweater."
    }

    @Test
    fun `clothes clause is omitted when all entries are blank`() {
        val out = subject.format(
            summary(
                clothes = ClothesClause(listOf(" ", "\t", "")),
            ),
        )
        out shouldBe "Today will be mild."
    }

    @Test
    fun `calendar tie-in is omitted when item is blank`() {
        val out = subject.format(
            summary(
                period = ForecastPeriod.TONIGHT,
                calendarTieIn = CalendarTieInClause(" "),
            ),
        )
        out shouldBe "Tonight will be mild."
    }

    @Test
    fun `evening event tie-in is omitted when item is blank even when rain time exists`() {
        val out = subject.format(
            summary(
                eveningEventTieIn = EveningEventTieInClause(" ", rainTime = LocalTime.of(21, 0)),
            ),
        )
        out shouldBe "Today will be mild."
    }
}
