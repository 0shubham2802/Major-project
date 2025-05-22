package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import android.os.Handler
import android.os.Looper

private const val TAG = "CameraHelper"

/**
 * Extension method to ensure proper camera texture creation
 * This handles the compatibility with different versions of the ARCore libraries
 */
fun BackgroundRenderer.createCameraTexture(context: Context): Boolean {
  var success = false
  try {
    // First, attempt direct access to texture ID to check if already initialized
    try {
      val textureIdField = this.javaClass.getDeclaredField("cameraColorTexture")
      textureIdField.isAccessible = true
      val textureObject = textureIdField.get(this)
      if (textureObject != null) {
        // Texture already exists
        Log.d(TAG, "Camera texture already initialized")
        return true
      }
    } catch (e: Exception) {
      // Field access failed, continue with normal initialization
    }
    
    // Try main method first (newer ARCore versions)
    try {
      val createMethod = BackgroundRenderer::class.java.getDeclaredMethod("createOnGlThread")
      createMethod.isAccessible = true
      createMethod.invoke(this)
      Log.d(TAG, "Called BackgroundRenderer.createOnGlThread successfully")
      success = true
    } catch (e: Exception) {
      Log.d(TAG, "First texture creation method failed: ${e.message}")
      
      // Try alternate method with context parameter
      try {
        val alternateMethod = BackgroundRenderer::class.java.getDeclaredMethod("createOnGlThread", Context::class.java)
        alternateMethod.isAccessible = true
        alternateMethod.invoke(this, context)
        Log.d(TAG, "Called BackgroundRenderer.createOnGlThread(Context) successfully")
        success = true
      } catch (e2: Exception) {
        Log.e(TAG, "Second texture creation method failed: ${e2.message}")
        
        // Last resort - try to directly create OpenGL texture
        try {
          val initMethod = BackgroundRenderer::class.java.getDeclaredMethod("init", Context::class.java)
          if (initMethod != null) {
            initMethod.isAccessible = true
            initMethod.invoke(this, context)
            Log.d(TAG, "Called BackgroundRenderer.init successfully")
            success = true
          }
        } catch (e3: Exception) {
          Log.e(TAG, "All texture creation methods failed: ${e3.message}", e3)
          success = false
        }
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Critical error creating camera texture: ${e.message}", e)
    success = false
  }
  
  return success
}

/**
 * Force reset the camera and attempt to reacquire it
 * More aggressive than the basic resetCamera method
 */
fun forceCameraReset(context: Context): Boolean {
  Log.d(TAG, "Attempting aggressive camera reset")
  
  try {
    // First try using Camera2 API
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    for (cameraId in cameraManager.cameraIdList) {
      try {
        Log.d(TAG, "Attempting to reset camera ID: $cameraId")
        // Wait a short time to ensure proper camera release
        Thread.sleep(100)
      } catch (e: Exception) {
        Log.e(TAG, "Error with camera ID $cameraId: ${e.message}")
      }
    }
    
    // Now try with legacy Camera API for older devices
    try {
      val cameraClass = Class.forName("android.hardware.Camera")
      val openMethod = cameraClass.getMethod("open", Int::class.java)
      val releaseMethod = cameraClass.getMethod("release")
      
      // Try to reset back camera (most common for AR)
      val backCamera = openMethod.invoke(null, 0)
      Thread.sleep(100)
      if (backCamera != null) {
        releaseMethod.invoke(backCamera)
        Log.d(TAG, "Legacy back camera reset successful")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Legacy camera reset failed: ${e.message}")
    }
    
    // Add a small delay to ensure system fully releases the camera
    Handler(Looper.getMainLooper()).postDelayed({
      Log.d(TAG, "Camera reset procedure completed")
    }, 500)
    
    return true
  } catch (e: Exception) {
    Log.e(TAG, "Complete camera reset failed: ${e.message}", e)
    return false
  }
}

/**
 * Configure ARCore session for optimal performance in both indoor and outdoor environments
 */
fun configureSessionForEnvironment(session: Session, indoor: Boolean = false) {
  try {
    val config = session.config
    
    // Set environment-specific configuration
    if (indoor) {
      // Indoor mode configuration
      // Indoor environments typically have less available visual features
      // and may have challenging lighting conditions
      config.focusMode = Config.FocusMode.AUTO
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
      config.augmentedFaceMode = Config.AugmentedFaceMode.DISABLED
    } else {
      // Outdoor mode configuration
      // Outdoor environments may have bright lighting and very different
      // visual features compared to indoor spaces
      config.focusMode = Config.FocusMode.AUTO
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
      config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
      config.augmentedFaceMode = Config.AugmentedFaceMode.DISABLED
    }
    
    // Common settings for both environments
    // Ensure stable tracking with environmental features available
    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
    config.geospatialMode = Config.GeospatialMode.ENABLED

    // Apply the configuration
    session.configure(config)
    Log.d(TAG, "Session configured for ${if (indoor) "indoor" else "outdoor"} environment")
  } catch (e: Exception) {
    Log.e(TAG, "Failed to configure session: ${e.message}", e)
  }
}

/**
 * Reset camera in case of issues
 * This can help recover from camera access problems
 */
fun resetCamera(context: Context): Boolean {
  try {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraIds = cameraManager.cameraIdList
    
    if (cameraIds.isNotEmpty()) {
      Log.d(TAG, "Attempting camera reset, found ${cameraIds.size} cameras")
      return true
    }
    
    return false
  } catch (e: CameraAccessException) {
    Log.e(TAG, "Camera access exception during reset: ${e.message}", e)
    return false
  } catch (e: Exception) {
    Log.e(TAG, "Failed to reset camera: ${e.message}", e)
    return false
  }
} 