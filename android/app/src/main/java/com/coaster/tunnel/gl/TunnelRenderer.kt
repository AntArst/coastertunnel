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

    private var startTime: Long = 0
    private var currentAudioData: FFTAnalyzer.AudioBands? = null
    
    // Rotation State
    private var currentRotation: Float = 0f
    private var rotationDirection: Float = 1f
    private var lastBpm: Float = 120f
    
    fun resetRotation() {
        currentRotation = 0f
        rotationDirection = 1f
    }
    
    // Star Time (Independent of real time, driven by BPM)
    private var starTime: Float = 0f
    private var lastFrameTime: Long = 0

    // History Texture
    private val historyWidth = 512
    private val historyHeight = 8 // 32 bands packed into 8 rows

    private val historyTexture = intArrayOf(0)
    private val historyBuffer = ByteBuffer.allocateDirect(historyWidth * historyHeight * 4)
        .order(ByteOrder.nativeOrder())
    private var historyOffset = 0

    private var viewWidth: Int = 1920
    private var viewHeight: Int = 1200
    private var frameCount: Int = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d("TunnelRenderer", "onSurfaceCreated")
        startTime = System.currentTimeMillis()
        lastFrameTime = startTime
        
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
        
        // Check for significant tempo change to reverse spin
        if (Math.abs(data.bpm - lastBpm) > 5.0f) {
            rotationDirection *= -1f
            lastBpm = data.bpm
        }
        
        // Update history texture
        // 32 bands packed into 8 rows (RGBA)
        
        historyOffset = (historyOffset + 1) % historyWidth
        
        for (row in 0 until 8) {
            val baseBand = row * 4
            val pixel = byteArrayOf(
                (data.bands[baseBand] * 255f).toInt().coerceIn(0, 255).toByte(),
                (data.bands[baseBand + 1] * 255f).toInt().coerceIn(0, 255).toByte(),
                (data.bands[baseBand + 2] * 255f).toInt().coerceIn(0, 255).toByte(),
                (data.bands[baseBand + 3] * 255f).toInt().coerceIn(0, 255).toByte()
            )
            val pBatch = ByteBuffer.wrap(pixel)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, historyOffset, row, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pBatch)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d("TunnelRenderer", "onSurfaceChanged: ${width}x${height}")
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.currentTimeMillis()
        val dt = (now - lastFrameTime) / 1000.0f
        lastFrameTime = now
        
        // Accumulate Star Time and Rotation based on BPM
        val bpm = currentAudioData?.bpm ?: 120.0f
        val speedFactor = bpm / 60.0f // 1.0 at 60BPM, 2.0 at 120BPM
        
        starTime += dt * speedFactor
        currentRotation += dt * speedFactor * 0.5f * rotationDirection // 0.5 rad/s base speed
        
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)
        
        val time = (now - startTime) / 1000.0f
        GLES30.glUniform1f(uTimeLoc, time)
        GLES30.glUniform1f(uStarTimeLoc, starTime)
        GLES30.glUniform1f(uRotationLoc, currentRotation)
        
        currentAudioData?.let { data ->
            GLES30.glUniform3f(uAudioLoc, data.bass, data.mids, data.treble)
            GLES30.glUniform1fv(uBandsLoc, 32, data.bands, 0)
        }
        
        GLES30.glUniform2f(uResLoc, viewWidth.toFloat(), viewHeight.toFloat()) 
        GLES30.glUniform1f(uOffsetLoc, historyOffset.toFloat() / historyWidth.toFloat())
        
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, historyTexture[0])
        GLES30.glUniform1i(uHistLoc, 0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        if (frameCount++ % 120 == 0) {
            Log.d("TunnelRenderer", "Drawing frame $frameCount, time=$time, bass=${currentAudioData?.bass}")
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
}
