package com.coaster.tunnel.gl

import com.coaster.tunnel.MainActivity

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.coaster.tunnel.R
import com.coaster.tunnel.audio.FFTAnalyzer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TunnelRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var program: Int = 0
    private var vao: IntArray = intArrayOf(0)
    private var vbo: IntArray = intArrayOf(0)
    
    private var uTimeLoc: Int = -1
    private var uAudioLoc: Int = -1
    private var uBandsLoc: Int = -1
    private var uResLoc: Int = -1
    private var uHistLoc: Int = -1
    private var uOffsetLoc: Int = -1
    private var uRotationLoc: Int = -1
    private var uStarTimeLoc: Int = -1
    private var uSpinVelLoc: Int = -1
    private var uSpeedLoc: Int = -1
    private var uBeatAgeLoc: Int = -1

    private var currentAudioData: FFTAnalyzer.AudioBands? = null

    // Shader clock, wrapped to [0, TAU) so it keeps full float precision
    private var waveTime: Double = 0.0

    // Rotation State. The spin is wrapped to [0, TAU) and eases toward its
    // target velocity, so reversals swing round instead of snapping.
    private var currentRotation: Double = 0.0
    private var spinVelocity: Float = 0f
    private var rotationDirection: Float = 1f
    private var lastBpm: Float = 120f
    private var sinceReverse: Float = REVERSE_COOLDOWN
    private var settleRemaining: Float = 0f

    // Tap: brake the spin and glide back to the start orientation
    fun resetRotation() {
        rotationDirection = 1f
        settleRemaining = SETTLE_SECONDS
    }

    // Star Time (Independent of real time, driven by BPM), wrapped to STAR_PERIOD
    private var starTime: Double = 0.0
    private var lastFrameNanos: Long = 0

    // Seconds since the last detected beat, drives the beat pulse and shockwave
    private var beatAge: Float = BEAT_AGE_MAX

    // Audio arrives at about 21 Hz but frames at 60 Hz. Bands are eased every
    // frame, and the history read head slides between audio frames, so lines
    // move smoothly instead of stepping at the audio rate.
    private val displayBands = FloatArray(32)
    private var displayBass = 0f
    private var displayMids = 0f
    private var displayTreble = 0f
    private var lastHistoryNanos: Long = 0
    private var historyInterval: Float = 2048f / 44100f

    // History Texture
    private val historyWidth = 512
    private val historyHeight = 8 // 32 bands packed into 8 rows

    private val historyTexture = intArrayOf(0)
    private val historyBuffer = ByteBuffer.allocateDirect(historyWidth * historyHeight * 4)
        .order(ByteOrder.nativeOrder())
    private val historyColumn = ByteBuffer.allocateDirect(historyHeight * 4)
        .order(ByteOrder.nativeOrder())
    private var historyOffset = 0

    private var viewWidth: Int = 1920
    private var viewHeight: Int = 1200
    private var frameCount: Int = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d("TunnelRenderer", "onSurfaceCreated")
        lastFrameNanos = System.nanoTime()
        
        try {
            val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, R.raw.tunnel_vert)
            val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, R.raw.tunnel_frag)
            
            program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)
            
            val linkStatus = intArrayOf(0)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("TunnelRenderer", "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
            }

            uTimeLoc = GLES30.glGetUniformLocation(program, "uTime")
            uAudioLoc = GLES30.glGetUniformLocation(program, "uAudio")
            uBandsLoc = GLES30.glGetUniformLocation(program, "uBands")
            uResLoc = GLES30.glGetUniformLocation(program, "uResolution")
            uHistLoc = GLES30.glGetUniformLocation(program, "uAudioHistory")
            uOffsetLoc = GLES30.glGetUniformLocation(program, "uOffset")
            uRotationLoc = GLES30.glGetUniformLocation(program, "uRotation")
            uStarTimeLoc = GLES30.glGetUniformLocation(program, "uStarTime")
            uSpinVelLoc = GLES30.glGetUniformLocation(program, "uSpinVel")
            uSpeedLoc = GLES30.glGetUniformLocation(program, "uSpeed")
            uBeatAgeLoc = GLES30.glGetUniformLocation(program, "uBeatAge")

            Log.d("TunnelRenderer", "Uniform locations: time=$uTimeLoc, res=$uResLoc, bands=$uBandsLoc")

            setupHistoryTexture()
            setupQuad()
            Log.d("TunnelRenderer", "Setup complete")
        } catch (e: Exception) {
            Log.e("TunnelRenderer", "Setup failed", e)
        }
    }

    private fun setupHistoryTexture() {
        GLES30.glGenTextures(1, historyTexture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, historyTexture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        
        // Initial clear data
        historyBuffer.clear()
        for (i in 0 until historyWidth * historyHeight * 4) historyBuffer.put(0x00.toByte())
        historyBuffer.position(0)
        
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, historyWidth, historyHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, historyBuffer)
    }

    private fun setupQuad() {
        val vertices = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(vertices)
        vertexBuffer.position(0)

        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vertexBuffer, GLES30.GL_STATIC_DRAW)
        
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
    }

    fun updateAudioData(data: FFTAnalyzer.AudioBands) {
        currentAudioData = data
        val now = System.nanoTime()

        // Reverse the spin on a significant tempo change. Only a beat produces a
        // new tempo measurement, so the BPM decaying through silence never flips
        // it, and a cooldown stops it flickering back and forth.
        if (data.isBeat) {
            beatAge = 0f
            if (Math.abs(data.bpm - lastBpm) > 5.0f && sinceReverse >= REVERSE_COOLDOWN) {
                rotationDirection *= -1f
                lastBpm = data.bpm
                sinceReverse = 0f
            }
        }

        // Track how often audio frames arrive so the read head can slide between them
        if (lastHistoryNanos > 0) {
            val interval = ((now - lastHistoryNanos) / 1e9f).coerceIn(0.005f, 0.25f)
            historyInterval = historyInterval * 0.9f + interval * 0.1f
        }
        lastHistoryNanos = now

        // Update history texture: one column, 32 bands packed into 8 rows (RGBA)
        historyOffset = (historyOffset + 1) % historyWidth
        historyColumn.clear()
        for (band in 0 until 32) {
            historyColumn.put((data.bands[band] * 255f).toInt().coerceIn(0, 255).toByte())
        }
        historyColumn.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, historyTexture[0])
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, historyOffset, 0, 1, historyHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, historyColumn)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d("TunnelRenderer", "onSurfaceChanged: ${width}x${height}")
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        // Clamp so a stall or resume doesn't jump the stars and spin forward
        val dt = ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        // Accumulate Star Time and Rotation based on BPM
        val bpm = currentAudioData?.bpm ?: 120.0f
        val speedFactor = bpm / 60.0f // 1.0 at 60BPM, 2.0 at 120BPM

        starTime = (starTime + dt * speedFactor) % STAR_PERIOD
        waveTime = (waveTime + dt) % TAU
        beatAge = (beatAge + dt).coerceAtMost(BEAT_AGE_MAX)
        sinceReverse = (sinceReverse + dt).coerceAtMost(REVERSE_COOLDOWN)

        if (settleRemaining > 0f) {
            // Brake, then ease back to the start orientation by the shortest way
            settleRemaining -= dt
            spinVelocity -= spinVelocity * (1f - Math.exp(-dt * 8.0).toFloat())
            val back = Math.IEEEremainder(-currentRotation, TAU)
            currentRotation += back * (1.0 - Math.exp(-dt * 8.0))
        } else {
            val targetVelocity = speedFactor * 0.5f * rotationDirection // 0.5 rad/s base speed
            spinVelocity += (targetVelocity - spinVelocity) * (1f - Math.exp(-dt * 2.5).toFloat())
            currentRotation += dt * spinVelocity
        }
        currentRotation = ((currentRotation % TAU) + TAU) % TAU

        currentAudioData?.let { data ->
            val k = 1f - Math.exp(-dt * 30.0).toFloat()
            for (i in 0 until 32) displayBands[i] += (data.bands[i] - displayBands[i]) * k
            displayBass += (data.bass - displayBass) * k
            displayMids += (data.mids - displayMids) * k
            displayTreble += (data.treble - displayTreble) * k
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        GLES30.glUniform1f(uTimeLoc, waveTime.toFloat())
        GLES30.glUniform1f(uStarTimeLoc, starTime.toFloat())
        GLES30.glUniform1f(uRotationLoc, currentRotation.toFloat())
        GLES30.glUniform1f(uSpinVelLoc, spinVelocity)
        GLES30.glUniform1f(uSpeedLoc, speedFactor)
        GLES30.glUniform1f(uBeatAgeLoc, beatAge)
        GLES30.glUniform3f(uAudioLoc, displayBass, displayMids, displayTreble)
        GLES30.glUniform1fv(uBandsLoc, 32, displayBands, 0)

        GLES30.glUniform2f(uResLoc, viewWidth.toFloat(), viewHeight.toFloat())

        // Read head slides from the previous column to the newest one between
        // audio frames, sampling texel centres
        val slide = if (lastHistoryNanos > 0) {
            ((now - lastHistoryNanos) / 1e9f / historyInterval).coerceIn(0f, 1f)
        } else 1f
        GLES30.glUniform1f(uOffsetLoc, (historyOffset - 1 + slide + 0.5f) / historyWidth.toFloat())
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, historyTexture[0])
        GLES30.glUniform1i(uHistLoc, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        if (frameCount++ % 120 == 0) {
            Log.d("TunnelRenderer", "Drawing frame $frameCount, bpm=$bpm, bass=${currentAudioData?.bass}")
        }
    }

    private fun loadShader(type: Int, resourceId: Int): Int {
        val shaderCode = context.resources.openRawResource(resourceId).bufferedReader().readText()
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        
        val compiled = intArrayOf(0)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $log")
        }
        return shader
    }

    companion object {
        private const val TAU = 2.0 * Math.PI
        // Must match STAR_PERIOD in tunnel_frag.glsl
        private const val STAR_PERIOD = 256.0
        private const val BEAT_AGE_MAX = 10f
        private const val SETTLE_SECONDS = 0.8f
        private const val REVERSE_COOLDOWN = 2f // seconds
    }
}
