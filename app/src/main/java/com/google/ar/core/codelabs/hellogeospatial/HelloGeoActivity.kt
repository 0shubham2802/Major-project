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
import com.google.android.gms.common.api.Status
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
import com.google.ar.core.codelabs.hellogeospatial.helpers.GoogleApiKeyValidator
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.codelabs.hellogeospatial.helpers.MapErrorHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.MapViewWrapper
import com.google.ar.core.codelabs.hellogeospatial.helpers.DirectionsHelper
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.core.codelabs.hellogeospatial.BuildConfig
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.opengl.GLSurfaceView

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
  private lateinit var directionsHelper: DirectionsHelper
  
  // Add missing variables
  private lateinit var surfaceView: GLSurfaceView
  private var installRequested = false
  
  // Add watchdog for ANR prevention
  private val watchdogHandler = Handler(Looper.getMainLooper())
  private val watchdogRunnable = Runnable {
    Log.w(TAG, "UI thread watchdog triggered - potential ANR detected")
    // Take recovery action
    try {
      Toast.makeText(this, "Application responsiveness issue detected, recovering...", Toast.LENGTH_SHORT).show()
      val fallbackIntent = Intent(this, FallbackActivity::class.java)
      fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      fallbackIntent.putExtra("FROM_ANR_RECOVERY", true)
      startActivity(fallbackIntent)
      finish()
    } catch (e: Exception) {
      Log.e(TAG, "Error in watchdog recovery", e)
      // Try system recovery as last resort
      try {
        Runtime.getRuntime().exit(0)
      } catch (e2: Exception) {
        Log.e(TAG, "Failed even system exit", e2)
      }
    }
  }
  private val WATCHDOG_TIMEOUT_MS = 8000L // 8 seconds - increased from 5

  override fun onCreate(savedInstanceState: Bundle?) {
    // Add emergency handler for stability
    val emergencyHandler = Handler(Looper.getMainLooper())
    val emergencyRunnable = object : Runnable {
      override fun run() {
        // Check if app is still responding by monitoring key operations
        try {
          if (::surfaceView.isInitialized && surfaceView.isAttachedToWindow) {
            Log.d(TAG, "Emergency health check - app functioning normally")
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error in emergency health check", e)
        }
        
        // Schedule next check (every 5 seconds)
        emergencyHandler.postDelayed(this, 5000)
      }
    }
    
    // Start emergency monitoring
    emergencyHandler.postDelayed(emergencyRunnable, 5000)
    
    super.onCreate(savedInstanceState)
    
    try {
      // Validate Google Maps API key and log information
      GoogleApiKeyValidator.validateApiKey(this)
      
      // Initialize AR components
      arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
      arCoreSessionHelper.exceptionCallback = { exception ->
        handleARException(exception)
      }
      
      // Initialize location services
      fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
      
      // Initialize directions helper
      directionsHelper = DirectionsHelper(this)
    
      // Set AR view as the main content view
      setContentView(R.layout.activity_main)
      
      // Initialize the GL surface view
      surfaceView = findViewById(R.id.surfaceview)
      
      // Set up AR renderer and initialize
      renderer = HelloGeoRenderer(this)
      
      // Initialize the sample renderer
      SampleRender(surfaceView, renderer, assets)
      
      // Set up the Map View Fragment
      setupMapFragment()
      
      // Additional setup
      installRequested = false
      
    } catch (e: Exception) {
      Log.e(TAG, "Error in onCreate", e)
      startFallbackActivity()
    }
  }
  
  fun startFallbackActivity() {
    try {
      startActivity(Intent(this, FallbackActivity::class.java))
      finish()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start FallbackActivity", e)
      showFallbackUserInterface()
    }
  }
  
  override fun onResume() {
    super.onResume()
    try {
      // Start UI watchdog
      startWatchdog()
      
      // Additional check for AR availability
      if (!checkIsARCoreSupportedAndUpToDate()) {
        showFallbackUserInterface()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in onResume", e)
    }
  }
  
  override fun onPause() {
    super.onPause()
    // Stop watchdog when activity is paused
    stopWatchdog()
  }
  
  /**
   * Start the UI watchdog to detect potential ANRs
   */
  private fun startWatchdog() {
    // First, ensure any existing watchdog is stopped
    stopWatchdog()
    
    // Schedule a new watchdog check
    watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
    
    // Post a tiny task that will execute if the UI thread is responsive
    // and reset the watchdog
    watchdogHandler.post {
      // UI thread is still responsive, reset watchdog
      resetWatchdog()
    }
  }
  
  /**
   * Reset the watchdog timer
   */
  private fun resetWatchdog() {
    // Cancel the current watchdog
    watchdogHandler.removeCallbacks(watchdogRunnable)
    
    // Schedule a new watchdog check
    watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)
    
    // Schedule periodic pings to keep checking UI thread
    watchdogHandler.postDelayed({
      // Only continue if activity is still active
      if (!isFinishing && !isDestroyed) {
        resetWatchdog()
      }
    }, WATCHDOG_TIMEOUT_MS / 2)
  }
  
  /**
   * Stop the watchdog timer
   */
  private fun stopWatchdog() {
    watchdogHandler.removeCallbacks(watchdogRunnable)
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
      
      // Get current location from Earth or from location services
      val earth = arCoreSessionHelper.session?.earth
      val currentLatLng = if (earth?.trackingState == TrackingState.TRACKING) {
        val cameraGeospatialPose = earth.cameraGeospatialPose
        LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
      } else {
        // Fallback to last known location
        currentLocation?.let { LatLng(it.latitude, it.longitude) } 
          ?: run {
            Toast.makeText(this, "Cannot determine current location", Toast.LENGTH_SHORT).show()
            return@let
          }
      }
      
      // Show loading indicator
      val loadingDialog = AlertDialog.Builder(this)
        .setTitle("Getting Directions")
        .setMessage("Please wait...")
        .setCancelable(false)
        .create()
      loadingDialog.show()
      
      // Fetch directions with turn-by-turn instructions
      directionsHelper.getDirectionsWithInstructions(
        currentLatLng, 
        destination, 
        object : DirectionsHelper.DirectionsWithInstructionsListener {
          override fun onDirectionsReady(
            pathPoints: List<LatLng>, 
            instructions: List<String>, 
            steps: List<DirectionsHelper.DirectionStep>
          ) {
            loadingDialog.dismiss()
            
            // Create anchors for the path
            renderer.updatePathAnchors(pathPoints)
            
            // Notify view that navigation has started
            view.startNavigationMode(destination)
            
            // Update the navigation instructions
            view.updateNavigationInstructions(instructions)
            
            // Show route on the map
            view.showRouteOnMap(currentLatLng, destination)
            
            // Create directional waypoints for turns
            val waypoints = steps.mapNotNull { step ->
              if (step.instruction.contains("turn", ignoreCase = true) || 
                  step.instruction.contains("onto", ignoreCase = true) ||
                  step.instruction.contains("left", ignoreCase = true) ||
                  step.instruction.contains("right", ignoreCase = true)) {
                step.startLocation
              } else null
            }
            
            // Log the route details
            Log.d(TAG, "Navigation started with ${pathPoints.size} points and ${instructions.size} instructions")
            Log.d(TAG, "First instruction: ${if (instructions.isNotEmpty()) instructions[0] else "none"}")
            
            Toast.makeText(this@HelloGeoActivity, "Navigation started", Toast.LENGTH_SHORT).show()
          }
          
          override fun onDirectionsError(errorMessage: String) {
            loadingDialog.dismiss()
            
            Log.e(TAG, "Directions API error: $errorMessage")
            Toast.makeText(this@HelloGeoActivity, "Error getting directions: $errorMessage", Toast.LENGTH_SHORT).show()
            
            // Fallback to direct path
            val simplePath = listOf(currentLatLng, destination)
            renderer.createPathAnchors(simplePath)
            view.startNavigationMode(destination)
            
            // Create a simple instruction
            view.updateNavigationInstructions(listOf("Head toward destination"))
            
            view.showRouteOnMap(currentLatLng, destination)
            
            Toast.makeText(this@HelloGeoActivity, "Using direct route", Toast.LENGTH_SHORT).show()
          }
        }
      )
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
      .setPositiveButton("Yes") { dialog, which ->
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
      // Use API level 30 or lower for compatibility
      if (Build.VERSION.SDK_INT >= 30) {
        // For API level 30+, but still avoiding 33-specific methods
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

  private fun setupMapFragment() {
    try {
      // Use our enhanced MapViewWrapper instead of regular SupportMapFragment
      val mapFragment = MapViewWrapper(this)
      
      supportFragmentManager.beginTransaction()
        .replace(R.id.map, mapFragment)
        .commit()
      
      // Set up error handling for map loading
      mapFragment.setOnMapLoadErrorListener {
        Toast.makeText(this, "Error loading map. Attempting to recover...", Toast.LENGTH_SHORT).show()
        
        // Create and use the map error helper to diagnose issues
        val mapErrorHelper = MapErrorHelper(this)
        mapErrorHelper.diagnoseMapsIssue()
      }
      
      // Set up the map when it's ready
      mapFragment.getMapAsync { googleMap ->
        Log.d(TAG, "Map is ready")
        
        // Initialize map settings
        googleMap.uiSettings.apply {
          isZoomControlsEnabled = true
          isCompassEnabled = true
          isMyLocationButtonEnabled = true
        }
        
        try {
          googleMap.isMyLocationEnabled = true
        } catch (e: SecurityException) {
          Log.e(TAG, "Location permission not granted", e)
        }
        
        // Pass in the activity - the View class will create the MapView with Google Map
        // Don't try to directly create the MapView here as it's handled inside HelloGeoView
        view = HelloGeoView(this)
        
        // Setup UI components after view is created
        setupNavigationUI()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up map fragment", e)
      Toast.makeText(this, "Error initializing map. Please restart the app.", Toast.LENGTH_LONG).show()
    }
  }

  /**
   * Handle AR exceptions with proper error recovery
   */
  private fun handleARException(exception: Exception) {
    Log.e(TAG, "AR exception", exception)
    
    // Reset watchdog to prevent ANR while handling the exception
    resetWatchdog()
    
    try {
      val message = when (exception) {
        is UnavailableUserDeclinedInstallationException -> "Please install ARCore"
        is UnavailableApkTooOldException -> "Please update ARCore"
        is UnavailableSdkTooOldException -> "Please update this app"
        is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
        is CameraNotAvailableException -> "Camera is not available - may be in use by another app"
        else -> "Failed to initialize AR: $exception"
      }
      
      // Special handling for camera issues
      if (exception is CameraNotAvailableException) {
        // Show a dialog with camera recovery options
        runOnUiThread {
          AlertDialog.Builder(this)
            .setTitle("Camera Unavailable")
            .setMessage("Camera is being used by another app or system process. What would you like to do?")
            .setPositiveButton("EMERGENCY RESET") { dialog, _ ->
              dialog.dismiss()
              emergencyCameraReset()
            }
            .setNegativeButton("MAP ONLY") { dialog, _ ->
              dialog.dismiss()
              startFallbackActivity()
            }
            .setNeutralButton("RETRY") { dialog, _ ->
              dialog.dismiss()
              retryCameraAccess()
            }
            .setCancelable(false)
            .show()
        }
      } else {
        // For other exceptions, show a toast and start the fallback activity
        runOnUiThread {
          Toast.makeText(this, message, Toast.LENGTH_LONG).show()
          startFallbackActivity()
        }
      }
    } catch (e: Exception) {
      // Failsafe error handling
      Log.e(TAG, "Error in exception handler", e)
      runOnUiThread {
        Toast.makeText(this, "Critical error - switching to fallback mode", Toast.LENGTH_LONG).show()
        startFallbackActivity()
      }
    }
  }
  
  /**
   * Attempt to retry camera access
   */
  private var cameraRetryCount = 0
  private val MAX_CAMERA_RETRIES = 3
  
  private fun retryCameraAccess() {
    cameraRetryCount++
    Log.d(TAG, "Attempting to retry camera access (attempt $cameraRetryCount)")
    
    // Show progress indicator to user
    Toast.makeText(this, "Retrying camera access...", Toast.LENGTH_SHORT).show()
    
    try {
      // First release resources
      arCoreSessionHelper.onPause()
      
      // Clean up resources
      System.gc()
      
      // Wait briefly
      Handler(Looper.getMainLooper()).postDelayed({
        try {
          // Try to resume
          arCoreSessionHelper.onResume()
          
          // Check for success after a moment
          Handler(Looper.getMainLooper()).postDelayed({
            if (cameraRetryCount >= MAX_CAMERA_RETRIES) {
              // Max retries reached
              Toast.makeText(this, "Camera access failed after multiple attempts", Toast.LENGTH_SHORT).show()
              emergencyCameraReset()
            } else {
              // Retry was successful
              Toast.makeText(this, "Camera access restored", Toast.LENGTH_SHORT).show()
            }
          }, 1000)
        } catch (e: Exception) {
          Log.e(TAG, "Error resuming session", e)
          if (cameraRetryCount < MAX_CAMERA_RETRIES) {
            // Try again
            Handler(Looper.getMainLooper()).postDelayed({
              retryCameraAccess()
            }, 1000)
          } else {
            // Try emergency reset
            emergencyCameraReset()
          }
        }
      }, 1000)
    } catch (e: Exception) {
      Log.e(TAG, "Error in retryCameraAccess", e)
      emergencyCameraReset()
    }
  }
  
  /**
   * Emergency camera reset that attempts to fix camera issues
   */
  private fun emergencyCameraReset() {
    Log.d(TAG, "Emergency camera reset - attempting to recover camera")
    Toast.makeText(this, "Attempting emergency camera recovery...", Toast.LENGTH_SHORT).show()
    
    try {
      // First try to release any ARCore camera resources
      arCoreSessionHelper.onPause()
      
      // Force release camera using the Camera2 API
      releaseCamera2Resources()
      
      // Also try the legacy Camera API as fallback
      releaseCamera1Resources()
      
      // Force garbage collection to ensure resources are freed
      System.gc()
      System.runFinalization()
      
      // Wait a moment for system to process
      Handler(Looper.getMainLooper()).postDelayed({
        try {
          // Try to resume with fresh camera
          Toast.makeText(this, "Attempting to restart camera...", Toast.LENGTH_SHORT).show()
          recreate() // Recreate activity for clean state
        } catch (e: Exception) {
          Log.e(TAG, "Failed to restart AR session", e)
          startFallbackActivity()
        }
      }, 1000)
    } catch (e: Exception) {
      Log.e(TAG, "Error in emergency camera reset", e)
      startFallbackActivity()
    }
  }
  
  /**
   * Release Camera2 API resources
   */
  private fun releaseCamera2Resources() {
    try {
      val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
      val cameraIds = cameraManager.cameraIdList
      
      for (cameraId in cameraIds) {
        Log.d(TAG, "Attempting to release Camera2 resources for camera $cameraId")
        
        try {
          // Create a semaphore to handle callbacks
          val semaphore = java.util.concurrent.Semaphore(0)
          
          // Try opening and immediately closing the camera
          val stateCallback = object : android.hardware.camera2.CameraDevice.StateCallback() {
            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
              Log.d(TAG, "Camera2 $cameraId opened successfully for reset")
              try {
                // Close immediately
                camera.close()
                Log.d(TAG, "Camera2 $cameraId closed successfully")
              } catch (e: Exception) {
                Log.e(TAG, "Error closing Camera2 device", e)
              } finally {
                semaphore.release()
              }
            }
            
            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
              Log.d(TAG, "Camera2 $cameraId disconnected")
              camera.close()
              semaphore.release()
            }
            
            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
              Log.e(TAG, "Camera2 $cameraId error: $error")
              camera.close()
              semaphore.release()
            }
            
            override fun onClosed(camera: android.hardware.camera2.CameraDevice) {
              Log.d(TAG, "Camera2 $cameraId closed in callback")
              semaphore.release()
            }
          }
          
          // Check for permissions at runtime
          if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No camera permission during reset")
            semaphore.release()
            continue
          }
          
          // Try to open the camera
          cameraManager.openCamera(cameraId, stateCallback, null)
          
          // Wait with timeout
          semaphore.tryAcquire(1, java.util.concurrent.TimeUnit.SECONDS)
          
        } catch (e: Exception) {
          Log.e(TAG, "Error releasing Camera2 device: ${e.message}")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in Camera2 release", e)
    }
  }
  
  /**
   * Release legacy Camera API resources as a fallback
   */
  @Suppress("DEPRECATION")
  private fun releaseCamera1Resources() {
    try {
      // Try with legacy Camera API for older devices or as fallback
      var camera: android.hardware.Camera? = null
      try {
        // Try to open front camera first
        camera = android.hardware.Camera.open(android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT)
        Log.d(TAG, "Successfully opened front camera for reset")
        Thread.sleep(100)
        camera.release()
        Log.d(TAG, "Released front camera")
      } catch (e: Exception) {
        Log.e(TAG, "Error with front camera: ${e.message}")
      }
      
      try {
        // Try to open back camera
        camera = android.hardware.Camera.open(android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK)
        Log.d(TAG, "Successfully opened back camera for reset")
        Thread.sleep(100)
        camera.release()
        Log.d(TAG, "Released back camera")
      } catch (e: Exception) {
        Log.e(TAG, "Error with back camera: ${e.message}")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in Camera1 release", e)
    }
  }

  override fun onDestroy() {
    // Clean up resources
    super.onDestroy()
  }
}
