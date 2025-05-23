/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.codelabs.hellogeospatial.helpers.resetCamera
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoApplication
import com.google.ar.core.codelabs.hellogeospatial.FallbackActivity
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.SplitScreenActivity
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoRenderer

/**
 * Manages an ARCore Session using the Android Lifecycle APIs.
 */
class ARCoreSessionLifecycleHelper(
  val activity: Activity,
  val features: Set<Session.Feature> = setOf()
) : DefaultLifecycleObserver {
  var installRequested = false
  var session: Session? = null
    private set

  var exceptionCallback: ((Exception) -> Unit)? = null
  var beforeSessionResume: ((Session) -> Unit)? = null

  private var retryCount = 0
  private var lastException: Exception? = null
  private val MAX_RETRY_ATTEMPTS = 3
  private val RETRY_DELAY_MS = 500L

  /**
   * Creates a new ARCore Session or resumes an existing session if available.
   * Handles cleaning up the current session if it exists.
   */
  private var sessionCreateAttempts = 0
  
  override fun onResume(owner: LifecycleOwner) {
    onResume()
  }

  // Non-lifecycle version of onResume with improved camera handling
  fun onResume() {
    val session = this.session ?: tryCreateSession() ?: return
    
    // Add a try-catch block specific to camera initialization
    try {
      Log.d(TAG, "Attempting to initialize camera for ARCore...")
      
      // Force camera permission check again to ensure it's granted
      if (ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) 
          != PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "Camera permission not granted during onResume!")
        GeoPermissionsHelper.requestPermissions(activity)
        return
      }
      
      // Make multiple attempts to initialize the camera
      var attempts = 0
      var success = false
      val maxAttempts = 3
      
      while (!success && attempts < maxAttempts) {
        attempts++
        try {
          // Explicitly release camera before attempting to acquire it
          if (attempts > 1) {
            Log.d(TAG, "Attempt $attempts: Forcing camera reset before initialization")
            resetCamera(activity)
            Thread.sleep(300L) // Wait a moment for camera to release
          }
          
          // Set session camera texture name if needed
          val textureId = getSessionCameraTextureId()
          if (textureId > 0) {
            Log.d(TAG, "Setting camera texture ID: $textureId")
            session.setCameraTextureName(textureId)
          }
          
          // Call session resume with additional timeout protection
          val resumeStartTime = System.currentTimeMillis()
          session.resume()
          val resumeTime = System.currentTimeMillis() - resumeStartTime
          Log.d(TAG, "Session resumed in ${resumeTime}ms on attempt $attempts")
          
          success = true
          this.session = session
          retryCount = 0 // Reset retry count on success
        } catch (e: CameraNotAvailableException) {
          Log.e(TAG, "Camera not available on attempt $attempts", e)
          
          if (attempts >= maxAttempts) {
            Log.e(TAG, "Failed to initialize camera after $maxAttempts attempts")
            exceptionCallback?.invoke(e)
          } else {
            Log.d(TAG, "Will retry camera initialization")
            Thread.sleep(500L * attempts) // Progressive backoff
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error on camera initialization attempt $attempts", e)
          
          if (attempts >= maxAttempts) {
            Log.e(TAG, "Failed after $maxAttempts attempts with error", e)
            exceptionCallback?.invoke(e)
          } else {
            Log.d(TAG, "Will retry after general error")
            Thread.sleep(500L * attempts)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exception during camera initialization", e)
      exceptionCallback?.invoke(e)
    }
  }

  // Non-lifecycle version of onPause
  fun onPause() {
    try {
      session?.pause()
    } catch (e: Exception) {
      Log.e(TAG, "Exception during session pause", e)
    }
  }

  // Non-lifecycle version of onDestroy
  fun onDestroy() {
    try {
      session?.close()
      session = null
    } catch (e: Exception) {
      Log.e(TAG, "Exception during session close", e)
    }
  }

  override fun onPause(owner: LifecycleOwner) {
    onPause()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    onDestroy()
  }

  private fun tryCreateSession(): Session? {
    // If we've tried and failed to create a session more than 3 times,
    // fall back to a non-AR experience to avoid continuous crashes
    if (sessionCreateAttempts > 3) {
      Log.e(TAG, "Too many session creation failures, falling back to non-AR experience")
      handleFatalError("Failed to create AR session after multiple attempts")
      return null
    }
    
    sessionCreateAttempts++
    Log.d(TAG, "Attempting to create session (attempt #$sessionCreateAttempts)")
    
    // The app must have been given the CAMERA permission. If we don't have it yet, request it.
    if (!GeoPermissionsHelper.hasGeoPermissions(activity)) {
      GeoPermissionsHelper.requestPermissions(activity)
      return null
    }

    return try {
      // Clear any existing session
      clearSession()
      
      // Request installation if necessary.
      when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)!!) {
        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
          installRequested = true
          // tryCreateSession will be called again, so we return null for now.
          return null
        }
        ArCoreApk.InstallStatus.INSTALLED -> {
          // Left empty; nothing needs to be done.
        }
      }

      // Create a session if Google Play Services for AR is installed and up to date.
      val newSession = Session(activity, features)
      
      // Configure the session
      val config = Config(newSession)
      config.geospatialMode = Config.GeospatialMode.ENABLED
      
      // Check depth support
      if (newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
        config.depthMode = Config.DepthMode.AUTOMATIC
      }
      
      // Check if device is low-end
      if (HelloGeoApplication.shouldUseLowResourceMode()) {
        // Apply optimizations for low-end devices
        Log.i(TAG, "Using performance settings for low-resource device")
        config.focusMode = Config.FocusMode.FIXED
      } else {
        config.focusMode = Config.FocusMode.AUTO
      }
      
      // Apply configuration
      newSession.configure(config)
      
      Log.d(TAG, "Session created successfully")
      
      // Reset retry count on success
      sessionCreateAttempts = 0
      lastException = null
      
      newSession
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create AR session", e)
      lastException = e
      exceptionCallback?.invoke(e)
      null
    }
  }
  
  /**
   * Force recreate the session to clear any camera issues
   */
  fun recreateSession() {
    Log.d(TAG, "Attempting to recreate AR session")
    
    // Force session close
    session?.let {
      try {
        it.pause()
        it.close()
      } catch (e: Exception) {
        Log.e(TAG, "Error closing existing session", e)
      }
    }
    
    // Release camera resources
    resetCamera(activity)
    
    // Force garbage collection
    System.gc()
    System.runFinalization()
    
    // Wait a moment for resources to be freed
    try {
      Thread.sleep(100)
    } catch (e: InterruptedException) {
      Log.e(TAG, "Sleep interrupted", e)
    }
    
    // Try to create a new session
    session = tryCreateSession()
    
    // Resume if created successfully
    session?.let {
      try {
        it.resume()
        Log.d(TAG, "Session recreated and resumed successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to resume recreated session", e)
        exceptionCallback?.invoke(e)
      }
    }
  }
  
  /**
   * Clears the current session safely
   */
  private fun clearSession() {
    // Close the current session if it exists
    try {
      session?.let {
        it.pause()
        it.close()
        session = null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error cleaning up session", e)
    }
  }
  
  /**
   * Handle a fatal error that requires fallback to non-AR mode
   */
  private fun handleFatalError(message: String) {
    Log.e(TAG, "Fatal AR error: $message")
    try {
      // Show error on UI thread
      val mainHandler = Handler(android.os.Looper.getMainLooper())
      mainHandler.post {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        
        // Launch fallback activity
        try {
          val intent = Intent(activity, FallbackActivity::class.java)
          intent.putExtra("ERROR_MESSAGE", message)
          activity.startActivity(intent)
          activity.finish()
        } catch (e: Exception) {
          Log.e(TAG, "Failed to start fallback activity", e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling fatal AR error", e)
    }
  }

  // Helper method to get camera texture ID from renderer
  private fun getSessionCameraTextureId(): Int {
    try {
      // Try to get texture ID from HelloGeoRenderer or any active GL context
      // First check if there's a renderer reference in the activity
      if (activity is HelloGeoActivity) {
        val helloGeoActivity = activity as HelloGeoActivity
        try {
          // Check if the renderer is initialized using safer approach
          val rendererField = HelloGeoActivity::class.java.getDeclaredField("renderer")
          rendererField.isAccessible = true
          val renderer = rendererField.get(helloGeoActivity) as? HelloGeoRenderer
          
          if (renderer != null) {
            val backgroundRenderer = renderer.accessBackgroundRenderer()
            if (backgroundRenderer != null) {
              Log.d(TAG, "Found camera texture ID from HelloGeoActivity")
              return backgroundRenderer.getCameraColorTexture().textureId
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error accessing HelloGeoActivity renderer", e)
        }
      }
      
      // Fallback to searching for SplitScreenActivity
      if (activity is SplitScreenActivity) {
        try {
          val splitScreenActivity = activity as SplitScreenActivity
          // Use reflection to access renderer
          val rendererField = SplitScreenActivity::class.java.getDeclaredField("renderer")
          rendererField.isAccessible = true
          val renderer = rendererField.get(splitScreenActivity) as? HelloGeoRenderer
          
          if (renderer != null) {
            val backgroundRenderer = renderer.accessBackgroundRenderer()
            if (backgroundRenderer != null) {
              Log.d(TAG, "Found camera texture ID from SplitScreenActivity")
              return backgroundRenderer.getCameraColorTexture().textureId
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error accessing SplitScreenActivity renderer", e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error getting camera texture ID", e)
    }
    
    // Return 0 if we couldn't get a valid texture ID
    return 0
  }

  companion object {
    private const val TAG = "ARCoreSessionHelper"
  }
}
