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
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.view.Gravity
import android.view.ViewGroup
import android.view.FrameLayout
import com.google.android.gms.maps.SupportMapFragment
import android.graphics.Color
import android.view.inputmethod.InputMethodManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.MarkerOptions

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

    try {
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
          showFallbackUserInterface()
          Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
    session.configure(
      session.config.apply {
        geospatialMode = Config.GeospatialMode.ENABLED
        // Enable better accuracy
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        // Enable depth for better occlusion
        depthMode = Config.DepthMode.AUTOMATIC
      }
    )
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

  private fun showFallbackUserInterface() {
    // Create a simple fallback UI that just shows a map without AR features
    try {
      // Create a simple layout with just a map
      val mapOnlyLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
      }
      
      // Add a header text
      val headerText = TextView(this).apply {
        text = "Map Navigation (AR Unavailable)"
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(16, 16, 16, 16)
        setTextColor(Color.BLACK)
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        )
      }
      mapOnlyLayout.addView(headerText)
      
      // Add a search bar
      val searchBar = EditText(this).apply {
        hint = "Search location"
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
          setMargins(16, 8, 16, 8)
        }
        
        // Set up search functionality
        setOnEditorActionListener { _, actionId, event ->
          if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
              (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
            val searchQuery = text.toString()
            if (searchQuery.isNotBlank()) {
              performFallbackSearch(searchQuery, mapFragment)
            }
            return@setOnEditorActionListener true
          }
          return@setOnEditorActionListener false
        }
        
        // Set to search input type
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
      }
      mapOnlyLayout.addView(searchBar)
      
      // Add a map fragment
      val mapContainerId = View.generateViewId()
      val mapFrameLayout = FrameLayout(this).apply {
        id = mapContainerId
        layoutParams = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          0
        ).apply {
          weight = 1f
          setMargins(16, 8, 16, 16)
        }
      }
      mapOnlyLayout.addView(mapFrameLayout)
      
      setContentView(mapOnlyLayout)
      
      // After setContentView, add the map fragment to the container
      val mapFragment = SupportMapFragment()
      supportFragmentManager.beginTransaction()
        .add(mapContainerId, mapFragment)
        .commit()
      
      // Initialize map when ready
      mapFragment.getMapAsync { googleMap ->
        googleMap.uiSettings.apply {
          isZoomControlsEnabled = true
          isCompassEnabled = true
          isMyLocationButtonEnabled = true
        }
        
        // Enable My Location if we have permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
          googleMap.isMyLocationEnabled = true
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create fallback UI", e)
    }
  }

  private fun performFallbackSearch(query: String, mapFragment: SupportMapFragment) {
    try {
      val geocoder = Geocoder(this)
      
      // Hide the keyboard
      val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
      inputMethodManager.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
      
      Toast.makeText(this, "Searching for: $query", Toast.LENGTH_SHORT).show()
      
      // Use the appropriate geocoding method based on Android version
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
