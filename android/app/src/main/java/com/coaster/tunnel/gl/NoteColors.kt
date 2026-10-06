package com.coaster.tunnel.gl

import com.coaster.tunnel.audio.NoteDetector
import kotlin.math.abs

/**
 * Turns detected notes into colours. Each pitch class has its own hue, laid
 * out around the circle of fifths (C red, G orange, D yellow, ... F magenta),
 * so notes that sound well together get neighbouring hues and a chord reads as
 * one family of colours, while a key change shifts the whole palette.
 */
class NoteColors(private val bandCount: Int = 32) {

    // Per band: note colour premultiplied by how clearly the note sounds, then
    // that strength. Premultiplied so the history texture can blend it linearly.
    val bandColors = FloatArray(bandCount * 4)

    // Blend of the sounding notes' colours, and how tonal the sound is
    val chord = FloatArray(4)

    fun update(notes: NoteDetector.Notes) {
        for (b in 0 until bandCount) {
            val pc = notes.bandNote[b]
            val strength = if (pc >= 0) notes.bandNoteStrength[b] else 0f
            // Quick to light, slower to fade, so short notes still register
            val k = if (strength > bandColors[b * 4 + 3]) 0.6f else 0.3f
            for (ch in 0 until 3) {
                val target = if (pc >= 0) PALETTE[pc][ch] * strength else 0f
                bandColors[b * 4 + ch] += (target - bandColors[b * 4 + ch]) * k
            }
            bandColors[b * 4 + 3] += (strength - bandColors[b * 4 + 3]) * k
        }

        var total = 0f
        chord.fill(0f)
        for (pc in 0 until 12) {
            val w = notes.chroma[pc]
            for (ch in 0 until 3) chord[ch] += PALETTE[pc][ch] * w
            total += w
        }
        if (total > 0f) for (ch in 0 until 3) chord[ch] /= total
        chord[3] = notes.tonality
    }

    companion object {
        // Position of a pitch class on the circle of fifths (C 0, G 1, D 2, ...)
        fun fifths(pitchClass: Int) = (pitchClass * 7) % 12

        // Same smooth hue wheel as spectrum() in tunnel_frag.glsl
        fun spectrum(h: Float): FloatArray = FloatArray(3) { ch ->
            val offset = floatArrayOf(0f, 4f, 2f)[ch]
            val c = (abs(((h * 6f + offset) % 6f) - 3f) - 1f).coerceIn(0f, 1f)
            c * c * (3f - 2f * c)
        }

        val PALETTE: Array<FloatArray> = Array(12) { pc -> spectrum(fifths(pc) / 12f) }
    }
}
