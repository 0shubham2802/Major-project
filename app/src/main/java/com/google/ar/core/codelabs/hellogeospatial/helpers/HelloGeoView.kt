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

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.ar.core.codelabs.hellogeospatial.ARActivity
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.codelabs.hellogeospatial.SplitScreenActivity
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper

class HelloGeoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs), LifecycleOwner {
    companion object {
        private const val TAG = "HelloGeoView"
        private val defaultLocation = LatLng(37.7749, -122.4194) // San Francisco
        private const val defaultZoom = 15f
    }

    // Lifecycle implementation
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // Current location - read-only property with private backing field
    private var _currentLocation: LatLng = defaultLocation
    val currentLocation: LatLng get() = _currentLocation

    // Activity references - nullable and private
    private var appCompatActivity: AppCompatActivity? = null
    private var helloGeoActivity: HelloGeoActivity? = null
    private var arActivity: ARActivity? = null
    private var splitScreenActivity: SplitScreenActivity? = null

    // UI elements with proper nullability
    private var _root: View? = null
    private var _surfaceView: GLSurfaceView? = null
    private var _buttonContainer: LinearLayout? = null
    private var _mapView: com.google.android.gms.maps.MapView? = null
    private var _mapTouchWrapper: MapTouchWrapper? = null
    private var _statusText: TextView? = null

    // Public accessors
    val root: View get() = _root ?: throw IllegalStateException("Root view not initialized")
    val surfaceView: GLSurfaceView get() = _surfaceView ?: throw IllegalStateException("Surface view not initialized")
    val buttonContainer: LinearLayout get() = _buttonContainer ?: throw IllegalStateException("Button container not initialized")
    val mapView: com.google.android.gms.maps.MapView? get() = _mapView
    val mapTouchWrapper: MapTouchWrapper? get() = _mapTouchWrapper
    val statusText: TextView? get() = _statusText

    // Navigation state
    private var _isNavigationMode = false
    val isNavigationMode: Boolean get() = _isNavigationMode

    private var _directionsHelper: DirectionsHelper? = null
    val directionsHelper: DirectionsHelper? get() = _directionsHelper

    // Helper classes
    val snackbarHelper = SnackbarHelper()
    private val mapErrorHelper = MapErrorHelper(context)

    // Map state
    private var _googleMap: GoogleMap? = null
    val googleMap: GoogleMap? get() = _googleMap
    private var _isMapInitialized = false
    val isMapInitialized: Boolean get() = _isMapInitialized
    private var routePolyline: Polyline? = null

    // ARCore Session
    private var session: com.google.ar.core.Session? = null

    init {
        // Initialize lifecycle
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // Set up activity reference based on context
        when (context) {
            is HelloGeoActivity -> {
                appCompatActivity = context
                helloGeoActivity = context
            }
            is ARActivity -> {
                appCompatActivity = context
                arActivity = context
            }
            is SplitScreenActivity -> {
                appCompatActivity = context
                splitScreenActivity = context
            }
        }

        initializeViews()
    }

    private fun initializeViews() {
        // Inflate the custom layout
        inflate(context, R.layout.view_hello_geo, this)

        // Initialize views
        _surfaceView = findViewById(R.id.surfaceview)
        _buttonContainer = findViewById(R.id.button_container)
        _mapView = findViewById<com.google.android.gms.maps.MapView>(R.id.map_view)
        _mapTouchWrapper = findViewById(R.id.map_touch_wrapper)
        _statusText = findViewById(R.id.statusText)

        // Set initial lifecycle state
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    // Lifecycle methods
    fun onResume(session: com.google.ar.core.Session? = null) {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        _surfaceView?.onResume()
        // Potentially use the session object here if needed
    }

    fun onPause(session: com.google.ar.core.Session? = null) {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        _surfaceView?.onPause()
        // Potentially use the session object here if needed
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    // Navigation methods
    fun startNavigationMode(destination: LatLng) {
        _isNavigationMode = true
        _mapView?.visibility = View.VISIBLE
        _mapTouchWrapper?.visibility = View.VISIBLE
    }

    fun stopNavigationMode() {
        _isNavigationMode = false
        _mapView?.visibility = View.GONE
        _mapTouchWrapper?.visibility = View.GONE
        routePolyline?.remove()
        routePolyline = null
    }

    // Map initialization
    fun setGoogleMap(map: GoogleMap) {
        _googleMap = map
        _isMapInitialized = true
    }

    fun setupSession(session: com.google.ar.core.Session) {
        this.session = session
    }

    fun addActionButton(button: android.widget.Button, tag: String) {
        // Implementation to add the button to the layout
        // For example, add to _buttonContainer
        button.tag = tag
        _buttonContainer?.addView(button)
    }

    fun setOnDestinationSelectedListener(listener: (LatLng, String) -> Unit) {
        // Store the listener and call it when a destination is selected
        // This will likely involve handling map clicks or search results
    }

    fun getActionButton(tag: String): android.widget.Button? {
        // Implementation to find and return the button with the given tag
        return _buttonContainer?.findViewWithTag(tag)
    }

    fun updateNavigationInstructions(instructions: List<String>) {
        // TODO: Implement this method
    }

    fun showRouteOnMap(currentLocation: LatLng, destination: LatLng) {
        // TODO: Implement this method
    }
}
