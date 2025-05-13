/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.opengl.Matrix
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException


class HelloGeoRenderer(val context: Context) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "HelloGeoRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
    
    // Constants for fallback detection - increasing values to give more time
    private const val MAX_EARTH_INIT_WAIT_TIME_MS = 120000L // 2 minutes to initialize Earth
    private const val MAX_FRAMES_WITHOUT_EARTH_TRACKING = 900 // ~30 seconds at 30fps
    
    // Track Earth quality
    private const val REQUIRED_TRACKING_CONFIDENCE = 0.7f // Min confidence to consider tracking reliable
  }

  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectTexture: Texture
  
  // Directional arrow for navigation
  lateinit var arrowMesh: Mesh
  lateinit var arrowShader: Shader
  lateinit var arrowTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  private var arSession: Session? = null
  
  // Store multiple anchors for navigation path
  private val anchors = mutableListOf<Anchor>()
  private var destinationAnchor: Anchor? = null
  private var isNavigating = false

  // Initialize display rotation helper with context - it accepts Context instead of Activity now
  val displayRotationHelper = DisplayRotationHelper(context)
  
  // Initialize tracking state helper with AppCompatActivity if available, or null
  val trackingStateHelper = when (context) {
    is AppCompatActivity -> TrackingStateHelper(context)
    else -> null // For non-Activity contexts, we'll handle tracking state differently
  }
  
  private var lastEarthTrackingErrorTime = 0L
  private var earthInitializedTime = 0L
  private var framesWithoutEarthTracking = 0
  private var hasFallenBackToMapMode = false
  private var lastTrackingQualityWarningTime = 0L

  // Reference to the view - can be HelloGeoView or ARActivity's view
  private var helloGeoView: HelloGeoView? = null
  
  // Set view reference
  fun setView(view: HelloGeoView) {
    this.helloGeoView = view
  }

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj")
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)
          
      // Load navigation arrow assets
      try {
        // Use existing marker as arrow since the arrow models don't exist
        arrowTexture = virtualObjectTexture
        arrowMesh = virtualObjectMesh
        arrowShader = virtualObjectShader
        Log.d(TAG, "Using existing marker as arrow due to missing arrow assets")
      } catch (e: IOException) {
        Log.e(TAG, "Failed to read navigation assets", e)
        // Fallback to using the same assets as the marker
        arrowTexture = virtualObjectTexture
        arrowMesh = virtualObjectMesh
        arrowShader = virtualObjectShader
      }

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, true) // Enable occlusion for better realism
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {
    try {
      val session = session ?: return

      try {
        //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
          session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
          hasSetTextureNames = true
          Log.d(TAG, "Camera texture names set")
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session)

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame =
          try {
            session.update()
          } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            showError("Camera not available. Try restarting the app.")
            return
          }

        val camera = frame.camera

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer.updateDisplayGeometry(frame)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        updateTrackingState(camera.trackingState)

        // -- Draw background
        if (frame.timestamp != 0L) {
          // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
          // drawing possible leftover data from previous sessions if the texture is reused.
          backgroundRenderer.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
          Log.d(TAG, "Camera tracking state is PAUSED, skipping frame rendering")
          return
        }

        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        //</editor-fold> 

        val earth = session.earth
        if (earth == null) {
          Log.d(TAG, "Earth is null, waiting for Earth to initialize... (${System.currentTimeMillis() - earthInitializedTime} ms elapsed)")
          // No need to show an error - just wait
          updateStatusText(null, null)
          
          // Track time waiting for Earth to initialize
          if (earthInitializedTime == 0L) {
            earthInitializedTime = System.currentTimeMillis()
            Log.d(TAG, "Starting Earth initialization timer")
            
            // Show a toast to let user know we're initializing Earth
            updateStatusText(null, null)
          } else if (System.currentTimeMillis() - earthInitializedTime > MAX_EARTH_INIT_WAIT_TIME_MS) {
            // Earth hasn't initialized in the maximum wait time, fall back to map mode
            Log.e(TAG, "Earth initialization timed out after ${MAX_EARTH_INIT_WAIT_TIME_MS}ms")
            handlePersistentEarthFailure("Earth initialization timed out")
          }
          
          return
        }

        // Reset Earth initialization timer once Earth is available
        if (earthInitializedTime > 0) {
          Log.d(TAG, "Earth initialized after ${System.currentTimeMillis() - earthInitializedTime}ms")
          earthInitializedTime = 0
          
          // Show a success toast when Earth is initialized
          updateStatusText(earth, null)
        }

        // Check Earth tracking state
        if (earth.trackingState != TrackingState.TRACKING) {
          val stateDescription = when(earth.trackingState) {
            TrackingState.PAUSED -> "PAUSED"
            TrackingState.STOPPED -> "STOPPED" 
            else -> "UNKNOWN"
          }
          
          Log.d(TAG, "Earth tracking state: $stateDescription, frames without tracking: $framesWithoutEarthTracking")
          updateStatusText(earth, null)
          
          // Increment counter for frames without Earth tracking
          framesWithoutEarthTracking++
          
          // Wait at least 10 seconds before showing error to the user
          if (System.currentTimeMillis() - lastEarthTrackingErrorTime > 10000) {
            lastEarthTrackingErrorTime = System.currentTimeMillis()
            updateStatusText(earth, null)
          }
          
          // Check if we've gone too long without Earth tracking
          if (framesWithoutEarthTracking > MAX_FRAMES_WITHOUT_EARTH_TRACKING) {
            Log.e(TAG, "Earth tracking failed persistently after ${framesWithoutEarthTracking} frames")
            handlePersistentEarthFailure("Earth tracking failed persistently")
          }
          
          return
        }

        // Reset counter since we have successful tracking
        if (framesWithoutEarthTracking > 0) {
          Log.d(TAG, "Earth tracking resumed after ${framesWithoutEarthTracking} frames")
          framesWithoutEarthTracking = 0
        }
        
        // Earth is tracking - evaluate quality
        val cameraGeospatialPose = earth.cameraGeospatialPose
        val horizontalAccuracy = cameraGeospatialPose.horizontalAccuracy
        val headingAccuracy = cameraGeospatialPose.headingAccuracy
        
        // Calculate a confidence metric (0-1) - lower is better for accuracies
        val locationConfidence = if (horizontalAccuracy > 0) Math.min(1.0, 10.0 / horizontalAccuracy) else 0.0
        val headingConfidence = if (headingAccuracy > 0) Math.min(1.0, 15.0 / headingAccuracy) else 0.0
        val overallConfidence = (locationConfidence + headingConfidence) / 2.0
        
        val qualityString = when {
            overallConfidence >= 0.8 -> "Excellent"
            overallConfidence >= REQUIRED_TRACKING_CONFIDENCE -> "Good"
            overallConfidence >= 0.4 -> "Fair"
            else -> "Poor"
        }
        
        Log.d(TAG, "Earth tracking quality: $qualityString ($overallConfidence), " +
              "location accuracy: ${horizontalAccuracy}m, heading accuracy: ${headingAccuracy}°")
        
        // Update UI with quality indicators
        updateStatusText(earth, earth.cameraGeospatialPose)
        
        // Provide tracking quality feedback to user if poor
        if (overallConfidence < REQUIRED_TRACKING_CONFIDENCE && 
            System.currentTimeMillis() - lastTrackingQualityWarningTime > 30000) {
          lastTrackingQualityWarningTime = System.currentTimeMillis()
          updateStatusText(earth, earth.cameraGeospatialPose)
        }
        
        // Log tracking state details
        Log.d(TAG, "Earth tracking state: TRACKING, camera pose: " +
              "lat=${cameraGeospatialPose.latitude}, " +
              "lng=${cameraGeospatialPose.longitude}, " +
              "alt=${cameraGeospatialPose.altitude}, " +
              "accuracy=${horizontalAccuracy}m")
        
        // Update map position
        updateMapPosition(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude, cameraGeospatialPose.heading)
        
        // Update AR navigation visuals if navigating
        if (isNavigating) {
          updateNavigationAnchors(earth, cameraGeospatialPose)
        }
        
        updateStatusText(earth, earth.cameraGeospatialPose)

        // Draw all anchors
        for (anchor in anchors) {
          if (anchor.trackingState == TrackingState.TRACKING) {
            render.renderObject(anchor, arrowMesh, arrowShader)
          }
        }

        // Draw the destination anchor with a different model
        destinationAnchor?.let {
          if (it.trackingState == TrackingState.TRACKING) {
            render.renderCompassAtAnchor(it)
          }
        }

        // Compose the virtual scene with the background.
        backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
      } catch (e: Exception) {
        Log.e(TAG, "Error during onDrawFrame inner block", e)
        showError("Error during onDrawFrame: $e")
      }
    } catch (e: Exception) {
      // Catch any GL thread errors that might crash the app
      Log.e(TAG, "Critical error in GL thread", e)
      updateStatusText(null, null)
      showError("Critical rendering error: ${e.message}")
    }
  }
  
  private fun updateNavigationAnchors(earth: Earth, cameraGeospatialPose: GeospatialPose) {
    // Update existing anchors or create new ones based on the current path
    // This would be called during navigation to update directional arrows
    
    // For simplicity, we're just going to keep the existing anchors
    // A full implementation would update the path based on current position
  }
  
  fun createAnchorAtLocation(latitude: Double, longitude: Double): Anchor? {
    val earth = session?.earth ?: run {
      Log.e(TAG, "Cannot create anchor: Earth is null")
      return null
    }
    
    if (earth.trackingState != TrackingState.TRACKING) {
      Log.e(TAG, "Cannot create anchor: Earth is not tracking. Current state: ${earth.trackingState}")
      return null
    }
    
    val altitude = earth.cameraGeospatialPose.altitude - 1.0
    Log.d(TAG, "Creating anchor at: lat=$latitude, lng=$longitude, altitude=$altitude")
    
    try {
      val anchor = earth.createAnchor(
        latitude,
        longitude,
        altitude,
        0f,
        0f,
        0f,
        1f
      )
      
      // Store as destination anchor
      destinationAnchor = anchor
      
      // Update map marker
      updateMapMarker(LatLng(latitude, longitude))
      
      Log.d(TAG, "Anchor created successfully: $anchor")
      return anchor
    } catch (e: Exception) {
      Log.e(TAG, "Error creating anchor", e)
      return null
    }
  }
  
  fun createDirectionalAnchor(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Anchor? {
    val earth = session?.earth ?: return null
    if (earth.trackingState != TrackingState.TRACKING) {
      return null
    }
    
    // Calculate bearing between points
    val bearing = calculateBearing(startLat, startLng, endLat, endLng)
    
    // Create anchor at midpoint with proper orientation
    val midLat = (startLat + endLat) / 2
    val midLng = (startLng + endLng) / 2
    val altitude = earth.cameraGeospatialPose.altitude - 1.0
    
    // Convert bearing to quaternion (rotate arrow to point in right direction)
    val radians = Math.toRadians(bearing)
    val qx = 0f
    val qy = Math.sin(radians / 2).toFloat()
    val qz = 0f
    val qw = Math.cos(radians / 2).toFloat()
    
    val anchor = earth.createAnchor(
      midLat, 
      midLng,
      altitude,
      qx, qy, qz, qw
    )
    
    anchors.add(anchor)
    return anchor
  }
  
  private fun calculateBearing(startLat: Double, startLng: Double, endLat: Double, endLng: Double): Double {
    val startLatRad = Math.toRadians(startLat)
    val startLngRad = Math.toRadians(startLng)
    val endLatRad = Math.toRadians(endLat)
    val endLngRad = Math.toRadians(endLng)
    
    val dLng = endLngRad - startLngRad
    
    val y = Math.sin(dLng) * Math.cos(endLatRad)
    val x = Math.cos(startLatRad) * Math.sin(endLatRad) -
            Math.sin(startLatRad) * Math.cos(endLatRad) * Math.cos(dLng)
    
    var bearing = Math.toDegrees(Math.atan2(y, x))
    if (bearing < 0) {
      bearing += 360.0
    }
    
    return bearing
  }
  
  fun createPathAnchors(path: List<LatLng>) {
    clearAnchors() // Remove existing anchors
    
    try {
      // Create destination anchor at the end of the path
      if (path.isNotEmpty()) {
        val destination = path.last()
        createAnchorAtLocation(destination.latitude, destination.longitude)
        
        // Create directional anchors along the path
        if (path.size > 1) {
          for (i in 0 until path.size - 1) {
            val start = path[i]
            val end = path[i + 1]
            createDirectionalAnchor(start.latitude, start.longitude, end.latitude, end.longitude)
          }
        }
        
        isNavigating = true
        Log.d(TAG, "Created ${anchors.size} path anchors and 1 destination anchor")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error creating path anchors", e)
    }
  }
  
  fun clearAnchors() {
    try {
      // Detach all anchors
      for (anchor in anchors) {
        anchor.detach()
      }
      anchors.clear()
      
      destinationAnchor?.detach()
      destinationAnchor = null
      
      isNavigating = false
      Log.d(TAG, "Cleared all anchors")
    } catch (e: Exception) {
      Log.e(TAG, "Error clearing anchors", e)
    }
  }

  var earthAnchor: Anchor? = null

  fun onMapClick(latLng: LatLng) {
    val earth = session?.earth ?: return
    if(earth.trackingState != TrackingState.TRACKING){
      return
    }
    earthAnchor?.detach()
    earthAnchor = earth.createAnchor(
      latLng.latitude,
      latLng.longitude,
      earth.cameraGeospatialPose.altitude - 1.3,
      0f,
      0f,
      0f,
      1f
    )

    updateMapMarker(latLng)
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    anchor.pose.toMatrix(modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }
  
  private fun SampleRender.renderObject(anchor: Anchor, mesh: Mesh, shader: Shader) {
    // Get the current pose of the Anchor in world space
    anchor.pose.toMatrix(modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    shader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(mesh, shader, virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) {
    if (context is HelloGeoActivity) {
      context.view.snackbarHelper.showError(context, errorMessage)
    } else if (context is ARActivity) {
      context.runOnUiThread {
        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun handlePersistentEarthFailure(reason: String) {
    if (hasFallenBackToMapMode) return // Prevent multiple fallbacks
    
    hasFallenBackToMapMode = true
    Log.e(TAG, "Falling back to map mode: $reason")
    
    if (context is HelloGeoActivity) {
      context.runOnUiThread {
        // Show a toast explaining the issue
        Toast.makeText(
          context,
          "AR features unavailable: $reason. Switching to map-only mode.",
          Toast.LENGTH_LONG
        ).show()
        
        // Start the fallback activity
        try {
          context.startActivity(Intent(context, FallbackActivity::class.java))
          context.finish()
        } catch (e: Exception) {
          Log.e(TAG, "Error launching FallbackActivity", e)
          // Call the public method
          context.showFallbackUserInterface()
        }
      }
    } else if (context is ARActivity) {
      context.runOnUiThread {
        Toast.makeText(context, "AR features unavailable: $reason. Switching to map-only mode.", Toast.LENGTH_LONG).show()
        context.returnToMapMode()
      }
    } else if (context is Activity) {
      // For other activity types
      (context as Activity).runOnUiThread {
        Toast.makeText(context, "AR features unavailable: $reason", Toast.LENGTH_LONG).show()
      }
    }
  }

  fun setSession(session: Session) {
    this.arSession = session
  }

  val session: Session?
    get() = arSession

  private fun updateMapPosition(latitude: Double, longitude: Double, heading: Double) {
    if (context is HelloGeoActivity) {
      context.view.mapView?.updateMapPosition(latitude, longitude, heading)
    }
    // In ARActivity we don't have a mapView to update
  }

  private fun updateStatusText(earth: Earth?, geospatialPose: GeospatialPose?) {
    if (context is HelloGeoActivity) {
      context.view.updateStatusText(earth, geospatialPose)
    }
    // ARActivity has its own status indicator
  }

  private fun updateTrackingState(trackingState: TrackingState) {
    trackingStateHelper?.updateKeepScreenOnFlag(trackingState)
  }

  private fun updateMapMarker(latLng: LatLng) {
    helloGeoView?.mapView?.let { mapView ->
      if (mapView.googleMap != null && mapView.earthMarker != null) {
        mapView.earthMarker.position = latLng
        mapView.earthMarker.isVisible = true
      }
    }
  }
}
