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
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.codelabs.hellogeospatial.helpers.resetCamera


class ARCoreSessionLifecycleHelper(
  val activity: Activity,
  val features: Set<Session.Feature> = setOf()
) : DefaultLifecycleObserver {
  
  companion object {
    private const val TAG = "ARCoreSessionHelper"
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 500L
  }
  
  var installRequested = false
  var session: Session? = null
    private set

  var exceptionCallback: ((Exception) -> Unit)? = null
  var beforeSessionResume: ((Session) -> Unit)? = null
  var onLifecycleOwner: LifecycleOwner? = null
    set(value) {
      field = value
      field?.lifecycle?.addObserver(this)
    }

  private var retryCount = 0
  private var lastException: Exception? = null

  private fun tryCreateSession(): Session? {
    // The app must have been given the CAMERA permission. If we don't have it yet, request it.
    if (!GeoPermissionsHelper.hasGeoPermissions(activity)) {
      GeoPermissionsHelper.requestPermissions(activity)
      return null
    }

    return try {
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
      Log.d(TAG, "Session created successfully")
      
      // Reset retry count on success
      retryCount = 0
      lastException = null
      
      newSession
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create AR session", e)
      lastException = e
      exceptionCallback?.invoke(e)
      null
    }
  }

  // Non-lifecycle version of onResume
  fun onResume() {
    val session = this.session ?: tryCreateSession() ?: return
    
    try {
      beforeSessionResume?.invoke(session)
      session.resume()
      this.session = session
      
      // Reset retry count on successful resume
      retryCount = 0
      Log.d(TAG, "Session resumed successfully")
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during onResume", e)
      
      // Try to recover from camera not available
      if (retryCount < MAX_RETRY_ATTEMPTS) {
        retryCount++
        Log.d(TAG, "Retrying session resume (attempt $retryCount)")
        
        // Try to reset camera
        resetCamera(activity)
        
        // Wait a moment before trying again
        try {
          Thread.sleep(RETRY_DELAY_MS)
          onResume() // Recursive call with retry
        } catch (ie: InterruptedException) {
          Log.e(TAG, "Retry interrupted", ie)
          exceptionCallback?.invoke(e)
        }
      } else {
        Log.e(TAG, "Maximum retry attempts reached", e)
        exceptionCallback?.invoke(e)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exception during session resume", e)
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

  override fun onResume(owner: LifecycleOwner) {
    onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    onPause()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    onDestroy()
  }
}
