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
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import java.io.IOException

/** Contains UI elements for Hello Geo. */
class HelloGeoView(val activity: HelloGeoActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.activity_main, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)
  val searchView = root.findViewById<SearchView>(R.id.searchView)

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
    val geocoder = Geocoder(activity)
    
    try {
      val addressList = geocoder.getFromLocationName(locationName, 1)
      
      if (addressList != null && addressList.isNotEmpty()) {
        val address = addressList[0]
        val latLng = LatLng(address.latitude, address.longitude)
        
        // Use the MapView's navigation method to handle the search location
        mapView?.navigateToSearchLocation(latLng, locationName)
        
        // Create an anchor at the searched location
        activity.renderer.onMapClick(latLng)
        
      } else {
        Toast.makeText(activity, "Location not found", Toast.LENGTH_SHORT).show()
      }
    } catch (e: IOException) {
      Toast.makeText(activity, "Error searching for location", Toast.LENGTH_SHORT).show()
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
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }
}
