package app.clothescast.core.data.tts

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The pipe-organ intro that opens the Halloween vampire's briefing: the first
 * bars of Bach's Toccata and Fugue in D minor (public domain), played in
 * octaves, then a D minor chord over a low pedal, in a stone-hall reverb.
 *
 * Synthesized here instead of shipped as a recording: it adds nothing to the
 * APK, needs no recording license, and renders at whatever sample rate Gemini
 * returned, so it splices onto the speech without resampling. Each rate is
 * rendered once per process (a few hundred milliseconds of CPU, kept off the
 * caller's thread by [withIntroFor]).
 */
internal object OrganSting {

    /** Peak level, below Gemini's speech so the intro doesn't blast. */
    const val PEAK = 0.5

    /** Reverb tail kept after the last chord, the final [FADE_S] faded to silence. */
    private const val TAIL_S = 0.7
    private const val FADE_S = 0.4

    // Pipe-organ registration as (multiple of the note's pitch, level): 16', 8',
    // 4', 2 2/3', 2', 1 3/5' and 1' stops.
    private val STOPS = listOf(
        0.5 to 0.45, 1.0 to 1.0, 2.0 to 0.55, 3.0 to 0.25, 4.0 to 0.3, 5.0 to 0.08, 8.0 to 0.12,
    )

    // A second rank tuned a few cents sharp, for the chorus of real pipes.
    private const val DETUNE = 1.0017

    private class Note(val start: Double, val duration: Double, val midiNotes: List<Int>)

    // The opening mordent and descending run, each note doubled an octave down,
    // then the chord. Times in seconds.
    private val SCORE: List<Note> = buildList {
        var t = 0.0
        fun play(midi: Int, duration: Double) {
            add(Note(t, duration, listOf(midi, midi - 12)))
            t += duration
        }
        play(81, 0.11) // A
        play(79, 0.11) // G
        play(81, 0.95) // A
        t += 0.3
        play(79, 0.1) // G
        play(77, 0.1) // F
        play(76, 0.1) // E
        play(74, 0.1) // D
        play(73, 0.4) // C sharp
        play(74, 0.9) // D
        t += 0.15
        add(Note(t, 1.6, listOf(38, 50, 53, 57, 62))) // D minor over a low D
    }

    /** Length of the score plus its tail, in seconds. */
    val durationSeconds: Double = SCORE.maxOf { it.start + it.duration } + TAIL_S

    private val cache = ConcurrentHashMap<Int, ByteArray>()

    /** The intro as signed 16-bit little-endian mono PCM at [sampleRate]. */
    fun pcm(sampleRate: Int): ByteArray = cache.getOrPut(sampleRate) { render(sampleRate) }

    private fun render(rate: Int): ByteArray {
        val length = (durationSeconds * rate).roundToInt()
        val dry = DoubleArray(length)
        for (note in SCORE) {
            for (midi in note.midiNotes) addTone(dry, rate, note, 440.0 * 2.0.pow((midi - 69) / 12.0))
        }
        val mixed = reverb(dry, rate)
        val fadeStart = length - (FADE_S * rate).roundToInt()
        for (i in fadeStart until length) mixed[i] *= (length - 1 - i).toDouble() / (length - 1 - fadeStart)
        val scale = PEAK / mixed.maxOf { abs(it) }
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = (mixed[i] * scale * Short.MAX_VALUE).roundToInt()
            out[2 * i] = s.toByte()
            out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun addTone(buf: DoubleArray, rate: Int, note: Note, freq: Double) {
        val start = (note.start * rate).roundToInt()
        val count = (note.duration * rate).roundToInt()
        val attack = (0.02 * rate).roundToInt()
        val release = (0.06 * rate).roundToInt()
        val stops = STOPS.filter { (multiple, _) -> freq * multiple < rate / 2.2 }
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            var sample = 0.0
            for ((multiple, level) in stops) {
                val phase = 2 * PI * freq * multiple * t
                sample += level * (sin(phase) + 0.5 * sin(phase * DETUNE))
            }
            val envelope = when {
                i < attack -> i.toDouble() / attack
                i >= count - release -> (count - 1 - i).toDouble() / release
                else -> 1.0
            }
            buf[start + i] += sample * envelope
        }
    }

    /**
     * Schroeder reverb: four parallel feedback combs, then two allpasses,
     * mixed under the dry signal at a quarter of its level.
     */
    private fun reverb(dry: DoubleArray, rate: Int): DoubleArray {
        val wet = DoubleArray(dry.size)
        for ((delayMs, gain) in listOf(29.7 to 0.84, 37.1 to 0.83, 41.1 to 0.82, 43.7 to 0.81)) {
            val d = (delayMs * rate / 1000).roundToInt()
            val comb = dry.copyOf()
            for (k in d until comb.size) comb[k] += gain * comb[k - d]
            for (k in wet.indices) wet[k] += comb[k]
        }
        var diffused = wet
        for ((delayMs, gain) in listOf(5.0 to 0.7, 1.7 to 0.7)) {
            val d = (delayMs * rate / 1000).roundToInt()
            val input = diffused
            val output = DoubleArray(input.size)
            for (k in input.indices) {
                output[k] = -gain * input[k] +
                    (if (k >= d) input[k - d] + gain * output[k - d] else 0.0)
            }
            diffused = output
        }
        val dryPeak = dry.maxOf { abs(it) }
        val wetPeak = diffused.maxOf { abs(it) }
        return DoubleArray(dry.size) { 0.75 * dry[it] + 0.25 * diffused[it] / wetPeak * dryPeak }
    }
}
