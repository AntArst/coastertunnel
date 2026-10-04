package com.coaster.tunnel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.coaster.tunnel.gl.TunnelGLSurfaceView

class MainActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: TunnelGLSurfaceView
    private val RECORD_AUDIO_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        // Request fullscreen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system UI
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        glSurfaceView = TunnelGLSurfaceView(this)
        setContentView(glSurfaceView)

        checkPermission()
    }

    private fun checkPermission() {
        Log.d("MainActivity", "Checking permissions")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            Log.d("MainActivity", "Permission already granted")
            startAudio()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d("MainActivity", "onRequestPermissionsResult: $requestCode")
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Permission granted by user")
                startAudio()
            } else {
                Log.e("MainActivity", "Permission denied by user")
            }
        }
    }

    private fun startAudio() {
        Log.d("MainActivity", "Starting audio capture")
        glSurfaceView.startAudio()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        // onPause stops the capture, so restart it on the way back or the tunnel freezes
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }
}
