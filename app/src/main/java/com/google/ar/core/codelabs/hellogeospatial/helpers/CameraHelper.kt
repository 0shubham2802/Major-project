package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.util.Log
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