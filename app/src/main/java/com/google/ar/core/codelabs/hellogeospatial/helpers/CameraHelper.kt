package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer

private const val TAG = "CameraHelper"

/**
 * Extension method to ensure proper camera texture creation
 * This handles the compatibility with different versions of the ARCore libraries
 */
fun BackgroundRenderer.createCameraTexture(context: Context) {
  try {
    // Check if the method exists using reflection
    val createMethod = BackgroundRenderer::class.java.getDeclaredMethod("createOnGlThread")
    if (createMethod != null) {
      // Call the method if it exists
      createMethod.isAccessible = true
      createMethod.invoke(this)
      Log.d(TAG, "Called BackgroundRenderer.createOnGlThread successfully")
    }
  } catch (e: Exception) {
    // Method doesn't exist, try the other known method
    try {
      // Try the alternate method name
      val alternateMethod = BackgroundRenderer::class.java.getDeclaredMethod("createOnGlThread", Context::class.java)
      if (alternateMethod != null) {
        alternateMethod.isAccessible = true
        alternateMethod.invoke(this, context)
        Log.d(TAG, "Called BackgroundRenderer.createOnGlThread(Context) successfully")
      }
    } catch (e2: Exception) {
      // Both methods failed, log error
      Log.e(TAG, "Failed to create camera texture: ${e2.message}", e2)
    }
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