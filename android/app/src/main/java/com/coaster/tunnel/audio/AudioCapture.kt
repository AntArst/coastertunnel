package com.coaster.tunnel.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioCapture(private val onSamplesCaptured: (FloatArray) -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var thread: Thread? = null

    private val sampleRate = 44100
    private val bufferSize = 2048 // FFT Size

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        // Ensure we have at least bufferSize samples
        val actualBufferSize = maxOf(minBufferSize, bufferSize * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                actualBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioCapture", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRunning = true

            thread = Thread {
                val samples = FloatArray(bufferSize)
                while (isRunning) {
                    val read = audioRecord?.read(samples, 0, bufferSize, AudioRecord.READ_BLOCKING) ?: -1
                    if (read > 0) {
                        onSamplesCaptured(samples.clone())
                    }
                }
            }
            thread?.start()
            Log.i("AudioCapture", "Audio capture started")
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error starting audio capture", e)
        }
    }

    fun stop() {
        isRunning = false
        try {
            thread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i("AudioCapture", "Audio capture stopped")
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error stopping audio capture", e)
        }
    }
}
