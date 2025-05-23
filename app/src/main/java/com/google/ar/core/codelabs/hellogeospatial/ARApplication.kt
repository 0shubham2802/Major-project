package com.google.ar.core.codelabs.hellogeospatial

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.multidex.MultiDex

/**
 * Application class for the AR application.
 */
class ARApplication : Application() {
    companion object {
        private const val TAG = "ARApplication"
    }
    
    private var cameraManager: CameraManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AR Application initialized")
        
        // Initialize camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        // Register camera availability callback
        setupCameraAvailabilityCallback()
    }
    
    private fun setupCameraAvailabilityCallback() {
        try {
            cameraManager?.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    super.onCameraAvailable(cameraId)
                    Log.d(TAG, "Camera $cameraId became available")
                }
                
                override fun onCameraUnavailable(cameraId: String) {
                    super.onCameraUnavailable(cameraId)
                    Log.d(TAG, "Camera $cameraId became unavailable")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register camera availability callback", e)
        }
    }
} 