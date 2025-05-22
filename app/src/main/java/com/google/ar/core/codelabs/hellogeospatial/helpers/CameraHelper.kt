package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer

private const val TAG = "CameraHelper"

/**
 * Extension method to ensure proper camera texture creation
 */
fun BackgroundRenderer.createCameraTexture(context: Context): Boolean {
  try {
    // Use reflection to access appropriate method to create texture
    val initMethod = this.javaClass.getDeclaredMethod("createExternalTexture")
    initMethod.isAccessible = true
    initMethod.invoke(this)
    Log.d(TAG, "Successfully created camera texture")
    return true
  } catch (e: Exception) {
    Log.e(TAG, "Error creating camera texture: ${e.message}")
    return false
  }
}

/**
 * Reset the camera system to a clean state
 */
fun resetCamera(context: Context): Boolean {
  try {
    Log.d(TAG, "Attempting to reset camera")
    
    // Force garbage collection to release any lingering camera resources
    System.gc()
    // Wait briefly for GC
    Thread.sleep(100)
    
    // Use Camera1 API to cleanly release camera
    try {
      @Suppress("DEPRECATION")
      val camera = android.hardware.Camera.open()
      camera.release()
      
      Log.d(TAG, "Camera successfully reset")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Error resetting camera: ${e.message}")
      return false
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error in resetCamera", e)
    return false
  }
}

/**
 * Force resets the camera to recover from errors
 */
fun forceCameraReset(context: Context): Boolean {
  try {
    Log.d(TAG, "Forcing camera reset to recover from CAMERA_ERROR")
    
    // First do a basic reset
    resetCamera(context)
    
    // Force garbage collection to release camera resources
    System.gc()
    Thread.sleep(100)
    
    // Try basic Camera1 reset
    try {
      @Suppress("DEPRECATION")
      val camera = android.hardware.Camera.open()
      camera.release()
      Log.d(TAG, "Basic camera reset successful")
    } catch (e: Exception) {
      Log.e(TAG, "Error with basic camera reset: ${e.message}")
    }
    
    return true
  } catch (e: Exception) {
    Log.e(TAG, "Error in forceCameraReset", e)
    return false
  }
}

/**
 * Configure ARCore session for optimal performance in the current environment
 */
fun configureSessionForEnvironment(session: Session, indoor: Boolean = false) {
  try {
    val config = session.config
    
    // Set environment-specific configuration
    if (indoor) {
      // Indoor mode configuration
      config.focusMode = Config.FocusMode.AUTO
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
    } else {
      // Outdoor mode configuration
      config.focusMode = Config.FocusMode.AUTO
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
      config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    }
    
    // Common settings for both environments
    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
    config.geospatialMode = Config.GeospatialMode.ENABLED

    // Apply the configuration
    session.configure(config)
    Log.d(TAG, "Session configured for ${if (indoor) "indoor" else "outdoor"} environment")
  } catch (e: Exception) {
    Log.e(TAG, "Failed to configure session: ${e.message}", e)
  }
} 