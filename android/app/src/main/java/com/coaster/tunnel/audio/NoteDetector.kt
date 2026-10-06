package com.coaster.tunnel.audio

import kotlin.math.*

/**
 * Finds the musical notes in the signal.
 *
 * Neighbouring semitones in the bass are only a few hertz apart, far finer than
 * the 21.5 Hz bins of the band analysis, so this keeps its own rolling window of
 * 8192 samples (5.4 Hz bins) and analyses it every hop. Spectral peaks are
 * whitened against their neighbourhood so drums and noise count for little,
 * each candidate note is scored by summing its first few harmonics, and
 * candidates that are overtones (or undertones) of a stronger note are
 * suppressed, so a single piano note doesn't also light up its fifth and third.
 */
class NoteDetector(private val sampleRate: Float, private val bandEdgesHz: FloatArray) {

    class Notes(
        val chroma: FloatArray,           // 12 pitch classes from C, 0..1, smoothed
        val tonality: Float,              // 0 for noise and drums, 1 for clearly pitched sound
        val bandNote: IntArray,           // strongest pitch class in each band, -1 for none
        val bandNoteStrength: FloatArray  // how clearly that note stands out, 0..1
    )

    private val size = 8192
    private val half = size / 2
    private val binHz = sampleRate / size

    private val buffer = FloatArray(size)
    private val real = FloatArray(size)
    private val imag = FloatArray(size)
    private val window = FloatArray(size) { 0.5f * (1f - cos(2f * PI.toFloat() * it / (size - 1))) }
    private val magnitude = FloatArray(half)
    private val prefix = DoubleArray(half + 1)
    private val peaks = FloatArray(half)

    private val noteCount = NOTE_HIGH - NOTE_LOW + 1
    private val noteHz = FloatArray(noteCount) { 440f * 2f.pow((NOTE_LOW + it - 69) / 12f) }
    private val noteBand = IntArray(noteCount) { n ->
        (0 until bandEdgesHz.size - 1).firstOrNull { noteHz[n] >= bandEdgesHz[it] && noteHz[n] < bandEdgesHz[it + 1] } ?: -1
    }
    private val salience = FloatArray(noteCount)
    private val fundamental = FloatArray(noteCount)
    private val order = IntArray(noteCount)
    private val accepted = BooleanArray(noteCount)

    private val chroma = FloatArray(12)
    private var tonality = 0f

