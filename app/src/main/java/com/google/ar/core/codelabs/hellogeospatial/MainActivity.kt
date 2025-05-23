package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.codelabs.hellogeospatial.helpers.CameraPermissionHelper

/**
 * Main entry point activity that allows users to launch the AR experience.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up the button
        findViewById<Button>(R.id.basicArButton).setOnClickListener {
            if (checkCameraPermission()) {
                startActivity(Intent(this, BasicARActivity::class.java))
            } else {
                requestCameraPermission()
            }
        }
        
        // Check and request camera permission
        if (!checkCameraPermission()) {
            requestCameraPermission()
        }
        
        // Update the status text
        updateArStatus()
    }
    
    override fun onResume() {
        super.onResume()
        updateArStatus()
    }

    private fun updateArStatus() {
        val statusText = findViewById<TextView>(R.id.arStatusText)
        
        // Check ARCore availability
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        val message = when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> 
                "ARCore is supported and installed."
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD, 
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> 
                "ARCore is supported but not installed or needs an update."
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> 
                "Your device does not support ARCore."
            else -> "ARCore availability unknown."
        }
        
        statusText.text = message
    }
    
    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, 
        permissions: Array<String>, 
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is needed to run AR features",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
} 