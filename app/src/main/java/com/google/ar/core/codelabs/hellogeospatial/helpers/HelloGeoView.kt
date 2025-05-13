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
package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import java.io.IOException
import java.util.Locale

/** Contains UI elements for Hello Geo. */
class HelloGeoView(val activity: HelloGeoActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.activity_main, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)
  val searchView = root.findViewById<SearchView>(R.id.searchView)
  
  // Add button container for navigation controls
  private val buttonContainer = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.BOTTOM or Gravity.END
    setPadding(0, 0, 32, 32)
  }
  
  // Map of action buttons by ID
  private val actionButtons = mutableMapOf<String, Button>()
  
  // Store text views separately to avoid casting issues
  private val textViews = mutableMapOf<String, TextView>()
  
  // Navigation related variables
  private var destinationSelectedListener: ((LatLng, String) -> Unit)? = null
  private var routePolyline: com.google.android.gms.maps.model.Polyline? = null
  private var isNavigationMode = false

  val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()

  var mapView: MapView? = null
  val mapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
    setup { screenLocation ->
      val latLng: LatLng =
        mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
      activity.renderer.onMapClick(latLng)
    }
  }
  val mapFragment =
    (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
      it.getMapAsync { googleMap -> mapView = MapView(activity, googleMap) }
    }

  val statusText = root.findViewById<TextView>(R.id.statusText)
  
  init {
    setupSearchView()
    setupButtonContainer()
  }
  
  private fun setupButtonContainer() {
    val rootFrame = root as FrameLayout
    val layoutParams = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.WRAP_CONTENT,
      FrameLayout.LayoutParams.WRAP_CONTENT
    ).apply {
      gravity = Gravity.BOTTOM or Gravity.END
      bottomMargin = 320 // Position above the map
      rightMargin = 16
    }
    rootFrame.addView(buttonContainer, layoutParams)
  }
  
  fun addActionButton(button: Button, id: String) {
    button.apply {
      layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply {
        setMargins(0, 0, 0, 8)
      }
      
      setPadding(16, 8, 16, 8)
      
      // Apply styling
      setBackgroundColor(ContextCompat.getColor(activity, android.R.color.holo_blue_dark))
      setTextColor(Color.WHITE)
    }
    
    buttonContainer.addView(button)
    actionButtons[id] = button
  }
  
  fun getActionButton(id: String): Button? {
    return actionButtons[id]
  }
  
  fun setOnDestinationSelectedListener(listener: (LatLng, String) -> Unit) {
    this.destinationSelectedListener = listener
  }
  
  private fun setupSearchView() {
    // Style the search view to look like Google Maps
    searchView.apply {
      queryHint = "Search for a destination"
      
      // Set text color to black
      val searchText = this.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
      searchText?.setTextColor(Color.BLACK)
      searchText?.setHintTextColor(Color.GRAY)
      
      // Focus handling for better UX
      setOnQueryTextFocusChangeListener { _, hasFocus ->
        if (hasFocus) {
          // When search gets focus - clear any previous query
          if (query.isNotBlank()) {
            setQuery("", false)
          }
          
          // Move the map down slightly to provide focus on search
          mapTouchWrapper.animate()
            .translationY(50f)
            .setDuration(200)
            .start()
        } else {
          // When search loses focus - restore map position
          mapTouchWrapper.animate()
            .translationY(0f)
            .setDuration(200)
            .start()
        }
      }
      
      // Handle search submission
      setOnQueryTextListener(object : SearchView.OnQueryTextListener {
        override fun onQueryTextSubmit(query: String?): Boolean {
          query?.let {
            searchLocation(it)
            clearFocus() // Hide keyboard after search
          }
          return true
        }

        override fun onQueryTextChange(newText: String?): Boolean {
          return false
        }
      })
    }
  }
  
  private fun searchLocation(locationName: String) {
    val geocoder = Geocoder(activity, Locale.getDefault())
    
    try {
      // For newer Android versions - using getFromLocationName might not work directly
      // Android 13 (TIRAMISU) is API level 33
      if (Build.VERSION.SDK_INT >= 33) {
        // Use the callback version for newer Android
        geocoder.getFromLocationName(locationName, 1) { addresses ->
          activity.runOnUiThread {
            if (addresses.isNotEmpty()) {
              val address = addresses[0]
              handleFoundLocation(address, locationName)
            } else {
              Toast.makeText(activity, "Location not found", Toast.LENGTH_SHORT).show()
            }
          }
        }
      } else {
        // Legacy method for older Android versions
        @Suppress("DEPRECATION")
        val addressList = geocoder.getFromLocationName(locationName, 1)
        
        if (addressList != null && addressList.isNotEmpty()) {
          val address = addressList[0]
          handleFoundLocation(address, locationName)
        } else {
          Toast.makeText(activity, "Location not found", Toast.LENGTH_SHORT).show()
        }
      }
    } catch (e: IOException) {
      Log.e("HelloGeoView", "Error in geocoding", e)
      Toast.makeText(activity, "Error searching for location", Toast.LENGTH_SHORT).show()
    }
  }
  
  private fun handleFoundLocation(address: Address, locationName: String) {
    val latLng = LatLng(address.latitude, address.longitude)
    
    // Use the MapView's navigation method to handle the search location
    mapView?.navigateToSearchLocation(latLng, locationName)
    
    // Notify destination selected listener
    destinationSelectedListener?.invoke(latLng, locationName)
  }
  
  fun startNavigationMode(destination: LatLng) {
    isNavigationMode = true
    
    // Show distance to destination
    val distanceText = TextView(activity).apply {
      text = "Preparing navigation..."
      setBackgroundColor(Color.argb(180, 0, 0, 0))
      setTextColor(Color.WHITE)
      setPadding(16, 8, 16, 8)
      gravity = Gravity.CENTER
    }
    
    val layoutParams = FrameLayout.LayoutParams(
      FrameLayout.LayoutParams.MATCH_PARENT,
      FrameLayout.LayoutParams.WRAP_CONTENT
    ).apply {
      gravity = Gravity.TOP
      topMargin = 80 // Below the search bar
    }
    
    (root as FrameLayout).addView(distanceText, layoutParams)
    textViews["distance_text"] = distanceText
    
    // Add Google Maps option button
    val gmapsButton = Button(activity).apply {
      text = "Open in Google Maps"
      setOnClickListener {
        activity.openGoogleMapsNavigation(destination)
      }
    }
    addActionButton(gmapsButton, "gmaps_button")
  }
  
  fun stopNavigationMode() {
    isNavigationMode = false
    
    try {
      // Remove navigation UI elements
      textViews["distance_text"]?.let {
        (root as FrameLayout).removeView(it)
        textViews.remove("distance_text")
      }
      
      // Remove Google Maps button
      actionButtons["gmaps_button"]?.let {
        buttonContainer.removeView(it)
        actionButtons.remove("gmaps_button")
      }
      
      // Clear route from map
      routePolyline?.remove()
      routePolyline = null
    } catch (e: Exception) {
      Log.e("HelloGeoView", "Error stopping navigation mode", e)
    }
  }
  
  fun showRouteOnMap(origin: LatLng, destination: LatLng) {
    // Remove any existing route
    routePolyline?.remove()
    
    // In a full implementation, we would use the Directions API to get a route
    // For this example, we'll just draw a straight line
    val polylineOptions = PolylineOptions()
      .add(origin, destination)
      .width(8f)
      .color(Color.BLUE)
      .geodesic(true)
    
    // Add the polyline to the map
    mapView?.googleMap?.let {
      routePolyline = it.addPolyline(polylineOptions)
    }
  }

  fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
    activity.runOnUiThread {
      val poseText = if (cameraGeospatialPose == null) "" else
        activity.getString(R.string.geospatial_pose,
                           cameraGeospatialPose.latitude,
                           cameraGeospatialPose.longitude,
                           cameraGeospatialPose.horizontalAccuracy,
                           cameraGeospatialPose.altitude,
                           cameraGeospatialPose.verticalAccuracy,
                           cameraGeospatialPose.heading,
                           cameraGeospatialPose.headingAccuracy)
      statusText.text = activity.resources.getString(R.string.earth_state,
                                                     earth.earthState.toString(),
                                                     earth.trackingState.toString(),
                                                     poseText)
      
      // If in navigation mode, update distance to destination
      if (isNavigationMode && cameraGeospatialPose != null) {
        updateNavigationInfo(cameraGeospatialPose)
      }
    }
  }
  
  private fun updateNavigationInfo(currentPose: GeospatialPose) {
    // Calculate distance to destination
    mapView?.searchMarker?.position?.let { destination ->
      val currentLatLng = LatLng(currentPose.latitude, currentPose.longitude)
      val distance = calculateDistance(
        currentLatLng.latitude, currentLatLng.longitude,
        destination.latitude, destination.longitude
      )
      
      // Update distance text
      textViews["distance_text"]?.let {
        val formattedDistance = if (distance < 1000) {
          "${distance.toInt()} meters"
        } else {
          String.format("%.1f km", distance / 1000)
        }
        it.text = "Distance to destination: $formattedDistance"
      }
    }
  }
  
  private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0 // Earth radius in meters
    
    val latDistance = Math.toRadians(lat2 - lat1)
    val lonDistance = Math.toRadians(lon2 - lon1)
    
    val a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2)
    
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    
    return R * c // Distance in meters
  }

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }
}
