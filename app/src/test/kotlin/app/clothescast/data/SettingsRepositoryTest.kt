package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clothescast.core.domain.model.Garment
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.DeltaFormat
import app.clothescast.core.domain.model.ClothesMentionMode
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.DistanceUnitSetting
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TemperatureUnitSetting
import app.clothescast.core.domain.model.HolidayId
import app.clothescast.core.domain.model.HolidayOverride
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.core.domain.model.TtsStyle
import app.clothescast.core.domain.model.VoiceLocale
import app.clothescast.core.domain.model.thresholdC
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.diag.ClothesRulesSnapshot
import app.clothescast.diag.SettingsAnalyticsSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    @TempDir lateinit var tempDir: Path

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var subject: SettingsRepository

    @BeforeEach
    fun setUp() {
        // See SecureKeyStoreTest.setUp for why we install a test Main dispatcher even
        // though this class doesn't directly read Dispatchers.Main, and why we pass an
        // explicit scheduler instead of relying on the no-arg dispatcher constructor.
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir.toFile(), "settings.preferences_pb") },
        )
        subject = SettingsRepository(
            dataStore = dataStore,
            zoneIdProvider = { zone },
            // Pin the locale so default-unit assertions don't drift with the
            // host machine's locale (CI runs on en_US, dev machines vary).
            systemLocaleProvider = { Locale.UK },
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `defaults are returned when nothing is stored`() = runTest {
        val prefs = subject.preferences.first()

        prefs.schedule.time shouldBe LocalTime.of(7, 0)
        prefs.schedule.days shouldBe Schedule.EVERY_DAY
        prefs.schedule.zoneId shouldBe zone
        prefs.deliveryMode shouldBe DeliveryMode.NOTIFICATION_AND_TTS
        prefs.temperatureUnit shouldBe TemperatureUnit.CELSIUS
        prefs.distanceUnit shouldBe DistanceUnit.MILES   // setUp pins Locale.UK → miles
        prefs.clothesRules shouldBe ClothesRule.DEFAULTS
        // Both scheduled slots are opt-in out of the box (no onboarding to gather
        // the notification permission upfront).
        prefs.dailyEnabled shouldBe false
        prefs.tonightEnabled shouldBe false
        prefs.dailyMentionEveningEvents shouldBe false
    }

    @Test
    fun `setSchedule round-trips time and days`() = runTest {
        subject.setSchedule(
            time = LocalTime.of(6, 30),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        )

        val prefs = subject.preferences.first()
        prefs.schedule.time shouldBe LocalTime.of(6, 30)
        prefs.schedule.days.shouldContainExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    }

    @Test
    fun `setSchedule rejects empty days`() = runTest {
        shouldThrow<IllegalArgumentException> {
            subject.setSchedule(LocalTime.of(7, 0), emptySet())
        }
    }

    @Test
    fun `setDeliveryMode round-trips`() = runTest {
        subject.setDeliveryMode(DeliveryMode.NOTIFICATION_AND_TTS)

        subject.preferences.first().deliveryMode shouldBe DeliveryMode.NOTIFICATION_AND_TTS
    }

    @Test
    fun `homeSectionOrder defaults to the canonical order`() = runTest {
        subject.preferences.first().homeSectionOrder shouldBe HomeSection.DEFAULTS
    }

    @Test
    fun `setHomeSectionOrder round-trips a reordered list`() = runTest {
        // A complete order round-trips verbatim.
        val reordered = listOf(
            HomeSection.INSIGHT,
            HomeSection.CONDITIONS,
            HomeSection.OUTFIT,
            HomeSection.CONFIDENCE,
            HomeSection.CHARTS,
        )
        subject.setHomeSectionOrder(reordered)

        subject.preferences.first().homeSectionOrder shouldContainExactly reordered
    }

    @Test
    fun `homeSectionOrder normalizes a partial stored list on read`() = runTest {
        // A stored order naming only one section gets the rest appended in
        // default order, so a future-added section never goes missing.
        subject.setHomeSectionOrder(listOf(HomeSection.INSIGHT))

        subject.preferences.first().homeSectionOrder shouldContainExactly
            listOf(
                HomeSection.INSIGHT,
                HomeSection.CONDITIONS,
                HomeSection.OUTFIT,
                HomeSection.CONFIDENCE,
                HomeSection.CHARTS,
            )
    }

    @Test
    fun `tonightDeliveryMode falls back to deliveryMode when not set`() = runTest {
        // Existing installs that haven't seen the per-period split yet keep their
        // old shared-mode behaviour: tonight inherits the day card's selection.
        subject.setDeliveryMode(DeliveryMode.NOTIFICATION_AND_TTS)

        subject.preferences.first().tonightDeliveryMode shouldBe DeliveryMode.NOTIFICATION_AND_TTS
    }

    @Test
    fun `setTonightDeliveryMode round-trips and is independent of deliveryMode`() = runTest {
        subject.setDeliveryMode(DeliveryMode.NOTIFICATION_ONLY)
        subject.setTonightDeliveryMode(DeliveryMode.TTS_ONLY)

        val prefs = subject.preferences.first()
        prefs.deliveryMode shouldBe DeliveryMode.NOTIFICATION_ONLY
        prefs.tonightDeliveryMode shouldBe DeliveryMode.TTS_ONLY
    }

    @Test
    fun `dailyMentionEveningEvents defaults to false and round-trips`() = runTest {
        subject.preferences.first().dailyMentionEveningEvents shouldBe false

        subject.setDailyMentionEveningEvents(true)
        subject.preferences.first().dailyMentionEveningEvents shouldBe true

        subject.setDailyMentionEveningEvents(false)
        subject.preferences.first().dailyMentionEveningEvents shouldBe false
    }

    @Test
    fun `clothesMentionMode defaults to ALWAYS and round-trips all values`() = runTest {
        subject.preferences.first().clothesMentionMode shouldBe ClothesMentionMode.ALWAYS

        subject.setClothesMentionMode(ClothesMentionMode.IF_CHANGED)
        subject.preferences.first().clothesMentionMode shouldBe ClothesMentionMode.IF_CHANGED

        subject.setClothesMentionMode(ClothesMentionMode.NEVER)
        subject.preferences.first().clothesMentionMode shouldBe ClothesMentionMode.NEVER

        subject.setClothesMentionMode(ClothesMentionMode.ALWAYS)
        subject.preferences.first().clothesMentionMode shouldBe ClothesMentionMode.ALWAYS
    }

    @Test
    fun `umbrella migration materialises a rule for an opted-in user and drops the raw key`() = runTest {
        // A user who had opted into the retired rain_accessory = UMBRELLA toggle
        // before it became a clothes rule. The migration materialises an umbrella
        // ClothesRule keyed on a precipitation-probability condition (appended to
        // the stored defaults) and drops the raw rain_accessory key.
        val rainAccessoryKey = stringPreferencesKey("rain_accessory")
        val before = mutablePreferencesOf(rainAccessoryKey to "UMBRELLA")

        val result = umbrellaRuleMigration().migrate(before)

        val rules = decodeStoredRules(result[clothesRulesKey])
        val umbrellaRule = rules.find { it.item == Garment.UMBRELLA }
        umbrellaRule shouldNotBe null
        (umbrellaRule!!.condition is ClothesRule.PrecipitationProbabilityAbove) shouldBe true
        result[rainAccessoryKey] shouldBe null
    }

    @Test
    fun `umbrella migration adds no umbrella rule when rain_accessory was never set`() = runTest {
        // A user who never opted in: no umbrella rule is materialised, and the
        // stored clothes-rules list is left untouched (null → read DEFAULTS).
        val result = umbrellaRuleMigration().migrate(emptyPreferences())

        result[clothesRulesKey] shouldBe null
    }

    @Test
    fun `umbrella migration re-runs for users migrated under the v1 sentinel`() = runTest {
        // The v1->v2 bump: a user who migrated while the Format toggle still
        // existed (v1 set) must be re-evaluated, so a rain_accessory written
        // after v1 fired isn't stranded once the key stops being read.
        val v1 = booleanPreferencesKey("umbrella_rule_migrated_v1")
        umbrellaRuleMigration().shouldMigrate(mutablePreferencesOf(v1 to true)) shouldBe true
    }

    @Test
    fun `umbrella migration runs once, gated by its v2 sentinel`() = runTest {
        val v2 = booleanPreferencesKey("umbrella_rule_migrated_v2")
        umbrellaRuleMigration().shouldMigrate(mutablePreferencesOf(v2 to true)) shouldBe false
    }

    @Test
    fun `umbrella migration rescues a rain_accessory opt-in stranded after the v1 run`() = runTest {
        // Intermediate build: v1 already fired, then the still-present Format
        // toggle wrote rain_accessory = UMBRELLA. The v2 re-run converts it.
        val v1 = booleanPreferencesKey("umbrella_rule_migrated_v1")
        val rainAccessoryKey = stringPreferencesKey("rain_accessory")
        val before = mutablePreferencesOf(v1 to true, rainAccessoryKey to "UMBRELLA")

        val result = umbrellaRuleMigration().migrate(before)

        val umbrellaRule = decodeStoredRules(result[clothesRulesKey]).find { it.item == Garment.UMBRELLA }
        umbrellaRule shouldNotBe null
        result[rainAccessoryKey] shouldBe null
    }

    @Test
    fun `periodPreamble defaults to ALWAYS and round-trips all values`() = runTest {
        subject.preferences.first().periodPreamble shouldBe PreambleVisibility.ALWAYS

        subject.setPeriodPreamble(PreambleVisibility.SPEECH_ONLY)
        subject.preferences.first().periodPreamble shouldBe PreambleVisibility.SPEECH_ONLY

        subject.setPeriodPreamble(PreambleVisibility.NEVER)
        subject.preferences.first().periodPreamble shouldBe PreambleVisibility.NEVER
    }

    @Test
    fun `wearPreamble defaults to ALWAYS and round-trips all values`() = runTest {
        subject.preferences.first().wearPreamble shouldBe PreambleVisibility.ALWAYS

        subject.setWearPreamble(PreambleVisibility.SPEECH_ONLY)
        subject.preferences.first().wearPreamble shouldBe PreambleVisibility.SPEECH_ONLY

        subject.setWearPreamble(PreambleVisibility.NEVER)
        subject.preferences.first().wearPreamble shouldBe PreambleVisibility.NEVER
    }

    @Test
    fun `bottomsFormat defaults to IF_GARMENTS and round-trips all values`() = runTest {
        subject.preferences.first().bottomsFormat shouldBe BottomsFormat.IF_GARMENTS

        subject.setBottomsFormat(BottomsFormat.ALWAYS)
        subject.preferences.first().bottomsFormat shouldBe BottomsFormat.ALWAYS

        subject.setBottomsFormat(BottomsFormat.NEVER)
        subject.preferences.first().bottomsFormat shouldBe BottomsFormat.NEVER

        subject.setBottomsFormat(BottomsFormat.IF_GARMENTS)
        subject.preferences.first().bottomsFormat shouldBe BottomsFormat.IF_GARMENTS
    }

    @Test
    fun `deltaFormat defaults to DEGREES and round-trips all values`() = runTest {
        subject.preferences.first().deltaFormat shouldBe DeltaFormat.DEGREES

        subject.setDeltaFormat(DeltaFormat.BANDS)
        subject.preferences.first().deltaFormat shouldBe DeltaFormat.BANDS

        subject.setDeltaFormat(DeltaFormat.DEGREES)
        subject.preferences.first().deltaFormat shouldBe DeltaFormat.DEGREES
    }

    @Test
    fun `tonightNotifyOnlyOnEvents defaults to false and round-trips`() = runTest {
        subject.preferences.first().tonightNotifyOnlyOnEvents shouldBe false

        subject.setTonightNotifyOnlyOnEvents(true)
        subject.preferences.first().tonightNotifyOnlyOnEvents shouldBe true

        subject.setTonightNotifyOnlyOnEvents(false)
        subject.preferences.first().tonightNotifyOnlyOnEvents shouldBe false
    }

    @Test
    fun `Location countryCode round-trips on weather pin`() = runTest {
        val weather = Location(
            latitude = -33.8688,
            longitude = 151.2093,
            displayName = "Sydney",
            countryCode = "AU",
        )
        subject.setLocation(weather)
        // Read coarsens to a ~1km grid — see Location.coarsened().
        subject.preferences.first().location shouldBe weather.coarsened()
    }

    @Test
    fun `holidayCountrySelection defaults to home+current+global+funny on with all off`() = runTest {
        val selection = subject.preferences.first().holidayCountrySelection
        selection.home shouldBe true
        selection.current shouldBe true
        selection.global shouldBe true
        selection.funny shouldBe true
        selection.all shouldBe false
        selection.countryOverrides shouldBe emptyMap()
    }

    @Test
    fun `holiday country picker flags round-trip independently`() = runTest {
        subject.setHolidayCountryHome(false)
        subject.preferences.first().holidayCountrySelection.home shouldBe false

        subject.setHolidayCountryCurrent(false)
        subject.preferences.first().holidayCountrySelection.current shouldBe false

        subject.setHolidayCountryGlobal(false)
        subject.preferences.first().holidayCountrySelection.global shouldBe false

        subject.setHolidayCountryFunny(false)
        subject.preferences.first().holidayCountrySelection.funny shouldBe false

        subject.setHolidayCountryAll(true)
        subject.preferences.first().holidayCountrySelection.all shouldBe true
    }

    @Test
    fun `per-country overrides round-trip with uppercase normalisation`() = runTest {
        subject.preferences.first().holidayCountrySelection.countryOverrides shouldBe emptyMap()

        subject.setHolidayCountryOverride("fr", HolidayOverride.ON)
        subject.preferences.first().holidayCountrySelection.countryOverrides shouldBe
            mapOf("FR" to HolidayOverride.ON)

        subject.setHolidayCountryOverride("DE", HolidayOverride.OFF)
        subject.preferences.first().holidayCountrySelection.countryOverrides shouldBe
            mapOf("FR" to HolidayOverride.ON, "DE" to HolidayOverride.OFF)

        // AUTO clears the override (sparse storage — missing means AUTO).
        subject.setHolidayCountryOverride("FR", HolidayOverride.AUTO)
        subject.preferences.first().holidayCountrySelection.countryOverrides shouldBe
            mapOf("DE" to HolidayOverride.OFF)
    }

    @Test
    fun `holidayOverrides defaults to empty and round-trips per holiday`() = runTest {
        val id = HolidayId.CHRISTMAS_DAY
        subject.preferences.first().holidayOverrides shouldBe emptyMap()

        subject.setHolidayOverride(id, HolidayOverride.ON)
        subject.preferences.first().holidayOverrides shouldBe mapOf(id to HolidayOverride.ON)

        subject.setHolidayOverride(id, HolidayOverride.OFF)
        subject.preferences.first().holidayOverrides shouldBe mapOf(id to HolidayOverride.OFF)

        // AUTO clears the override (sparse storage — missing means AUTO).
        subject.setHolidayOverride(id, HolidayOverride.AUTO)
        subject.preferences.first().holidayOverrides shouldBe emptyMap()
    }

    @Test
    fun `region defaults to SYSTEM when nothing stored`() = runTest {
        subject.preferences.first().region shouldBe Region.SYSTEM
    }

    @Test
    fun `setRegion round-trips`() = runTest {
        subject.setRegion(Region.EN_US)
        subject.preferences.first().region shouldBe Region.EN_US
    }

    @Test
    fun `temperature default follows region locale - en-US picks Fahrenheit`() = runTest {
        subject.setRegion(Region.EN_US)
        subject.preferences.first().temperatureUnit shouldBe TemperatureUnit.FAHRENHEIT
    }

    @Test
    fun `distance default follows region locale - en-US picks miles`() = runTest {
        subject.setRegion(Region.EN_US)
        subject.preferences.first().distanceUnit shouldBe DistanceUnit.MILES
    }

    @Test
    fun `temperature default falls back to system locale when region is SYSTEM`() = runTest {
        // setUp pins systemLocaleProvider to Locale.UK → Celsius + miles.
        subject.preferences.first().temperatureUnit shouldBe TemperatureUnit.CELSIUS
        subject.preferences.first().distanceUnit shouldBe DistanceUnit.MILES
    }

    @Test
    fun `distance default follows region locale - en-GB picks miles`() = runTest {
        subject.setRegion(Region.EN_GB)
        subject.preferences.first().distanceUnit shouldBe DistanceUnit.MILES
    }

    @Test
    fun `temperature default follows region locale - en-GB picks Celsius`() = runTest {
        subject.setRegion(Region.EN_GB)
        subject.preferences.first().temperatureUnit shouldBe TemperatureUnit.CELSIUS
    }

    @Test
    fun `explicitly chosen unit overrides the region-derived default`() = runTest {
        subject.setRegion(Region.EN_US)
        subject.setTemperatureUnitSetting(TemperatureUnitSetting.CELSIUS)
        subject.setDistanceUnitSetting(DistanceUnitSetting.KILOMETERS)

        val prefs = subject.preferences.first()
        prefs.temperatureUnit shouldBe TemperatureUnit.CELSIUS
        prefs.distanceUnit shouldBe DistanceUnit.KILOMETERS
        prefs.temperatureUnitSetting shouldBe TemperatureUnitSetting.CELSIUS
        prefs.distanceUnitSetting shouldBe DistanceUnitSetting.KILOMETERS
    }

    @Test
    fun `setUnits round-trips both`() = runTest {
        subject.setTemperatureUnitSetting(TemperatureUnitSetting.FAHRENHEIT)
        subject.setDistanceUnitSetting(DistanceUnitSetting.MILES)

        val prefs = subject.preferences.first()
        prefs.temperatureUnit shouldBe TemperatureUnit.FAHRENHEIT
        prefs.distanceUnit shouldBe DistanceUnit.MILES
        prefs.temperatureUnitSetting shouldBe TemperatureUnitSetting.FAHRENHEIT
        prefs.distanceUnitSetting shouldBe DistanceUnitSetting.MILES
    }

    @Test
    fun `AUTO re-derives from locale after explicit choice`() = runTest {
        subject.setRegion(Region.EN_US)
        subject.setTemperatureUnitSetting(TemperatureUnitSetting.CELSIUS)
        subject.setDistanceUnitSetting(DistanceUnitSetting.KILOMETERS)
        // Reset to AUTO — should re-derive from en-US → Fahrenheit + miles.
        subject.setTemperatureUnitSetting(TemperatureUnitSetting.AUTO)
        subject.setDistanceUnitSetting(DistanceUnitSetting.AUTO)

        val prefs = subject.preferences.first()
        prefs.temperatureUnitSetting shouldBe TemperatureUnitSetting.AUTO
        prefs.distanceUnitSetting shouldBe DistanceUnitSetting.AUTO
        prefs.temperatureUnit shouldBe TemperatureUnit.FAHRENHEIT
        prefs.distanceUnit shouldBe DistanceUnit.MILES
    }

    @Test
    fun `setClothesRules round-trips all three condition types`() = runTest {
        val rules = listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(5.0)),
            ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(28.5)),
            ClothesRule(Garment.JACKET, ClothesRule.PrecipitationProbabilityAbove(40.0)),
        )

        subject.setClothesRules(rules)

        subject.preferences.first().clothesRules shouldContainExactly rules
    }

    @Test
    fun `legacy free-form rules are dropped on load`() = runTest {
        // Stored JSON from before the Garment catalog may carry free-form items
        // ("cardigan") that aren't in the catalog. item is typed now, so the
        // repository drops them on read rather than surfacing untyped rules.
        dataStore.edit {
            it[stringPreferencesKey("clothes_rules_json")] = """
                [{"item":"cardigan","type":"temp_below","value":15.0},
                 {"item":"sweater","type":"temp_below","value":18.0}]
            """.trimIndent()
        }

        subject.preferences.first().clothesRules shouldContainExactly listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0)),
        )
    }

    @Test
    fun `setClothesRules round-trips a fahrenheit-typed threshold`() = runTest {
        // The rule remembers what the user typed (65°F), not a converted Celsius
        // approximation — switching unit at display time is reversible.
        val rules = listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(65.0, TemperatureUnit.FAHRENHEIT)),
            ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(80.0, TemperatureUnit.FAHRENHEIT)),
        )

        subject.setClothesRules(rules)

        subject.preferences.first().clothesRules shouldContainExactly rules
    }

    @Test
    fun `legacy clothes-rules JSON without a unit field deserialises as celsius`() = runTest {
        // Pre-unit-aware app versions wrote `{ "item": ..., "type": ..., "value": ... }`
        // with no `unit`. The DTO must still parse them, and the resulting rule
        // must behave exactly as it did before the unit field existed.
        dataStore.edit {
            it[stringPreferencesKey("clothes_rules_json")] = """
                [{"item":"sweater","type":"temp_below","value":18.0},
                 {"item":"shorts","type":"temp_above","value":24.0}]
            """.trimIndent()
        }

        val rules = subject.preferences.first().clothesRules
        rules shouldContainExactly listOf(
            ClothesRule(Garment.SWEATER, ClothesRule.TemperatureBelow(18.0, TemperatureUnit.CELSIUS)),
            ClothesRule(Garment.SHORTS, ClothesRule.TemperatureAbove(24.0, TemperatureUnit.CELSIUS)),
        )
    }

    @Test
    fun `setClothesRules with empty list reads back as defaults`() = runTest {
        // An empty stored list — whether set deliberately or left over from the
        // editable-UI era when a user deleted all their rules — is treated as
        // "no rules configured" and resolves to DEFAULTS at read time. Editing
        // is locked (ClothesSettings is read-only), so otherwise such a user
        // would have no way to recover the defaults.
        subject.setClothesRules(emptyList())

        subject.preferences.first().clothesRules shouldBe ClothesRule.DEFAULTS
    }

    @Test
    fun `corrupt clothes rules JSON falls back to defaults`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("clothes_rules_json")] = "{not valid json["
        }

        subject.preferences.first().clothesRules shouldBe ClothesRule.DEFAULTS
    }

    @Test
    fun `defaultBottom defaults to LONG_PANTS and round-trips`() = runTest {
        subject.preferences.first().defaultBottom shouldBe OutfitSuggestion.Bottom.LONG_PANTS

        subject.setDefaultBottom(OutfitSuggestion.Bottom.JEANS)
        subject.preferences.first().defaultBottom shouldBe OutfitSuggestion.Bottom.JEANS

        subject.setDefaultBottom(OutfitSuggestion.Bottom.LONG_PANTS)
        subject.preferences.first().defaultBottom shouldBe OutfitSuggestion.Bottom.LONG_PANTS
    }

    @Test
    fun `defaultBottom round-trips all Bottom variants and clamps unknown to LONG_PANTS`() = runTest {
        // With the "If no rules match" framing, every Bottom variant is a
        // legitimate fallback — including SHORTS, for users in hot climates
        // who removed the shorts rule. Only truly unknown stored values
        // (forward-compat from a future build, hand-edited DataStore) degrade
        // to LONG_PANTS so the icon doesn't render a null.
        subject.setDefaultBottom(OutfitSuggestion.Bottom.LONG_SKIRT)
        subject.preferences.first().defaultBottom shouldBe OutfitSuggestion.Bottom.LONG_SKIRT

        subject.setDefaultBottom(OutfitSuggestion.Bottom.SHORTS)
        subject.preferences.first().defaultBottom shouldBe OutfitSuggestion.Bottom.SHORTS

        dataStore.edit { it[stringPreferencesKey("default_bottom")] = "BOGUS" }
        subject.preferences.first().defaultBottom shouldBe OutfitSuggestion.Bottom.LONG_PANTS
    }

    @Test
    fun `defaultTop defaults to TSHIRT and round-trips`() = runTest {
        subject.preferences.first().defaultTop shouldBe OutfitSuggestion.Top.TSHIRT

        subject.setDefaultTop(OutfitSuggestion.Top.POLO)
        subject.preferences.first().defaultTop shouldBe OutfitSuggestion.Top.POLO

        subject.setDefaultTop(OutfitSuggestion.Top.SWEATER)
        subject.preferences.first().defaultTop shouldBe OutfitSuggestion.Top.SWEATER
    }

    @Test
    fun `defaultTop clamps unknown stored values back to TSHIRT`() = runTest {
        dataStore.edit { it[stringPreferencesKey("default_top")] = "BOGUS" }
        subject.preferences.first().defaultTop shouldBe OutfitSuggestion.Top.TSHIRT
    }

    @Test
    fun `unknown enum names fall back to defaults`() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("delivery_mode")] = "UNKNOWN_MODE"
            prefs[stringPreferencesKey("temperature_unit")] = "KELVIN"
            prefs[stringSetPreferencesKey("schedule_days")] = setOf("FUNDAY")
        }

        val prefs = subject.preferences.first()
        prefs.deliveryMode shouldBe DeliveryMode.NOTIFICATION_AND_TTS
        prefs.temperatureUnit shouldBe TemperatureUnit.CELSIUS
        prefs.schedule.days shouldBe Schedule.EVERY_DAY
    }

    @Test
    fun `outfit colour overrides default to empty maps`() = runTest {
        val prefs = subject.preferences.first()
        prefs.outfitTopColors shouldBe emptyMap()
        prefs.outfitBottomColors shouldBe emptyMap()
        prefs.outfitHandsColors shouldBe emptyMap()
        prefs.outfitCarriedColors shouldBe emptyMap()
    }

    @Test
    fun `setOutfitHandsColor round-trips a fill and clears with null`() = runTest {
        subject.setOutfitHandsColor(OutfitSuggestion.Hands.GLOVES, 0xFFE53935L)
        subject.preferences.first().outfitHandsColors[OutfitSuggestion.Hands.GLOVES] shouldBe 0xFFE53935L

        subject.setOutfitHandsColor(OutfitSuggestion.Hands.GLOVES, null)
        subject.preferences.first().outfitHandsColors.containsKey(OutfitSuggestion.Hands.GLOVES) shouldBe false
    }

    @Test
    fun `setOutfitCarriedColor round-trips a fill and clears with null`() = runTest {
        subject.setOutfitCarriedColor(OutfitSuggestion.Carried.UMBRELLA, 0xFF1E88E5L)
        subject.preferences.first().outfitCarriedColors[OutfitSuggestion.Carried.UMBRELLA] shouldBe 0xFF1E88E5L

        subject.setOutfitCarriedColor(OutfitSuggestion.Carried.UMBRELLA, null)
        subject.preferences.first().outfitCarriedColors.containsKey(OutfitSuggestion.Carried.UMBRELLA) shouldBe false
    }

    @Test
    fun `setOutfitTopColor round-trips a fill and clears with null`() = runTest {
        subject.setOutfitTopColor(OutfitSuggestion.Top.SWEATER, 0xFFE53935L)
        subject.setOutfitTopColor(OutfitSuggestion.Top.TSHIRT, 0xFF1E88E5L)
        val withTwo = subject.preferences.first().outfitTopColors
        withTwo[OutfitSuggestion.Top.SWEATER] shouldBe 0xFFE53935L
        withTwo[OutfitSuggestion.Top.TSHIRT] shouldBe 0xFF1E88E5L

        // Clearing one entry leaves the other intact — the partial update path
        // is what the picker's "Default" tile relies on.
        subject.setOutfitTopColor(OutfitSuggestion.Top.SWEATER, null)
        val afterClear = subject.preferences.first().outfitTopColors
        afterClear.containsKey(OutfitSuggestion.Top.SWEATER) shouldBe false
        afterClear[OutfitSuggestion.Top.TSHIRT] shouldBe 0xFF1E88E5L
    }

    @Test
    fun `setOutfitBottomColor round-trips`() = runTest {
        subject.setOutfitBottomColor(OutfitSuggestion.Bottom.JEANS, 0xFF7CB342L)
        subject.preferences.first().outfitBottomColors[OutfitSuggestion.Bottom.JEANS] shouldBe 0xFF7CB342L
    }

    @Test
    fun `outfit colours tolerate unknown enum names in stored JSON`() = runTest {
        // Forward-compat — a future build that adds a new Top variant and
        // writes its colour shouldn't crash earlier installs that read it
        // back. The unknown entry silently disappears; known entries survive.
        // Numbers are positive Longs (the writer normalises ARGB ints into
        // the unsigned-Long range so JSON output stays human-readable).
        dataStore.edit {
            it[stringPreferencesKey("outfit_top_colors_json")] =
                """{"SWEATER":4294901760,"FUTURE_VARIANT":4278255360}"""
        }
        val prefs = subject.preferences.first()
        prefs.outfitTopColors[OutfitSuggestion.Top.SWEATER] shouldBe 0xFFFF0000L
        prefs.outfitTopColors.size shouldBe 1
    }

    @Test
    fun `corrupt outfit colours JSON falls back to empty`() = runTest {
        dataStore.edit { it[stringPreferencesKey("outfit_top_colors_json")] = "not json" }
        subject.preferences.first().outfitTopColors shouldBe emptyMap()
    }

    @Test
    fun `gemini voice setter round-trips`() = runTest {
        subject.setGeminiVoice("Puck")
        subject.preferences.first().geminiVoice shouldBe "Puck"
    }

    @Test
    fun `gemini voice defaults to Despina when nothing stored`() = runTest {
        subject.preferences.first().geminiVoice shouldBe "Despina"
    }

    @Test
    fun `tts style defaults to NORMAL when nothing stored`() = runTest {
        subject.preferences.first().ttsStyle shouldBe TtsStyle.WEATHER_FORECASTER
    }

    @Test
    fun `tts style setter round-trips`() = runTest {
        // Cover every TtsStyle value so that adding a new entry (or
        // dropping one) without keeping the persistence path in sync
        // shows up here. DataStore stores the enum name, so this is
        // effectively a contract test for the name → enum decode path.
        for (style in TtsStyle.entries) {
            subject.setTtsStyle(style)
            subject.preferences.first().ttsStyle shouldBe style
        }
    }

    @Test
    fun `device voice defaults to null and round-trips`() = runTest {
        subject.preferences.first().deviceVoice shouldBe null

        subject.setDeviceVoice("en-us-x-tpc-network")
        subject.preferences.first().deviceVoice shouldBe "en-us-x-tpc-network"

        // Null clears the pin; preferences flow stops emitting the stored value
        // and the speaker reverts to auto-pick.
        subject.setDeviceVoice(null)
        subject.preferences.first().deviceVoice shouldBe null
    }

    @Test
    fun `device voice setter treats blank as clear`() = runTest {
        subject.setDeviceVoice("en-us-x-tpc-network")
        subject.setDeviceVoice("")
        subject.preferences.first().deviceVoice shouldBe null
    }

    @Test
    fun `setTtsEngineIfUnset writes when nothing is stored`() = runTest {
        subject.preferences.first().ttsEngine shouldBe TtsEngine.DEVICE

        subject.setTtsEngineIfUnset(TtsEngine.GEMINI)
        subject.preferences.first().ttsEngine shouldBe TtsEngine.GEMINI
    }

    @Test
    fun `setTtsEngineIfUnset does not clobber an explicit choice`() = runTest {
        subject.setTtsEngine(TtsEngine.GEMINI)

        subject.setTtsEngineIfUnset(TtsEngine.DEVICE)
        subject.preferences.first().ttsEngine shouldBe TtsEngine.GEMINI
    }

    @Test
    fun `setTtsEngineIfUnset does not clobber an explicit DEVICE choice`() = runTest {
        // The default is also DEVICE, so explicitly picking DEVICE has to
        // count as "set" — otherwise re-running onboarding would silently
        // promote a deliberately-DEVICE user to Gemini.
        subject.setTtsEngine(TtsEngine.DEVICE)

        subject.setTtsEngineIfUnset(TtsEngine.GEMINI)
        subject.preferences.first().ttsEngine shouldBe TtsEngine.DEVICE
    }

    @Test
    fun `useCalendarEvents defaults to false and round-trips`() = runTest {
        subject.preferences.first().useCalendarEvents shouldBe false

        subject.setUseCalendarEvents(true)
        subject.preferences.first().useCalendarEvents shouldBe true

        subject.setUseCalendarEvents(false)
        subject.preferences.first().useCalendarEvents shouldBe false
    }

    @Test
    fun `dismissedUpdateVersion defaults to 0 and round-trips`() = runTest {
        // Default 0 means "never dismissed" — any non-zero availableVersionCode
        // from Play surfaces the update banner. Storing the dismissed version
        // (rather than a boolean) means a still-newer release re-surfaces the
        // banner automatically.
        subject.dismissedUpdateVersion.first() shouldBe 0

        subject.setDismissedUpdateVersion(72)
        subject.dismissedUpdateVersion.first() shouldBe 72

        subject.setDismissedUpdateVersion(99)
        subject.dismissedUpdateVersion.first() shouldBe 99
    }

    @Test
    fun `telemetryEnabled defaults to true and round-trips`() = runTest {
        // Default-on so the long tail of installs report crashes without the
        // user having to find the toggle. The Today banner exists to make
        // this default visible; flipping the switch persists across launches.
        subject.preferences.first().telemetryEnabled shouldBe true

        subject.setTelemetryEnabled(false)
        subject.preferences.first().telemetryEnabled shouldBe false

        subject.setTelemetryEnabled(true)
        subject.preferences.first().telemetryEnabled shouldBe true
    }

    @Test
    fun `colorPalette defaults to RAINBOW and round-trips`() = runTest {
        // Default RAINBOW — the existing pink/orange/green chart palette
        // and Material teal/red confidence chips stay in place for everyone
        // who hasn't opted in via Display settings.
        subject.preferences.first().colorPalette shouldBe ColorPalette.RAINBOW

        subject.setColorPalette(ColorPalette.ACCESSIBLE)
        subject.preferences.first().colorPalette shouldBe ColorPalette.ACCESSIBLE

        subject.setColorPalette(ColorPalette.RAINBOW)
        subject.preferences.first().colorPalette shouldBe ColorPalette.RAINBOW
    }

    @Test
    fun `telemetryNoticeAcked defaults to false and round-trips`() = runTest {
        // Default false so the one-time disclosure banner surfaces on first
        // launch. Acked separately from telemetryEnabled so a user who turns
        // telemetry off and on again doesn't see the disclosure twice.
        subject.preferences.first().telemetryNoticeAcked shouldBe false

        subject.setTelemetryNoticeAcked(true)
        subject.preferences.first().telemetryNoticeAcked shouldBe true
    }

    @Test
    fun `dismissedLocalBuildSha defaults to empty and round-trips`() = runTest {
        subject.dismissedLocalBuildSha.first() shouldBe ""

        subject.setDismissedLocalBuildSha("abc1234")
        subject.dismissedLocalBuildSha.first() shouldBe "abc1234"

        subject.setDismissedLocalBuildSha("def5678")
        subject.dismissedLocalBuildSha.first() shouldBe "def5678"
    }

    @Test
    fun `bugReportConsentAcknowledged defaults to false and round-trips`() = runTest {
        // Default false so a fresh install always shows the consent dialog
        // before the first share. A round-trip back to false covers the
        // (currently UI-less) "let me see the disclosure again" path so
        // the persistence layer doesn't lock anyone out if a future build
        // exposes a reset toggle.
        subject.bugReportConsentAcknowledged.first() shouldBe false

        subject.setBugReportConsentAcknowledged(true)
        subject.bugReportConsentAcknowledged.first() shouldBe true

        subject.setBugReportConsentAcknowledged(false)
        subject.bugReportConsentAcknowledged.first() shouldBe false
    }

    @Test
    fun `analyticsSnapshot reports defaults and UNSET when nothing is stored`() = runTest {
        // Pinned systemLocaleProvider is Locale.UK → BCP-47 "en-GB". With no
        // stored keys, both SYSTEM sentinels resolve to that for the
        // *effective* values, but every override field reads UNSET so reports
        // can tell "user never touched it" apart from "user picked SYSTEM /
        // the default value explicitly".
        val snap = subject.analyticsSnapshot.first()

        snap.regionDefault shouldBe "en-GB"
        snap.regionOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.regionEffective shouldBe "en-GB"
        snap.voiceLocaleDefault shouldBe "en-GB"
        snap.voiceLocaleOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.voiceLocaleEffective shouldBe "en-GB"
        snap.ttsEngineDefault shouldBe TtsEngine.DEVICE.name
        snap.ttsEngineOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.ttsEngineEffective shouldBe TtsEngine.DEVICE.name
        snap.ttsStyleDefault shouldBe TtsStyle.WEATHER_FORECASTER.name
        snap.ttsStyleOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.ttsStyleEffective shouldBe TtsStyle.WEATHER_FORECASTER.name
        snap.geminiVoiceDefault shouldBe "Despina"
        snap.geminiVoiceOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.geminiVoiceEffective shouldBe "Despina"
        snap.deviceVoiceDefault shouldBe SettingsAnalyticsSnapshot.AUTO
        snap.deviceVoiceOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.deviceVoiceEffective shouldBe SettingsAnalyticsSnapshot.AUTO
    }

    @Test
    fun `analyticsSnapshot reflects each setting's override and effective`() = runTest {
        subject.setRegion(Region.EN_US)
        subject.setVoiceLocale(VoiceLocale.EN_AU)
        subject.setTtsEngine(TtsEngine.GEMINI)
        subject.setTtsStyle(TtsStyle.PIRATE)
        subject.setGeminiVoice("Puck")
        subject.setDeviceVoice("en-us-x-tpc-network")

        val snap = subject.analyticsSnapshot.first()

        // System locale is unchanged by region overrides — region default still
        // tracks the device locale, not the picked region.
        snap.regionDefault shouldBe "en-GB"
        snap.regionOverride shouldBe Region.EN_US.name
        snap.regionEffective shouldBe "en-US"
        // VoiceLocale's "default" follows the region locale, so picking a
        // region shifts the SYSTEM-fallback baseline even though the user
        // overrode VoiceLocale to something else.
        snap.voiceLocaleDefault shouldBe "en-US"
        snap.voiceLocaleOverride shouldBe VoiceLocale.EN_AU.name
        snap.voiceLocaleEffective shouldBe "en-AU"
        snap.ttsEngineOverride shouldBe TtsEngine.GEMINI.name
        snap.ttsEngineEffective shouldBe TtsEngine.GEMINI.name
        snap.ttsStyleOverride shouldBe TtsStyle.PIRATE.name
        snap.ttsStyleEffective shouldBe TtsStyle.PIRATE.name
        snap.geminiVoiceOverride shouldBe "Puck"
        snap.geminiVoiceEffective shouldBe "Puck"
        snap.deviceVoiceOverride shouldBe "en-us-x-tpc-network"
        snap.deviceVoiceEffective shouldBe "en-us-x-tpc-network"
    }

    @Test
    fun `analyticsSnapshot voiceLocale default tracks region override`() = runTest {
        // VoiceLocale.SYSTEM falls back to the *region* locale, not the
        // system locale — so changing the region also changes the baseline
        // an unset voice locale resolves to. Voice locale itself is UNSET
        // here because the user only touched the region picker.
        subject.setRegion(Region.EN_US)

        val snap = subject.analyticsSnapshot.first()
        snap.voiceLocaleOverride shouldBe SettingsAnalyticsSnapshot.UNSET
        snap.voiceLocaleDefault shouldBe "en-US"
        snap.voiceLocaleEffective shouldBe "en-US"
    }

    @Test
    fun `analyticsSnapshot SYSTEM override is distinct from UNSET`() = runTest {
        // Persisting Region.SYSTEM / VoiceLocale.SYSTEM is observably a
        // different story from never having touched the picker. The override
        // field surfaces that distinction: "user actively kept the default" vs
        // "default fell out as SYSTEM". An UNSET-vs-SYSTEM breakdown is the
        // whole point of this snapshot, so this is contract-level.
        subject.setRegion(Region.EN_US)
        subject.setRegion(Region.SYSTEM)
        subject.setVoiceLocale(VoiceLocale.EN_AU)
        subject.setVoiceLocale(VoiceLocale.SYSTEM)

        val snap = subject.analyticsSnapshot.first()
        snap.regionOverride shouldBe Region.SYSTEM.name
        snap.regionEffective shouldBe "en-GB"
        snap.voiceLocaleOverride shouldBe VoiceLocale.SYSTEM.name
        snap.voiceLocaleEffective shouldBe "en-GB"
    }

    @Test
    fun `settingsSnapshot reflects effective values and hour-bucketed schedule`() = runTest {
        // Pinned systemLocaleProvider is Locale.UK → temperature CELSIUS,
        // distance MILES (UK uses miles for everyday distances per
        // defaultDistanceUnitFor). Defaults give NOTIFICATION_AND_TTS for both
        // slots and 07:00 / 19:00 every day.
        val defaults = subject.settingsSnapshot.first()

        defaults.temperatureUnitSetting shouldBe TemperatureUnitSetting.AUTO.name
        defaults.temperatureUnitEffective shouldBe TemperatureUnit.CELSIUS.name
        defaults.distanceUnitSetting shouldBe DistanceUnitSetting.AUTO.name
        defaults.distanceUnitEffective shouldBe DistanceUnit.MILES.name
        defaults.deliveryModeDaily shouldBe DeliveryMode.NOTIFICATION_AND_TTS.name
        defaults.deliveryModeTonight shouldBe DeliveryMode.NOTIFICATION_AND_TTS.name
        defaults.themeMode shouldBe ThemeMode.SYSTEM.name
        // Both slots are opt-in out of the box; the user enables a slot, and the
        // notification permission, from the schedule page. The 07:00 / 19:00
        // every-day times are the slots' effective values once turned on.
        defaults.dailyEnabled shouldBe false
        defaults.dailyTimeBucketHour shouldBe "07"
        defaults.dailyDaysCount shouldBe 7
        defaults.tonightTimeBucketHour shouldBe "19"
        defaults.tonightDaysCount shouldBe 7
        defaults.tonightEnabled shouldBe false
        defaults.tonightNotifyOnlyOnEvents shouldBe false
        defaults.dailyMentionEveningEvents shouldBe false
        defaults.useCalendarEvents shouldBe false

        subject.setSchedule(
            time = LocalTime.of(6, 30),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        )
        subject.setTemperatureUnitSetting(TemperatureUnitSetting.FAHRENHEIT)
        subject.setDeliveryMode(DeliveryMode.NOTIFICATION_ONLY)
        subject.setUseCalendarEvents(true)

        val edited = subject.settingsSnapshot.first()

        // Hour bucket drops the minutes — 06:30 reports as "06" so reports
        // don't see exact-time precision that could fingerprint a schedule.
        edited.dailyTimeBucketHour shouldBe "06"
        edited.dailyDaysCount shouldBe 3
        edited.temperatureUnitSetting shouldBe TemperatureUnitSetting.FAHRENHEIT.name
        edited.temperatureUnitEffective shouldBe TemperatureUnit.FAHRENHEIT.name
        edited.deliveryModeDaily shouldBe DeliveryMode.NOTIFICATION_ONLY.name
        edited.useCalendarEvents shouldBe true
    }

    @Test
    fun `clothesRulesSnapshot reflects user edits to thresholds`() = runTest {
        // Nudge jacket from default 10°C down to 8°C — delta should report
        // as "-2" and the customised count should land at exactly 1.
        val edited = ClothesRule.DEFAULTS.map { rule ->
            if (rule.item.itemKey == "jacket") rule.copy(condition = ClothesRule.TemperatureBelow(8.0))
            else rule
        }
        subject.setClothesRules(edited)

        val snap = subject.clothesRulesSnapshot.first()

        snap.customisedCount shouldBe 1
        snap.jacketDeltaC shouldBe "-2"
        snap.categoriesCustomised shouldBe "jacket"
        snap.allDefaults shouldBe false
    }

    @Test
    fun `clothesRulesSnapshot defaults report all zeros and allDefaults true`() = runTest {
        // Untouched repository — falls back to ClothesRule.DEFAULTS, which the
        // snapshot must report as completely unmodified.
        val snap = subject.clothesRulesSnapshot.first()

        snap.allDefaults shouldBe true
        snap.customisedCount shouldBe 0
        snap.extraRulesCount shouldBe 0
        snap.sweaterDeltaC shouldBe "0"
        snap.jacketDeltaC shouldBe "0"
        snap.coatDeltaC shouldBe "0"
        snap.glovesDeltaC shouldBe "0"
        snap.shortsDeltaC shouldBe "0"
        snap.categoriesCustomised shouldBe ""
    }

    @Test
    fun `clothesRulesSnapshot deleted category surfaces as MISSING`() = runTest {
        // User wiped coat from their list — every other default unchanged.
        subject.setClothesRules(ClothesRule.DEFAULTS.filter { it.item.itemKey != "coat" })

        val snap = subject.clothesRulesSnapshot.first()

        snap.coatDeltaC shouldBe ClothesRulesSnapshot.MISSING
        snap.customisedCount shouldBe 1
        snap.categoriesCustomised shouldBe "coat"
    }

    @Test
    fun `forecast models default to null on fresh install`() = runTest {
        // Auto mode: no stored key means the location-aware resolver runs
        // in [ClothesCastApplication] instead of forcing the global trio.
        // The picker shows the Auto switch as on and the per-model
        // checkboxes derive from the user's location.
        subject.preferences.first().forecastModels shouldBe null
    }

    @Test
    fun `forecast models setter round-trips arbitrary subsets`() = runTest {
        // Cover every ForecastModel value so adding or dropping an entry
        // without keeping the persistence path in sync shows up here.
        // Stored as enum names; the read-back side resolves them back.
        for (model in ForecastModel.entries) {
            val subset = ForecastModel.DEFAULTS + model
            subject.setForecastModels(subset)
            subject.preferences.first().forecastModels shouldBe subset
        }
    }

    @Test
    fun `setting forecast models to empty clears the key to Auto`() = runTest {
        // An empty set never reaches this path from the UI (the picker
        // enforces a minimum of two), but a hand-edited DataStore could.
        // The empty key gets cleared and the next read returns null —
        // i.e. the user is back in Auto mode.
        subject.setForecastModels(emptySet())
        subject.preferences.first().forecastModels shouldBe null
    }

    @Test
    fun `setting forecast models to null clears the key to Auto`() = runTest {
        // First persist an explicit set, then clear by passing null —
        // mirrors the picker's Auto-switch-on flow.
        subject.setForecastModels(setOf(ForecastModel.UKMO_SEAMLESS, ForecastModel.ECMWF_IFS04))
        subject.preferences.first().forecastModels shouldBe
            setOf(ForecastModel.UKMO_SEAMLESS, ForecastModel.ECMWF_IFS04)
        subject.setForecastModels(null)
        subject.preferences.first().forecastModels shouldBe null
    }

    @Test
    fun `forecast models tolerate unknown stored enum names`() = runTest {
        // Forward-compat: a future build that adds a new model variant
        // and writes its name shouldn't break earlier installs reading
        // it back. The unknown entry silently disappears; known entries
        // survive.
        dataStore.edit {
            it[stringSetPreferencesKey("forecast_models")] =
                setOf("ECMWF_IFS04", "FUTURE_MODEL_VARIANT", "GFS_SEAMLESS")
        }
        subject.preferences.first().forecastModels shouldBe
            setOf(ForecastModel.ECMWF_IFS04, ForecastModel.GFS_SEAMLESS)
    }

    @Test
    fun `zoneId is resolved fresh on each emission`() = runTest {
        val zones = mutableListOf(ZoneId.of("UTC"), ZoneId.of("America/New_York"))
        val rotating = SettingsRepository(
            dataStore = dataStore,
            zoneIdProvider = { zones.removeAt(0) },
        )

        rotating.preferences.first().schedule.zoneId shouldBe ZoneId.of("UTC")
        rotating.preferences.first().schedule.zoneId shouldBe ZoneId.of("America/New_York")
    }

    private val dailyEnabledKey = booleanPreferencesKey("daily_enabled")
    private val tonightEnabledKey = booleanPreferencesKey("tonight_enabled")
    private val scheduleMigratedKey = booleanPreferencesKey("schedule_default_off_migrated_v1")

    @Test
    fun `schedule migration writes nothing for a fresh empty store`() = runTest {
        val result = scheduleEnabledOptInMigration().migrate(emptyPreferences())

        // Nothing to grandfather: both slots are default-off, so a fresh install
        // needs neither key written. Only the sentinel lands so the migration
        // won't run again.
        result[dailyEnabledKey] shouldBe null
        result[tonightEnabledKey] shouldBe null
        result[scheduleMigratedKey] shouldBe true
    }

    @Test
    fun `schedule migration grandfathers the evening slot on an existing store`() = runTest {
        // A store that already holds any preference predates the evening slot
        // becoming opt-in, so the user was getting evening casts under the old
        // default-on — preserve that. The morning slot is left alone (a clean
        // opt-in reset; it reads off by default).
        val existing = mutablePreferencesOf(stringPreferencesKey("region") to "en-GB")

        val result = scheduleEnabledOptInMigration().migrate(existing)

        result[dailyEnabledKey] shouldBe null
        result[tonightEnabledKey] shouldBe true
        result[scheduleMigratedKey] shouldBe true
    }

    @Test
    fun `schedule migration preserves an explicit morning disable and grandfathers the evening`() = runTest {
        val existing = mutablePreferencesOf(dailyEnabledKey to false)

        val result = scheduleEnabledOptInMigration().migrate(existing)

        // Explicit off stays off; the never-touched tonight slot is grandfathered on.
        result[dailyEnabledKey] shouldBe false
        result[tonightEnabledKey] shouldBe true
    }

    @Test
    fun `schedule migration runs once, gated by its sentinel`() = runTest {
        val migration = scheduleEnabledOptInMigration()

        migration.shouldMigrate(emptyPreferences()) shouldBe true
        migration.shouldMigrate(mutablePreferencesOf(scheduleMigratedKey to true)) shouldBe false
    }

    // --- gloves default migration ---

    private val clothesRulesKey = stringPreferencesKey("clothes_rules_json")
    private val glovesMigratedKey = booleanPreferencesKey("gloves_default_migrated_v1")
    private val migrationJson = Json { ignoreUnknownKeys = true }

    // Decode a stored clothes-rules JSON string the same way the production read
    // path does (ClothesRuleDto.toDomain drops non-catalog keys).
    private fun decodeStoredRules(raw: String?): List<ClothesRule> =
        migrationJson.decodeFromString<List<ClothesRuleDto>>(raw!!).mapNotNull { it.toDomain() }

    // The pre-gloves default list — what an install that persisted rules before
    // this change would hold on disk.
    private val preGlovesDefaultsJson =
        """[{"item":"sweater","type":"temp_below","value":16.0,"unit":"CELSIUS"},""" +
            """{"item":"jacket","type":"temp_below","value":10.0,"unit":"CELSIUS"},""" +
            """{"item":"coat","type":"temp_below","value":4.0,"unit":"CELSIUS"},""" +
            """{"item":"shorts","type":"temp_above","value":23.0,"unit":"CELSIUS"}]"""

    @Test
    fun `gloves migration appends the gloves default to a stored list without it`() = runTest {
        val before = mutablePreferencesOf(clothesRulesKey to preGlovesDefaultsJson)

        val result = glovesDefaultMigration().migrate(before)

        val rules = decodeStoredRules(result[clothesRulesKey])
        rules.map { it.item.itemKey } shouldBe listOf("sweater", "jacket", "coat", "shorts", "gloves")
        // The appended rule tracks the catalog default (TemperatureBelow 4°C).
        rules.last() shouldBe ClothesRule.DEFAULTS.first { it.item == Garment.GLOVES }
        result[glovesMigratedKey] shouldBe true
    }

    @Test
    fun `gloves migration preserves a customised threshold and still appends gloves`() = runTest {
        // User nudged coat to 2°C; the migration must keep that and add gloves.
        val customised = """[{"item":"coat","type":"temp_below","value":2.0,"unit":"CELSIUS"}]"""
        val before = mutablePreferencesOf(clothesRulesKey to customised)

        val result = glovesDefaultMigration().migrate(before)

        val rules = decodeStoredRules(result[clothesRulesKey])
        rules.map { it.item.itemKey } shouldBe listOf("coat", "gloves")
        (rules.first().condition as ClothesRule.TemperatureBelow).value shouldBe 2.0
    }

    @Test
    fun `gloves migration does not duplicate an existing gloves rule`() = runTest {
        // A user who already added a gloves rule (at their own 0°C threshold)
        // keeps exactly that — no second default appended.
        val withGloves =
            """[{"item":"coat","type":"temp_below","value":4.0,"unit":"CELSIUS"},""" +
                """{"item":"gloves","type":"temp_below","value":0.0,"unit":"CELSIUS"}]"""
        val before = mutablePreferencesOf(clothesRulesKey to withGloves)

        val result = glovesDefaultMigration().migrate(before)

        val rules = decodeStoredRules(result[clothesRulesKey])
        rules.count { it.item == Garment.GLOVES } shouldBe 1
        (rules.first { it.item == Garment.GLOVES }.condition as ClothesRule.TemperatureBelow)
            .value shouldBe 0.0
    }

    @Test
    fun `gloves migration leaves a fresh install with no stored list untouched`() = runTest {
        val result = glovesDefaultMigration().migrate(emptyPreferences())

        // No stored key is written: parseRules reads DEFAULTS (now incl. gloves)
        // directly, so a fresh install needs nothing grandfathered.
        result[clothesRulesKey] shouldBe null
        result[glovesMigratedKey] shouldBe true
    }

    @Test
    fun `gloves migration leaves corrupt JSON untouched but sets the sentinel`() = runTest {
        val before = mutablePreferencesOf(clothesRulesKey to "{not valid json")

        val result = glovesDefaultMigration().migrate(before)

        // parseRules falls back to DEFAULTS (incl. gloves) when it can't decode,
        // so leaving the bytes as-is still gets the user gloves at read time.
        result[clothesRulesKey] shouldBe "{not valid json"
        result[glovesMigratedKey] shouldBe true
    }

    @Test
    fun `gloves migration leaves a legacy-only list untouched so defaults still apply`() = runTest {
        // A stored list that decodes but holds only legacy free-form items (no
        // catalog garment) yields no domain rules. parseRules treats that as
        // "unconfigured" and falls back to DEFAULTS — appending gloves to the raw
        // list would defeat that fallback and strip the other defaults, so the
        // migration must leave such a list alone.
        val legacyOnly = """[{"item":"cardigan","type":"temp_below","value":12.0,"unit":"CELSIUS"}]"""
        val before = mutablePreferencesOf(clothesRulesKey to legacyOnly)

        val result = glovesDefaultMigration().migrate(before)

        result[clothesRulesKey] shouldBe legacyOnly
        result[glovesMigratedKey] shouldBe true
    }

    @Test
    fun `gloves migration runs once, gated by its sentinel`() = runTest {
        val migration = glovesDefaultMigration()

        migration.shouldMigrate(emptyPreferences()) shouldBe true
        migration.shouldMigrate(mutablePreferencesOf(glovesMigratedKey to true)) shouldBe false
    }
}
