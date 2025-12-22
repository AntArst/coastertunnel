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

    fun startAudio() {
        audioCapture = AudioCapture { samples ->
            val audioData = analyzer.analyze(samples)
            queueEvent {
                renderer.updateAudioData(audioData)
            }
        }
        audioCapture?.start()
    }

    override fun onPause() {
        super.onPause()
        audioCapture?.stop()
    }

    override fun onResume() {
        super.onResume()
        // Audio will be started by MainActivity after permission check if already granted
    }
}
