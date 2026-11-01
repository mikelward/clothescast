package app.clothescast.core.data.tts

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SleighBellsTest {

    private val rate = 24_000

    private fun samples(pcm: ByteArray): DoubleArray = DoubleArray(pcm.size / 2) {
        ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() / 32767.0
    }

    private fun rms(s: DoubleArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { s[it] * s[it] } / (to - from))

    @Test
    fun `lasts the same few seconds at any sample rate`() {
        (SleighBells.durationSeconds in 1.5..2.5) shouldBe true
        for (r in listOf(24_000, 44_100)) {
            withClue("rate=$r") {
                SleighBells.pcm(r).size / 2.0 / r shouldBe (SleighBells.durationSeconds plusOrMinus 0.001)
            }
        }
    }

    @Test
    fun `peaks at its level without clipping`() {
        samples(SleighBells.pcm(rate)).maxOf { abs(it) } shouldBe (SleighBells.PEAK plusOrMinus 0.001)
    }

    @Test
    fun `rings from the start and fades to silence before the speech`() {
        val s = samples(SleighBells.pcm(rate))
        (rms(s, 0, rate / 10) > 0.02) shouldBe true
        (rms(s, s.size - rate / 100, s.size) < 0.001) shouldBe true
    }

    @Test
    fun `sounds like small bells, high and bright, not a low tone`() {
        // The first difference of a tone grows with its pitch: about 0.1 of the
        // signal at 440 Hz, above 1 for the small bells' 4-8 kHz.
        val s = samples(SleighBells.pcm(rate))
        val diff = DoubleArray(s.size - 1) { s[it + 1] - s[it] }
        (rms(diff, 0, diff.size) > rms(s, 0, s.size)) shouldBe true
    }

    @Test
    fun `shakes four times, evenly spaced and alike`() {
        val s = samples(SleighBells.pcm(rate))
        val window = (0.05 * rate).toInt()
        val spacing = (SleighBells.SPACING_S * rate).toInt()
        val hits = (0 until SleighBells.SHAKES).map { rms(s, it * spacing, it * spacing + window) }
        // Each shake thins out before the next one lands.
        val gap = rms(s, spacing - window, spacing)
        hits.forEach { (gap < it / 2) shouldBe true }
        // No tune: every shake is the same, at about the same level.
        hits.forEach { it shouldBe (hits[0] plusOrMinus hits[0] * 0.2) }
    }
}
