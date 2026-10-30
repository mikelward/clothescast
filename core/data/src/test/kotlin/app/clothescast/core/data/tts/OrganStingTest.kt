package app.clothescast.core.data.tts

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class OrganStingTest {

    private val rate = 24_000

    private fun samples(pcm: ByteArray): DoubleArray = DoubleArray(pcm.size / 2) {
        ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() / 32767.0
    }

    private fun rms(s: DoubleArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { s[it] * s[it] } / (to - from))

    /** Goertzel power of [freq] Hz over samples [from, to). */
    private fun power(s: DoubleArray, from: Int, to: Int, freq: Double): Double {
        val c = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until to) {
            val s0 = s[i] + c * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    @Test
    fun `lasts the same few seconds at any sample rate`() {
        (OrganSting.durationSeconds in 4.0..7.0) shouldBe true
        for (r in listOf(24_000, 44_100)) {
            withClue("rate=$r") {
                OrganSting.pcm(r).size / 2.0 / r shouldBe (OrganSting.durationSeconds plusOrMinus 0.001)
            }
        }
    }

    @Test
    fun `peaks at its level without clipping`() {
        samples(OrganSting.pcm(rate)).maxOf { abs(it) } shouldBe (OrganSting.PEAK plusOrMinus 0.001)
    }

    @Test
    fun `sounds from the start and fades to silence before the speech`() {
        val s = samples(OrganSting.pcm(rate))
        (rms(s, 0, rate / 2) > 0.05) shouldBe true
        (rms(s, s.size - rate / 100, s.size) < 0.001) shouldBe true
    }

    @Test
    fun `holds the toccata's opening A`() {
        // The mordent lands on a held A (880 Hz, doubled at 440) from about
        // 0.2 s to 1.2 s; a semitone up should barely register.
        val s = samples(OrganSting.pcm(rate))
        val from = (0.3 * rate).toInt()
        val to = (1.1 * rate).toInt()
        (power(s, from, to, 880.0) > 10 * power(s, from, to, 932.3)) shouldBe true
    }
}
