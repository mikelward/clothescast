package app.clothescast.core.data.tts

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The sleigh bells that open Father Christmas's briefing: four evenly spaced
 * shakes, then a short ring-out before he speaks.
 *
 * Each shake sounds like a tambourine hit: a "tsssh" of hundreds of tiny
 * jingle collisions, crowded at the hit and thinning out over a fraction of a
 * second, each a bright metal ping with a puff of rattle. Separate strikes or
 * tuned bells read as an anvil or a music box instead. The same shake plays
 * every time, so nothing reads as a melody.
 *
 * Synthesized for the same reasons as [OrganSting]: nothing added to the APK,
 * no recording license, and rendered at Gemini's sample rate so it splices
 * onto the speech as is. Each rate is rendered once per process.
 */
internal object SleighBells {

    /** Peak level, below Gemini's speech so the intro doesn't blast. */
    const val PEAK = 0.5

    /** How many shakes, and the seconds from one to the next. */
    const val SHAKES = 4
    const val SPACING_S = 0.42

    /** Ring-out kept after the last shake; the final [FADE_S] fades to silence. */
    private const val TAIL_S = 0.6
    private const val FADE_S = 0.25

    // Jingle collisions per shake, and the time over which they thin out.
    private const val COLLISIONS = 400
    private const val SIZZLE_S = 0.32

    // The jingles' pitches, drawn at random from this band, and how long each
    // collision rings, as an exponential decay time constant.
    private const val JINGLES = 16
    private const val LOWEST_HZ = 3500.0
    private const val HIGHEST_HZ = 8000.0
    private const val PING_S = 0.02

    // The pellet-like rattle on each collision: a short tick of bright noise.
    private const val RATTLE_LEVEL = 0.6
    private const val RATTLE_S = 0.003

    /** Length of the shakes plus the tail, in seconds. */
    val durationSeconds: Double = (SHAKES - 1) * SPACING_S + TAIL_S

    private class Collision(val at: Double, val pitch: Double, val level: Double)

    private val cache = ConcurrentHashMap<Int, ByteArray>()

    /** The intro as signed 16-bit little-endian mono PCM at [sampleRate]. */
    fun pcm(sampleRate: Int): ByteArray = cache.getOrPut(sampleRate) { render(sampleRate) }

    private fun render(rate: Int): ByteArray {
        val length = (durationSeconds * rate).roundToInt()
        val buf = DoubleArray(length)
        // Fixed seed: the same shake every time, so tests and listeners hear one take.
        val random = Random(11)
        val jingles = List(JINGLES) { LOWEST_HZ + random.nextDouble() * (HIGHEST_HZ - LOWEST_HZ) }
        // One shake, reused for every beat so all four sound alike.
        val shake = List(COLLISIONS) {
            // Exponentially distributed onsets: crowded at the hit, sparser after.
            val at = -ln(1 - random.nextDouble() * 0.98) * SIZZLE_S / 3
            Collision(
                at = at,
                pitch = jingles[random.nextInt(JINGLES)] * (1 + (random.nextDouble() - 0.5) * 0.02),
                level = exp(-at / SIZZLE_S) * (0.5 + random.nextDouble() * 0.5),
            )
        }
        for (beat in 0 until SHAKES) {
            for (collision in shake) ping(buf, rate, beat * SPACING_S, collision, random)
        }
        val fadeStart = length - (FADE_S * rate).roundToInt()
        for (i in fadeStart until length) buf[i] *= (length - 1 - i).toDouble() / (length - 1 - fadeStart)
        val scale = PEAK / buf.maxOf { abs(it) }
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = (buf[i] * scale * Short.MAX_VALUE).roundToInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun ping(buf: DoubleArray, rate: Int, beatStart: Double, c: Collision, random: Random) {
        val start = ((beatStart + c.at) * rate).roundToInt()
        val count = min((PING_S * 5 * rate).roundToInt(), buf.size - start)
        var lastNoise = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            val envelope = min(1.0, t / 0.001) * exp(-t / PING_S)
            // The jingle's inharmonic overtones die away faster than its pitch.
            var x = sin(2 * PI * c.pitch * t) + 0.5 * exp(-t / (PING_S / 2)) * sin(2 * PI * c.pitch * 1.47 * t)
            if (c.pitch * 2.09 < rate / 2.2) x += 0.3 * exp(-t / (PING_S / 3)) * sin(2 * PI * c.pitch * 2.09 * t)
            // First difference of white noise: a bright tick, not a hiss.
            val noise = random.nextDouble() * 2 - 1
            x += RATTLE_LEVEL * (noise - lastNoise) * exp(-t / RATTLE_S)
            lastNoise = noise
            buf[start + i] += c.level * envelope * x
        }
    }
}
