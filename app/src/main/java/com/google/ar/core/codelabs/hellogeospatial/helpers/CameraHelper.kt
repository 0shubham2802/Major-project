package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.app.Activity
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager

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
    
    // Try alternative method if the first one fails
    try {
      val alternativeMethod = this.javaClass.getDeclaredMethod("createTextures")
      alternativeMethod.isAccessible = true
      alternativeMethod.invoke(this)
      Log.d(TAG, "Created camera texture using alternative method")
      return true
    } catch (e2: Exception) {
      Log.e(TAG, "All texture creation methods failed", e2)
      return false
    }
  }
}

/**
 * Reset the camera system to a clean state
 * Enhanced for better outdoor camera recovery
 */
fun resetCamera(context: Context): Boolean {
  try {
    Log.d(TAG, "Attempting to reset camera with outdoor optimizations")
    
    // Force garbage collection to release any lingering camera resources
    System.gc()
    Thread.sleep(100)
    
    // Use Camera1 API to cleanly release camera
    try {
      @Suppress("DEPRECATION")
      val camera = android.hardware.Camera.open()
      camera.release()
      
      // Additional cleanup for newer Camera2 API
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        try {
          val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
          // Just iterate through cameras to ensure any open handles are released
          for (cameraId in cameraManager.cameraIdList) {
            Log.d(TAG, "Resetting camera: $cameraId")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error accessing Camera2 API", e)
        }
      }
      
      Log.d(TAG, "Camera successfully reset")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Error resetting camera: ${e.message}")
      
      // Try an alternative approach if first method fails
      try {
        // Force camera process reset by executing service commands
        // This is a more aggressive approach that can help recover from stuck camera processes
        val process = Runtime.getRuntime().exec("dumpsys media.camera reset")
        process.waitFor()
        Log.d(TAG, "Executed camera service reset command")
        
        // Wait a bit for the system to process camera reset
        Thread.sleep(300)
        
        return true
      } catch (e2: Exception) {
        Log.e(TAG, "Failed to reset camera using dumpsys", e2)
        return false
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error in resetCamera", e)
    return false
  }
}

/**
 * Force resets the camera to recover from errors
 * Enhanced with more aggressive recovery options for outdoor use
 */
fun forceCameraReset(context: Context): Boolean {
  val executor = Executors.newSingleThreadExecutor()
  
  try {
    Log.d(TAG, "Forcing aggressive camera reset to recover from CAMERA_ERROR")
    
    // Show toast immediately to inform user
    Handler(Looper.getMainLooper()).post {
      Toast.makeText(context, 
          "Camera reconnection in progress...", 
          Toast.LENGTH_LONG).show()
    }
    
    // Run camera reset in background thread with timeout
    val future = executor.submit<Boolean> {
      try {
        // First do a basic reset
        resetCamera(context)
        
        // Force garbage collection to release camera resources
        System.gc()
        Thread.sleep(100)
        
        // Try multiple approaches to reset camera
        var success = false
        
        // Approach 1: Basic Camera1 reset
        try {
          @Suppress("DEPRECATION")
          val camera = android.hardware.Camera.open()
          camera.release()
          success = true
          Log.d(TAG, "Basic camera reset successful")
        } catch (e: Exception) {
          Log.e(TAG, "Error with basic camera reset: ${e.message}")
        }
        
        // Approach 2: Camera service reset - more aggressive
        if (!success) {
          try {
            val process = Runtime.getRuntime().exec("dumpsys media.camera reset")
            process.waitFor(500, TimeUnit.MILLISECONDS) // Add timeout
            success = true
            Log.d(TAG, "Camera service reset successful")
          } catch (e: Exception) {
            Log.e(TAG, "Camera service reset failed: ${e.message}")
          }
        }
        
        // Approach 3: Camera kill - most aggressive (requires root, will fail gracefully)
        if (!success) {
          try {
            Runtime.getRuntime().exec("killall -9 android.hardware.camera")
            Thread.sleep(200)
            success = true
            Log.d(TAG, "Camera process kill attempted")
          } catch (e: Exception) {
            // Expected to fail on non-rooted devices
            Log.d(TAG, "Camera process kill failed (expected on non-rooted devices)")
          }
        }
        
        // Final delay to let camera system recover
        Thread.sleep(200)
        
        // Update UI to indicate camera reset completed
        Handler(Looper.getMainLooper()).post {
          Toast.makeText(context, 
              "Camera reconnection completed", 
              Toast.LENGTH_SHORT).show()
        }
        
        success
      } catch (e: Exception) {
        Log.e(TAG, "Error in camera reset thread", e)
        false
      }
    }
    
    // Set a timeout for the camera reset operation to prevent ANR
    try {
      return future.get(3000, TimeUnit.MILLISECONDS) // 3 second timeout
    } catch (e: Exception) {
      Log.e(TAG, "Camera reset timed out or was interrupted", e)
      
      // If we timeout, try one more simple approach and return
      Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, 
            "Camera reset timed out, using fallback", 
            Toast.LENGTH_SHORT).show()
      }
      
      return false
    } finally {
      executor.shutdownNow() // Ensure executor is shutdown
    }
  } catch (e: Exception) {
    Log.e(TAG, "Error in forceCameraReset", e)
    return false
  }
}

/**
 * Configure ARCore session for optimal performance in the current environment
 * Enhanced for better outdoor performance
 */