    fun process(samples: FloatArray): Notes {
        // Roll the newest samples into the window
        val n = min(samples.size, size)
        System.arraycopy(buffer, n, buffer, 0, size - n)
        System.arraycopy(samples, samples.size - n, buffer, size - n, n)
        val hopSeconds = samples.size / sampleRate

        for (i in 0 until size) {
            real[i] = buffer[i] * window[i]
            imag[i] = 0f
        }
        fft(real, imag, size)
        for (k in 0 until half) magnitude[k] = sqrt(real[k] * real[k] + imag[k] * imag[k]) / size
        for (k in 0 until half) prefix[k + 1] = prefix[k] + magnitude[k]

        // Whitened peaks: how far each local maximum stands above its surroundings
        peaks.fill(0f)
        for (k in 1 until half - 1) {
            val m = magnitude[k]
            if (m < GATE || m < magnitude[k - 1] || m < magnitude[k + 1]) continue
            // A fixed span keeps the score independent of frequency
            val lo = max(0, k - WHITEN_SPAN)
            val hi = min(half, k + WHITEN_SPAN + 1)
            val envelope = ((prefix[hi] - prefix[lo]) / (hi - lo)).toFloat()
            peaks[k] = max(0f, m / envelope - PEAK_FLOOR)
        }

        // Harmonic sum: a note scores for energy at its fundamental and overtones.
        // Real notes have both, so a candidate pieced together from other notes'
        // overtones (no fundamental) or a bare sine like a kick drum (no
        // overtones) scores far lower.
        for (c in 0 until noteCount) {
            var s = 0f
            var weight = 1f
            var hits = 0
            for (h in 1..HARMONICS) {
                val f = noteHz[c] * h
                if (f > MAX_HARMONIC_HZ) break
                val p = peakNear(f)
                if (h == 1) fundamental[c] = p
                if (p > 0f) hits++
                s += weight * p
                weight *= 0.8f
            }
            if (fundamental[c] == 0f) s *= 0.2f
            salience[c] = s * min(1f, hits / 3f)
        }

        // Strongest first; anything an octave, twelfth, two octaves, or major
        // third above (or below) an already accepted note is mostly its overtone.
        // A note whose octave below has its own fundamental and a comparable
        // score is that note's overtone too, so the lower one wins instead.
        for (i in 0 until noteCount) order[i] = i
        for (i in 1 until noteCount) {
            val v = order[i]
            var j = i - 1
            while (j >= 0 && salience[order[j]] < salience[v]) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }
        accepted.fill(false)
        for (i in 0 until noteCount) {
            val c = order[i]
            if (salience[c] <= 0f) break
            var overtone = c >= 12 && fundamental[c - 12] > 0f && salience[c - 12] >= 0.5f * salience[c]
            for (a in 0 until noteCount) {
                if (overtone) break
                if (accepted[a] && abs(c - a) in HARMONIC_INTERVALS) { overtone = true; break }
            }
            if (overtone) salience[c] *= 0.25f else accepted[c] = true
        }

        // How clearly pitched the sound is overall
        val strongest = salience.maxOrNull() ?: 0f
        val norm = max(strongest, SALIENCE_FULL)
        val targetTonality = (strongest / SALIENCE_FULL).coerceIn(0f, 1f)
        tonality = smooth(tonality, targetTonality, hopSeconds)

        // Strongest note within each band's frequency range
        val bandNote = IntArray(bandEdgesHz.size - 1) { -1 }
        val bandStrength = FloatArray(bandEdgesHz.size - 1)
        val bandBest = FloatArray(bandEdgesHz.size - 1)
        for (c in 0 until noteCount) {
            val b = noteBand[c]
            if (b >= 0 && salience[c] > bandBest[b]) {
                bandBest[b] = salience[c]
                bandNote[b] = (NOTE_LOW + c) % 12
            }
        }
        for (b in bandBest.indices) {
            bandStrength[b] = ((bandBest[b] / norm - 0.25f) / 0.45f).coerceIn(0f, 1f)
            if (bandStrength[b] == 0f) bandNote[b] = -1
        }

        // Chroma: the share of each pitch class, scaled by how tonal the sound is
        val raw = FloatArray(12)
        for (c in 0 until noteCount) raw[(NOTE_LOW + c) % 12] += salience[c]
        val rawMax = raw.maxOrNull() ?: 0f
        for (pc in 0 until 12) {
            val target = if (rawMax > 0f) (raw[pc] / rawMax).pow(1.5f) * targetTonality else 0f
            chroma[pc] = smooth(chroma[pc], target, hopSeconds)
        }

        return Notes(chroma.clone(), tonality, bandNote, bandStrength)
    }

    // Largest whitened peak within half a semitone of f
    private fun peakNear(f: Float): Float {
        val centre = f / binHz
        val reach = max(0.5f, centre * HALF_SEMITONE)
        val lo = max(1, floor(centre - reach).toInt())
        val hi = min(half - 2, ceil(centre + reach).toInt())
        var best = 0f
        for (k in lo..hi) best = max(best, peaks[k])
        return best
    }

    private fun smooth(current: Float, target: Float, dt: Float): Float {
        val rate = if (target > current) 12f else 3f
        return current + (target - current) * (1f - exp(-rate * dt))
    }

    companion object {
        private const val NOTE_LOW = 33     // A1, 55 Hz
        private const val NOTE_HIGH = 105   // A7, 3520 Hz
        private const val HARMONICS = 5
        private const val MAX_HARMONIC_HZ = 10000f
        private const val GATE = 0.0002f      // ignore peaks quieter than about -60 dBFS
        private const val WHITEN_SPAN = 12   // bins either side, about 65 Hz
        private const val PEAK_FLOOR = 3f     // a noise peak rarely stands 3x above its neighbours
        private const val SALIENCE_FULL = 8f  // salience of a clearly pitched note
        private val HALF_SEMITONE = 2f.pow(1f / 24f) - 1f
        // Semitone distances to the 2nd, 3rd, 4th, 5th and 6th harmonics
        private val HARMONIC_INTERVALS = setOf(12, 19, 24, 28, 31)
    }
}
