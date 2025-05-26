<<<<<<< HEAD
private fun launchSplitScreenMode() {
    // Check camera permission
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
=======
package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
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

    private lateinit var cameraManager: CameraManager
    private var isProcessingCameraAction = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Set up the button
        findViewById<Button>(R.id.basicArButton).setOnClickListener {
            if (isProcessingCameraAction) {
                Toast.makeText(this, "Please wait, processing camera...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (checkCameraPermission()) {
                prepareCameraAndStart()
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
        isProcessingCameraAction = false
    }
    
    /**
     * Prepare camera resources and launch the camera activity
     */
    private fun prepareCameraAndStart() {
        isProcessingCameraAction = true
        
        // Update button state
        findViewById<Button>(R.id.basicArButton).isEnabled = false
        
        // Reset camera resources at application level
        (application as? ARApplication)?.forceReleaseCameraResources()
        
        // Force garbage collection
        System.gc()
        
        // Wait briefly for resources to be cleaned up
        Handler().postDelayed({
            // Check camera availability again after cleanup
            if (isCameraAvailable()) {
                startActivity(Intent(this, BasicARActivity::class.java))
            } else {
                Toast.makeText(this, "Camera is not available right now. Try again.", Toast.LENGTH_LONG).show()
            }
            
            // Restore button state
            findViewById<Button>(R.id.basicArButton).isEnabled = true
            isProcessingCameraAction = false
        }, 500)
    }

    private fun isCameraAvailable(): Boolean {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    return true
                }
            }
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "Error checking camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun updateArStatus() {
        val statusText = findViewById<TextView>(R.id.arStatusText)
        
        if (!checkCameraPermission()) {
            statusText.text = "Camera permission required for AR"
            return
        }

        if (!isCameraAvailable()) {
            statusText.text = "Camera is not available"
            return
        }
        
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
>>>>>>> aa77e5378985bbb16e4493f985f2046e93bc796d
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
<<<<<<< HEAD
        return
    }

    try {
        val splitScreenIntent = Intent(this, SplitScreenActivity::class.java)
        startActivity(splitScreenIntent)
        Toast.makeText(this, "Loading split screen mode - please wait", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch split screen mode", e)
        Toast.makeText(this, "Failed to launch split screen mode: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Set up split screen FAB
    findViewById<FloatingActionButton>(R.id.split_screen_fab).setOnClickListener {
        launchSplitScreenMode()
=======
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
                updateArStatus()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is needed to run AR features",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
>>>>>>> aa77e5378985bbb16e4493f985f2046e93bc796d
    }
} 