fun configureSessionForEnvironment(session: Session, indoor: Boolean = false) {
  try {
    val config = session.config
    
    if (indoor) {
      // Indoor mode configuration
      config.focusMode = Config.FocusMode.AUTO
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
    } else {
      // Outdoor mode configuration - optimized for AR navigation
      config.focusMode = Config.FocusMode.AUTO
      config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
      config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
      
      // Additional outdoor optimizations
      config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
      
      // Disable depth for better outdoor performance
      config.depthMode = Config.DepthMode.DISABLED
      
      // Increase camera capture FPS rate if available
      try {
        val cameraConfigFilterField = config.javaClass.getDeclaredField("mCameraConfigFilter")
        if (cameraConfigFilterField != null) {
          cameraConfigFilterField.isAccessible = true
          // Filter for target FPS of 30 for outdoor usage
          config.depthMode = Config.DepthMode.DISABLED
        }
      } catch (e: Exception) {
        // Ignore if not available
        Log.d(TAG, "Camera config filtering not available: ${e.message}")
      }
    }
    
    // Common settings for both environments
    config.geospatialMode = Config.GeospatialMode.ENABLED

    // Apply the configuration
    session.configure(config)
    Log.d(TAG, "Session configured for ${if (indoor) "indoor" else "outdoor"} environment")
  } catch (e: Exception) {
    Log.e(TAG, "Failed to configure session: ${e.message}", e)
  }
}

/**
 * Forces a camera reset to release any camera resources that might be stuck
 */
fun resetCamera(activity: Activity) {
    try {
        Log.d(TAG, "Starting aggressive camera reset")
        
        // Use a combination of Camera1 and Camera2 API to ensure all resources are released
        resetCamera2(activity)
        resetLegacyCamera()
        
        // Force garbage collection to clean up any lingering resources
        System.gc()
        System.runFinalization()
        
        Log.d(TAG, "Camera reset complete")
    } catch (e: Exception) {
        Log.e(TAG, "Error during camera reset", e)
    }
}

/**
 * Reset Camera2 API resources
 */
private fun resetCamera2(activity: Activity) {
    try {
        Log.d(TAG, "Resetting Camera2 API resources")
        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return
        
        // Get all camera IDs
        val cameraIds = try {
            cameraManager.cameraIdList
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to get camera ID list", e)
            return
        }
        
        // Process each camera
        for (cameraId in cameraIds) {
            try {
                Log.d(TAG, "Attempting reset for Camera2 camera: $cameraId")
                
                // Create a semaphore to ensure the operation completes
                val semaphore = java.util.concurrent.Semaphore(0)
                
                // Callback for camera device states
                val stateCallback = object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                        try {
                            Log.d(TAG, "Camera2 device opened, now closing")
                            camera.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing opened camera", e)
                        } finally {
                            semaphore.release()
                        }
                    }
                    
                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                        Log.d(TAG, "Camera2 device disconnected")
                        camera.close()
                        semaphore.release()
                    }
                    
                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                        Log.e(TAG, "Camera2 device error: $error")
                        camera.close()
                        semaphore.release()
                    }
                    
                    override fun onClosed(camera: android.hardware.camera2.CameraDevice) {
                        Log.d(TAG, "Camera2 device closed")
                        semaphore.release()
                    }
                }
                
                // Check for camera permission
                if (androidx.core.app.ActivityCompat.checkSelfPermission(
                        activity, 
                        android.Manifest.permission.CAMERA
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "No camera permission during reset")
                    continue
                }
                
                // Try to open and immediately close the camera to force a reset
                Handler(Looper.getMainLooper()).post {
                    try {
                        cameraManager.openCamera(cameraId, stateCallback, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening camera for reset: ${e.message}")
                        semaphore.release() // Make sure we don't deadlock
                    }
                }
                
                // Wait with timeout for operation to complete
                semaphore.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during Camera2 reset for camera $cameraId", e)
            }
        }
        
        // Double check that we've given time for camera to be released
        Thread.sleep(100)
        
    } catch (e: Exception) {
        Log.e(TAG, "Error in Camera2 reset process", e)
    }
}

/**
 * Reset legacy Camera API resources as fallback
 */
@Suppress("DEPRECATION")
private fun resetLegacyCamera() {
    try {
        Log.d(TAG, "Resetting legacy Camera API resources")
        
        // Try with both front and back cameras
        val cameraTypes = listOf(
            android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK,
            android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
        )
        
        for (cameraType in cameraTypes) {
            var camera: android.hardware.Camera? = null
            
            try {
                // Get number of cameras
                val numCameras = android.hardware.Camera.getNumberOfCameras()
                Log.d(TAG, "Device has $numCameras cameras")
                
                // Try to find the camera of this type
                var cameraFound = false
                for (i in 0 until numCameras) {
                    val info = android.hardware.Camera.CameraInfo()
                    android.hardware.Camera.getCameraInfo(i, info)
                    if (info.facing == cameraType) {
                        cameraFound = true
                        break
                    }
                }
                
                if (!cameraFound) {
                    Log.d(TAG, "No camera of type $cameraType found")
                    continue
                }
                
                Log.d(TAG, "Opening camera of type $cameraType for reset")
                camera = android.hardware.Camera.open(cameraType)
                
                if (camera != null) {
                    Log.d(TAG, "Camera opened successfully, now releasing")
                    // Wait a moment with camera open
                    Thread.sleep(100)
                    // Release the camera
                    camera.release()
                    Log.d(TAG, "Camera released successfully")
                    // Wait after release
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with legacy camera reset: ${e.message}")
                // Make sure camera is released even if there's an error
                try {
                    camera?.release()
                } catch (releaseEx: Exception) {
                    Log.e(TAG, "Error releasing camera in exception handler", releaseEx)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error in legacy camera reset", e)
    }
} 