package com.coaster.tunnel.audio

import kotlin.math.*

class FFTAnalyzer {
    private val fftSize = 2048
    private val sampleRate = 44100.0f
    
    // 32 frequency bands
    data class AudioBands(
        val bass: Float,
        val mids: Float,
        val treble: Float,
        val amplitude: Float,
        val bands: FloatArray // 32 bands
    )

    // Intermediate buffers
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val magnitude = FloatArray(fftSize / 2)
    private val window = FloatArray(fftSize)
    
    // Smoothing values
    private var smoothBass = 0.0f
    private var smoothMids = 0.0f
    private var smoothTreble = 0.0f
    private val smoothBands = FloatArray(32)

    init {
        // Hann window
        for (i in 0 until fftSize) {
            window[i] = 0.5f * (1.0f - cos(2.0f * PI.toFloat() * i / (fftSize - 1)))
        }
    }

    fun analyze(samples: FloatArray): AudioBands {
        // 1. Windowing
        for (i in 0 until fftSize) {
            real[i] = if (i < samples.size) samples[i] * window[i] else 0.0f
            imag[i] = 0.0f
        }

        // 2. FFT (Simple Radix-2)
        fft(real, imag, fftSize)

        // 3. Magnitude
        for (i in 0 until fftSize / 2) {
            magnitude[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }

        // 4. Group into 32 bands (Logarithmic)
        // Frequency range: 60Hz to 22000Hz
        val bandLimits = IntArray(33)
        val minLog = ln(60.0)
        val maxLog = ln(22000.0)
        val logRange = maxLog - minLog
        
        for (i in 0..32) {
            val logFreq = minLog + (logRange * i / 32.0)
            val freq = exp(logFreq).toFloat()
            bandLimits[i] = freqToBin(freq)
        }
        
        // Ensure strictly increasing indices
        var currentBin = bandLimits[0]
        for (i in 1..32) {
            if (bandLimits[i] <= currentBin) {
                bandLimits[i] = currentBin + 1
            }
            currentBin = bandLimits[i]
        }

        val rawBands = FloatArray(32)
        for (i in 0 until 32) {
            var sum = 0.0f
            var count = 0
            for (bin in bandLimits[i] until bandLimits[i+1]) {
                if (bin < magnitude.size) {
                    sum += magnitude[bin]
                    count++
                }
            }
            // Normalize and scale
            val normalizedSum = sum / fftSize
            val avg = if (count > 0) normalizedSum / count else 0.0f
            
            // Noise gate: ignore signals below 0.001
            // Gain 5000.0f to strongly boost signals
            rawBands[i] = if (avg > 0.001f) (avg * 5000.0f) else 0.0f
        }

        // 5. Smoothing (Attack/Decay)
        val dt = 1.0f / 60.0f // Approx frame time
        val attack = 25.0f
        val decay = 8.0f // Slower decay for smoother look

        for (i in 0 until 32) {
             // Hard clamp the target
             var target = rawBands[i].coerceIn(0f, 1.5f)
             if (target < 0.05f) target = 0.0f
             
            smoothBands[i] = smoothValue(smoothBands[i], target, dt, attack, decay)
        }

        // Summaries: splitting 32 bands
        // Bass: 0-3 (60-150Hz)
        // Mids: 4-19 (150-4000Hz)
        // Treble: 20-31 (4000-22000Hz)
        val bass = (0..3).map { smoothBands[it] }.average().toFloat()
        val mids = (4..19).map { smoothBands[it] }.average().toFloat()
        val treble = (20..31).map { smoothBands[it] }.average().toFloat()
        val amplitude = (bass + mids + treble) / 3f

        return AudioBands(bass, mids, treble, amplitude, smoothBands.clone())
    }

    private fun freqToBin(freq: Float): Int {
        return (freq / (sampleRate / fftSize)).toInt().coerceIn(0, fftSize / 2 - 1)
    }

    private fun smoothValue(current: Float, target: Float, dt: Float, attack: Float, decay: Float): Float {
        val speed = if (target > current) attack else decay
        val t = 1.0f - exp(-speed * dt)
        return current + (target - current) * t
    }

    // Standard iterative Radix-2 FFT
    private fun fft(x: FloatArray, y: FloatArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempReal = x[i]
                x[i] = x[j]
                x[j] = tempReal
                val tempImag = y[i]
                y[i] = y[j]
                y[j] = tempImag
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        var m = 2
        while (m <= n) {
            val theta = -2.0 * PI / m
            val wpr = cos(theta).toFloat()
            val wpi = sin(theta).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (k in 0 until m / 2) {
                    val r = x[i + k + m / 2]
                    val im = y[i + k + m / 2]
                    val tr = wr * r - wi * im
                    val ti = wr * im + wi * r
                    x[i + k + m / 2] = x[i + k] - tr
                    y[i + k + m / 2] = y[i + k] - ti
                    x[i + k] += tr
                    y[i + k] += ti
                    val temp = wr
                    wr = wr * wpr - wi * wpi
                    wi = temp * wpi + wi * wpr
                }
                i += m
            }
            m *= 2
        }
    }
}
