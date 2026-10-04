package com.coaster.tunnel.gl

import android.content.Context
import android.opengl.GLSurfaceView
import com.coaster.tunnel.audio.AudioCapture
import com.coaster.tunnel.audio.FFTAnalyzer

class TunnelGLSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer: TunnelRenderer
    private var audioCapture: AudioCapture? = null
    private val analyzer = FFTAnalyzer()

    init {
        setEGLContextClientVersion(3)
        renderer = TunnelRenderer(context)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            queueEvent {
                renderer.resetRotation()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    // Safe to call repeatedly: reuses one capture, and start() is a no-op while running
    fun startAudio() {
        val capture = audioCapture ?: AudioCapture { samples ->
            val audioData = analyzer.analyze(samples)
            queueEvent {
                renderer.updateAudioData(audioData)
            }
        }.also { audioCapture = it }
        capture.start()
    }

    override fun onPause() {
        super.onPause()
        audioCapture?.stop()
    }

    override fun onResume() {
        super.onResume()
        // Audio is restarted by MainActivity.onResume once the permission is granted
    }
}
