package com.google.ar.core.codelabs.hellogeospatial

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.multidex.MultiDex
import java.lang.ref.WeakReference

/**
 * Application class for the AR application.
 */
class ARApplication : Application() {
    companion object {
        private const val TAG = "ARApplication"
        private var instance: ARApplication? = null
        
        fun getInstance(): ARApplication? {
            return instance
        }
    }
    
    private var cameraManager: CameraManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundActivity: WeakReference<Activity>? = null
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "AR Application initialized")
        
        // Initialize camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        // Register camera availability callback
        setupCameraAvailabilityCallback()
        
        // Track activity lifecycle for better resource management
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            
            override fun onActivityStarted(activity: Activity) {}
            
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = WeakReference(activity)
            }
            
            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivity?.get() == activity) {
                    foregroundActivity = null
                }
            }
            
            override fun onActivityStopped(activity: Activity) {}
            
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    
    /**
     * Get the current foreground activity
     */
    fun getCurrentActivity(): Activity? {
        return foregroundActivity?.get()
    }
    
    /**
     * Force release camera resources at the application level
     */
    fun forceReleaseCameraResources() {
        // Force garbage collection to help release resources
        System.gc()
        
        // Log camera status
        logCameraStatus()
        
        // Use a dummy camera operation to trigger device release
        try {
            if (cameraManager?.cameraIdList?.isNotEmpty() == true) {
                // Just enumerate cameras to trigger potential cleanup
                cameraManager?.cameraIdList?.forEach { _ ->
                    // Do nothing, just triggering the API
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera resource release", e)
        }
        
        mainHandler.postDelayed({
            Log.d(TAG, "Delayed camera resource release completed")
            logCameraStatus()
        }, 500)
    }
    
    private fun logCameraStatus() {
        try {
            val camerasAvailable = cameraManager?.cameraIdList?.size ?: 0
            Log.d(TAG, "Number of cameras available: $camerasAvailable")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging camera status", e)
        }
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