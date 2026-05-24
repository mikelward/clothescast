package app.clothescast.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.clothescast.core.domain.model.DailyHistoryEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DailyHistoryStoreTest {
    @TempDir lateinit var tempDir: Path

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var subject: DailyHistoryStore

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(TestCoroutineScheduler()))
        dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempDir.toFile(), "history.preferences_pb") },
        )
        subject = DailyHistoryStore(dataStore)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `entryFor returns null when no entry stored for that date`() = runTest {
        subject.entryFor(LocalDate.of(2026, 5, 23)).shouldBeNull()
    }

    @Test
    fun `put and entryFor round-trip a single entry`() = runTest {
        val entry = DailyHistoryEntry(
            date = LocalDate.of(2026, 5, 23),
            feelsLikeMinC = 14.0,
            feelsLikeMaxC = 28.5,
        )
        subject.put(entry)
        subject.entryFor(entry.date) shouldBe entry
    }

    @Test
    fun `put replaces a same-day entry rather than duplicating it`() = runTest {
        val date = LocalDate.of(2026, 5, 23)
        subject.put(DailyHistoryEntry(date, 10.0, 20.0))
        subject.put(DailyHistoryEntry(date, 12.0, 22.0))
        subject.entryFor(date) shouldBe DailyHistoryEntry(date, 12.0, 22.0)
    }

    @Test
    fun `put prunes anything older than entry date minus one day`() = runTest {
        // Three days running: only the latest two should survive, because the
        // delta clause only ever reads today.minusDays(1).
        val d1 = LocalDate.of(2026, 5, 21)
        val d2 = d1.plusDays(1)
        val d3 = d1.plusDays(2)
        subject.put(DailyHistoryEntry(d1, 10.0, 20.0))
        subject.put(DailyHistoryEntry(d2, 11.0, 21.0))
        subject.put(DailyHistoryEntry(d3, 12.0, 22.0))

        subject.entryFor(d1).shouldBeNull()
        subject.entryFor(d2) shouldBe DailyHistoryEntry(d2, 11.0, 21.0)
        subject.entryFor(d3) shouldBe DailyHistoryEntry(d3, 12.0, 22.0)
    }

    @Test
    fun `put dropping an entry across a multi-day gap leaves only the new one`() = runTest {
        // User skipped a few days (app disabled, phone off, on holiday). When
        // they come back, the prior record is too stale to be "yesterday" so
        // it's pruned the moment a new entry lands.
        subject.put(DailyHistoryEntry(LocalDate.of(2026, 5, 21), 10.0, 20.0))
        subject.put(DailyHistoryEntry(LocalDate.of(2026, 5, 24), 14.0, 28.0))

        subject.entryFor(LocalDate.of(2026, 5, 21)).shouldBeNull()
        subject.entryFor(LocalDate.of(2026, 5, 24)) shouldBe
            DailyHistoryEntry(LocalDate.of(2026, 5, 24), 14.0, 28.0)
    }
}
