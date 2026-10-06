package com.coaster.tunnel.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

class NoteDetectorTest {

    private val sampleRate = 44100f
    private val edges = FloatArray(33) { exp(ln(60.0) + (ln(22000.0) - ln(60.0)) * it / 32.0).toFloat() }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    // A plain instrument-like tone: eight harmonics falling off as 1/h
    private fun tone(midi: Int, t: Double, amp: Double = 0.15): Double {
        var s = 0.0
        for (h in 1..8) s += sin(2 * PI * hz(midi) * h * t) / h
        return s * amp
    }

    private fun listen(seconds: Double, signal: (Double) -> Double): NoteDetector.Notes {
        val detector = NoteDetector(sampleRate, edges)
        var notes: NoteDetector.Notes? = null
        for (hop in 0 until (seconds * sampleRate / 2048).toInt()) {
            notes = detector.process(FloatArray(2048) { signal((hop * 2048 + it) / sampleRate.toDouble()).toFloat() })
        }
        return notes!!
    }

    private fun bandOf(midi: Int) = (0 until 32).first { hz(midi) >= edges[it] && hz(midi) < edges[it + 1] }

    private fun strongest(chroma: FloatArray, count: Int) =
        (0 until 12).sortedByDescending { chroma[it] }.take(count).toSet()

    @Test
    fun findsASingleNote() {
        val notes = listen(1.0) { tone(69, it) }  // A4
        assertEquals(setOf(9), strongest(notes.chroma, 1))
        assertEquals(9, notes.bandNote[bandOf(69)])
        assertTrue(notes.tonality > 0.8f)
    }

    @Test
    fun findsABassNote() {
        val notes = listen(1.0) { tone(40, it, 0.3) }  // E2, 82 Hz
        assertEquals(setOf(4), strongest(notes.chroma, 1))
        assertEquals(4, notes.bandNote[bandOf(40)])
    }

    @Test
    fun findsEachNoteOfAChordInItsOwnBand() {
        val notes = listen(1.0) { tone(60, it) + tone(64, it) + tone(67, it) }  // C4 E4 G4
        assertEquals(setOf(0, 4, 7), strongest(notes.chroma, 3))
        assertEquals(0, notes.bandNote[bandOf(60)])
        assertEquals(4, notes.bandNote[bandOf(64)])
        assertEquals(7, notes.bandNote[bandOf(67)])
    }

    @Test
    fun overtonesDoNotShowAsNotes() {
        // A4's 3rd and 5th harmonics are E and C#; neither should light a band
        val notes = listen(1.0) { tone(69, it) }
        val lit = (0 until 32).filter { notes.bandNote[it] >= 0 && notes.bandNoteStrength[it] > 0.2f }
        assertEquals(listOf(bandOf(69)), lit)
    }

    @Test
    fun noiseIsNotTonal() {
        val random = Random(1)
        val notes = listen(1.0) { random.nextGaussian() * 0.1 }
        assertTrue("tonality ${notes.tonality}", notes.tonality < 0.2f)
        assertTrue(notes.bandNoteStrength.all { it < 0.2f })
    }

    @Test
    fun silenceIsQuiet() {
        val notes = listen(0.5) { 0.0 }
        assertEquals(0f, notes.tonality, 0f)
        assertTrue(notes.bandNote.all { it == -1 })
    }
}
