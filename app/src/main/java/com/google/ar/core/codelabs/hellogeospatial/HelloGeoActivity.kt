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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.Locale

class HelloGeoActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "HelloGeoActivity"
    private const val INTERNET_PERMISSION_CODE = 101
    private const val LOCATION_PERMISSION_CODE = 102
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HelloGeoView
  lateinit var renderer: HelloGeoRenderer
  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private var currentLocation: Location? = null
  private var destinationLatLng: LatLng? = null
  private var isNavigating = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Wrap everything in a try-catch to prevent crashes
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
      Log.e(TAG, "Uncaught exception", throwable)
      runOnUiThread {
        Toast.makeText(this, "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
        showFallbackUserInterface()
      }
    }

    try {
      // RE-ENABLING AR: Try AR mode with proper fallback
      Log.d(TAG, "Attempting to initialize AR mode")
      
      // Show a loading message while we set things up
      val loadingLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
      }
      
      val loadingText = TextView(this).apply {
        text = "Initializing AR Navigation..."
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.BLACK)
      }
      
      val progressBar = ProgressBar(this)
      loadingLayout.addView(progressBar)
      loadingLayout.addView(loadingText)
      setContentView(loadingLayout)
      
      // Check if device supports AR
      if (!checkIsARCoreSupportedAndUpToDate()) {
        Log.d(TAG, "Device doesn't support ARCore, falling back to map mode")
        Handler(Looper.getMainLooper()).postDelayed({
          startActivity(Intent(this, FallbackActivity::class.java))
          finish()
        }, 500)
        return
      }
      
      // AR is supported, continue with AR initialization
      // Initialize location services
      fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

      // Check for required permissions
      checkRequiredPermissions()

      // Setup ARCore session lifecycle helper and configuration.
      arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
      // If Session creation or Session.resume() fails, display a message and log detailed
      // information.
      arCoreSessionHelper.exceptionCallback =
        { exception ->
          val message =
            when (exception) {
              is UnavailableUserDeclinedInstallationException ->
                "Please install Google Play Services for AR"
              is UnavailableApkTooOldException -> "Please update ARCore"
              is UnavailableSdkTooOldException -> "Please update this app"
              is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
              is CameraNotAvailableException -> "Camera not available. Try restarting the app."
              else -> "Failed to create AR session: $exception"
            }
          Log.e(TAG, "ARCore threw an exception", exception)
          Toast.makeText(this, message, Toast.LENGTH_LONG).show()
          
          // For emulator and devices that don't support AR
          Log.d(TAG, "Falling back to non-AR mode")
          showFallbackUserInterface()
        }

      // Configure session features.
      arCoreSessionHelper.beforeSessionResume = ::configureSession
      lifecycle.addObserver(arCoreSessionHelper)

      // Set up the Hello AR renderer.
      renderer = HelloGeoRenderer(this)
      lifecycle.addObserver(renderer)

      // Set up Hello AR UI.
      view = HelloGeoView(this)
      lifecycle.addObserver(view)
      setContentView(view.root)

      // Sets up an example renderer using our HelloGeoRenderer.
      SampleRender(view.surfaceView, renderer, assets)
      
      // Setup navigation buttons after view is created
      setupNavigationUI()
      
      // Request current location
      requestCurrentLocation()
    } catch (e: Exception) {
      Log.e(TAG, "Error initializing app", e)
      showFallbackUserInterface()
      Toast.makeText(this, "Could not initialize AR features. Using map only mode.", Toast.LENGTH_LONG).show()
    }
  }
  
  override fun onResume() {
    super.onResume()
    try {
      // Additional check for AR availability
      if (!checkIsARCoreSupportedAndUpToDate()) {
        showFallbackUserInterface()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in onResume", e)
    }
  }
  
  private fun checkIsARCoreSupportedAndUpToDate(): Boolean {
    return try {
      when (ArCoreApk.getInstance().checkAvailability(this)) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> true
        else -> {
          Log.w(TAG, "ARCore not available")
          false
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error checking ARCore availability", e)
      false
    }
  }
  
  private fun setupNavigationUI() {
    // Add navigation control buttons to the layout dynamically
    val navigateButton = Button(this).apply {
      text = "Start Navigation"
      visibility = View.GONE
      setOnClickListener {
        startNavigation()
      }
    }
    
    val stopButton = Button(this).apply {
      text = "Stop Navigation"
      visibility = View.GONE
      setOnClickListener { 
        stopNavigation()
      }
    }
    
    // Add these buttons to the root view
    view.addActionButton(navigateButton, "navigate_button")
    view.addActionButton(stopButton, "stop_button")
    
    // Update destination callback
    view.setOnDestinationSelectedListener { latLng, locationName ->
      destinationLatLng = latLng
      navigateButton.visibility = View.VISIBLE
      stopButton.visibility = View.GONE
      
      Toast.makeText(this, "Destination set to: $locationName", Toast.LENGTH_SHORT).show()
    }
  }

  private fun startNavigation() {
    destinationLatLng?.let { destination ->
      isNavigating = true
      
      // Show navigation UI
      view.getActionButton("navigate_button")?.visibility = View.GONE
      view.getActionButton("stop_button")?.visibility = View.VISIBLE
      
      // Create AR anchor at destination
      val earth = arCoreSessionHelper.session?.earth
      if (earth?.trackingState == TrackingState.TRACKING) {
        val cameraGeospatialPose = earth.cameraGeospatialPose
        
        // Create current location point
        val currentLatLng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
        
        // For now, just create a simple path from current to destination
        val simplePath = listOf(currentLatLng, destination)
        
        // Create anchors for the path
        renderer.createPathAnchors(simplePath)
        
        // Notify view that navigation has started
        view.startNavigationMode(destination)
        
        // Show route on the map
        view.showRouteOnMap(currentLatLng, destination)
        
        Toast.makeText(this, "Navigation started", Toast.LENGTH_SHORT).show()
      } else {
        Toast.makeText(this, "Earth tracking not available yet. Try again.", Toast.LENGTH_SHORT).show()
      }
    } ?: run {
      Toast.makeText(this, "Please set a destination first", Toast.LENGTH_SHORT).show()
    }
  }
  
  private fun stopNavigation() {
    isNavigating = false
    
    // Hide navigation UI
    view.getActionButton("navigate_button")?.visibility = View.VISIBLE
    view.getActionButton("stop_button")?.visibility = View.GONE
    
    // Remove AR anchors
    renderer.clearAnchors()
    
    // Notify view that navigation has stopped
    view.stopNavigationMode()
    
    Toast.makeText(this, "Navigation stopped", Toast.LENGTH_SHORT).show()
  }
  
  private fun requestCurrentLocation() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
        == PackageManager.PERMISSION_GRANTED) {
      fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        location?.let {
          currentLocation = it
          Log.d(TAG, "Current location: ${it.latitude}, ${it.longitude}")
        }
      }
    }
  }

  // Configure the session, setting the desired options according to your usecase.
  fun configureSession(session: Session) {
    try {
      Log.d(TAG, "Configuring ARCore session with geospatial mode")
      session.configure(
        session.config.apply {
          // Enable geospatial mode
          geospatialMode = Config.GeospatialMode.ENABLED
          
          // Balanced settings for AR navigation
          planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
          
          // Enable depth for better occlusion but only if device supports it
          try {
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
              depthMode = Config.DepthMode.AUTOMATIC
              Log.d(TAG, "Depth mode enabled")
            } else {
              depthMode = Config.DepthMode.DISABLED
              Log.d(TAG, "Depth mode not supported on this device")
            }
          } catch (e: Exception) {
            depthMode = Config.DepthMode.DISABLED
            Log.e(TAG, "Error checking depth support", e)
          }
          
          // Use lighter lighting estimation mode for better performance
          lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
          
          // Use latest camera image for best tracking
          updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
          
          // Enable auto focus for better tracking
          focusMode = Config.FocusMode.AUTO
          
          // Enable cloud anchors for potential future social/shared AR features
          cloudAnchorMode = Config.CloudAnchorMode.ENABLED
        }
      )
      Log.d(TAG, "ARCore session configured successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error configuring ARCore session", e)
      Toast.makeText(this, "Error configuring AR: ${e.message}", Toast.LENGTH_SHORT).show()
    }
  }

  private fun checkRequiredPermissions() {
    // Check for internet permission
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.INTERNET), INTERNET_PERMISSION_CODE)
    }
    
    // Check for location permissions
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_CODE)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    
    when (requestCode) {
      INTERNET_PERMISSION_CODE -> {
        if (results.isNotEmpty() && results[0] != PackageManager.PERMISSION_GRANTED) {
          Toast.makeText(this, "Internet permission is required for location search", Toast.LENGTH_LONG).show()
        }
      }
      LOCATION_PERMISSION_CODE -> {
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
          // Location permission granted, get current location
          requestCurrentLocation()
        } else {
          Toast.makeText(this, "Location permission is required for navigation", Toast.LENGTH_LONG).show()
        }
      }
      else -> {
        if (!GeoPermissionsHelper.hasGeoPermissions(this)) {
          // Use toast instead of snackbar here since the activity will exit.
          Toast.makeText(this, "Camera and location permissions are needed to run this application", Toast.LENGTH_LONG)
            .show()
          if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(this)) {
            // Permission denied with checking "Do not ask again".
            GeoPermissionsHelper.launchPermissionSettings(this)
          }
          finish()
        }
      }
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
  
  override fun onBackPressed() {
    if (isNavigating) {
      // If navigating, stop navigation instead of exiting
      showExitNavigationDialog()
    } else {
      super.onBackPressed()
    }
  }
  
  private fun showExitNavigationDialog() {
    AlertDialog.Builder(this)
      .setTitle("Stop Navigation")
      .setMessage("Do you want to stop the current navigation?")
      .setPositiveButton("Yes") { _, _ ->
        stopNavigation()
      }
      .setNegativeButton("No", null)
      .show()
  }
  
  fun openGoogleMapsNavigation(destination: LatLng) {
    val uri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}&mode=w")
    val mapIntent = Intent(Intent.ACTION_VIEW, uri)
    mapIntent.setPackage("com.google.android.apps.maps")
    
    if (mapIntent.resolveActivity(packageManager) != null) {
      startActivity(mapIntent)
    } else {
      Toast.makeText(this, "Google Maps app is not installed", Toast.LENGTH_SHORT).show()
    }
  }

  fun showFallbackUserInterface() {
    // Create a simpler fallback UI to avoid potential import issues
    try {
      // Create a layout with text and button
      val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 32)
        setBackgroundColor(Color.WHITE)
      }
      
      // Add a title text view
      val textView = TextView(this).apply {
        text = "AR Navigation\n\nThis device does not fully support AR features."
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(16, 16, 16, 48) // Extra padding at bottom
        setTextColor(Color.BLACK)
      }
      layout.addView(textView)
      
      // Add a button to launch the full fallback activity
      val fallbackButton = Button(this).apply {
        text = "Continue with Map Navigation"
        background = ContextCompat.getDrawable(context, android.R.color.holo_blue_dark)
        setTextColor(Color.WHITE)
        setPadding(32, 16, 32, 16)
        
        setOnClickListener {
          try {
            startActivity(Intent(this@HelloGeoActivity, FallbackActivity::class.java))
            finish()
          } catch (e: Exception) {
            Log.e(TAG, "Failed to start FallbackActivity", e)
            Toast.makeText(this@HelloGeoActivity, "Error launching map mode", Toast.LENGTH_LONG).show()
          }
        }
      }
      layout.addView(fallbackButton)
      
      setContentView(layout)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create even simple fallback UI", e)
      // Last resort - try to start the fallback activity
      try {
        startActivity(Intent(this, FallbackActivity::class.java))
        finish()
      } catch (e2: Exception) {
        Log.e(TAG, "Everything failed, app will likely crash", e2)
      }
    }
  }

  private fun performFallbackSearch(query: String, mapFragment: SupportMapFragment) {
    try {
      val geocoder = Geocoder(this, Locale.getDefault())
      
      // Hide the keyboard
      val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
      inputMethodManager.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
      
      Toast.makeText(this, "Searching for: $query", Toast.LENGTH_SHORT).show()
      
      // Use the appropriate geocoding method based on Android version
      // Android 13 (TIRAMISU) is API level 33
      if (Build.VERSION.SDK_INT >= 33) {
        geocoder.getFromLocationName(query, 1) { addresses ->
          runOnUiThread {
            if (addresses.isNotEmpty()) {
              val address = addresses[0]
              val latLng = LatLng(address.latitude, address.longitude)
              
              // Move the map to the found location
              mapFragment.getMapAsync { googleMap ->
                googleMap.clear()
                googleMap.addMarker(MarkerOptions().position(latLng).title(query))
                googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
              }
            } else {
              Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
            }
          }
        }
      } else {
        // Legacy method for older Android versions
        @Suppress("DEPRECATION")
        val addressList = geocoder.getFromLocationName(query, 1)
        
        if (addressList != null && addressList.isNotEmpty()) {
          val address = addressList[0]
          val latLng = LatLng(address.latitude, address.longitude)
          
          // Move the map to the found location
          mapFragment.getMapAsync { googleMap ->
            googleMap.clear()
            googleMap.addMarker(MarkerOptions().position(latLng).title(query))
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
          }
        } else {
          Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in fallback search", e)
      Toast.makeText(this, "Error searching for location", Toast.LENGTH_SHORT).show()
    }
  }
}
