package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.ARActivity
import com.google.ar.core.codelabs.hellogeospatial.FallbackActivity
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoRenderer
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.Locale

/**
 * Split-screen activity showing both AR and Map views simultaneously
 */
class SplitScreenActivity : AppCompatActivity(), OnMapReadyCallback {
    companion object {
        private const val TAG = "SplitScreenActivity"
        private const val LOCATION_PERMISSION_CODE = 100
        private const val CAMERA_PERMISSION_CODE = 101
    }

    // Map components
    private lateinit var mapFragment: SupportMapFragment
    private var googleMap: GoogleMap? = null
    
    // AR components
    private lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private lateinit var view: HelloGeoView
    private lateinit var renderer: HelloGeoRenderer
    private lateinit var surfaceView: GLSurfaceView
    
    // Navigation components
    private var destinationLatLng: LatLng? = null
    private var isNavigating = false
    private var trackingQualityIndicator: TextView? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set error handler
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
        }
        
        try {
            // Check if ARCore is supported
            if (!checkARCoreSupport()) {
                // If not supported, redirect to map-only view
                Toast.makeText(this, "AR not supported on this device. Redirecting to map view.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, FallbackActivity::class.java))
                finish()
                return
            }
            
            // Set the content view
            setContentView(R.layout.activity_split_screen)
            
            // Check for required permissions
            checkAndRequestPermissions()
            
            // Initialize the map portion
            initializeMap()
            
            // Initialize the AR portion
            initializeAR()
            
            // Set up UI controls
            setupUIControls()
            
            // Get destination from intent if available
            if (intent.hasExtra("DESTINATION_LAT") && intent.hasExtra("DESTINATION_LNG")) {
                val lat = intent.getDoubleExtra("DESTINATION_LAT", 0.0)
                val lng = intent.getDoubleExtra("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    destinationLatLng = LatLng(lat, lng)
                    destinationLatLng?.let { destination ->
                        // Show destination on map when ready
                        googleMap?.addMarker(MarkerOptions().position(destination).title("Destination"))
                        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f))
                        
                        // Make navigate button visible
                        findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
            fallbackToMapOnlyMode()
        }
    }
    
    private fun checkARCoreSupport(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> true
            else -> false
        }
    }
    
    private fun initializeMap() {
        try {
            mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
            
            // Set a timeout for map loading
            val mapLoadingTimeout = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e(TAG, "Map loading timed out")
                findViewById<LinearLayout>(R.id.map_loading_container)?.visibility = View.GONE
                Toast.makeText(this, "Map loading timed out. Please check your internet connection.", Toast.LENGTH_LONG).show()
            }
            
            mapLoadingTimeout.postDelayed(timeoutRunnable, 20000)
            
            // Initialize the map asynchronously
            mapFragment.getMapAsync(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing map", e)
            Toast.makeText(this, "Error initializing map", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun initializeAR() {
        try {
            // Get the tracking quality indicator
            trackingQualityIndicator = findViewById(R.id.tracking_quality)
            
            // Get the AR surface view
            surfaceView = findViewById(R.id.ar_surface_view)
            
            // Create and initialize HelloGeoView with SplitScreenActivity
            view = HelloGeoView(this)
            
            // Need to set the surface view
            try {
                val field = HelloGeoView::class.java.getDeclaredField("surfaceView")
                field.isAccessible = true
                field.set(view, surfaceView)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting surface view", e)
            }
            
            // Create and initialize the renderer
            renderer = HelloGeoRenderer(this)
            renderer.setView(view)
            
            // Create and initialize ARCore session
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
            
            // Register error handler
            arCoreSessionHelper.exceptionCallback = { exception ->
                val message = when (exception) {
                    is CameraNotAvailableException -> "Camera not available"
                    else -> "AR Error: ${exception.message}"
                }
                Log.e(TAG, "AR error: $message", exception)
                trackingQualityIndicator?.text = "AR Error: ${exception.javaClass.simpleName}"
            }
            
            // Configure the session
            arCoreSessionHelper.beforeSessionResume = ::configureSession
            
            // Initialize the session
            arCoreSessionHelper.onResume()
            
            // Get the session
            val session = arCoreSessionHelper.session
            if (session != null) {
                view.setupSession(session)
                renderer.setSession(session)
            } else {
                trackingQualityIndicator?.text = "Failed to create AR session"
            }
            
            // Set up the renderer
            SampleRender(surfaceView, renderer, assets)
            
            // Start tracking quality updates
            startTrackingQualityUpdates()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AR", e)
            trackingQualityIndicator?.text = "AR Error: ${e.message}"
        }
    }
    
    private fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                // Enable geospatial mode
                geospatialMode = Config.GeospatialMode.ENABLED
                
                // Basic AR settings
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                focusMode = Config.FocusMode.AUTO
            }
        )
    }
    
    private fun startTrackingQualityUpdates() {
        // Update tracking quality status every second
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                updateTrackingQualityIndicator()
                handler.postDelayed(this, 1000)
            }
        })
    }
    
    private fun updateTrackingQualityIndicator() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            if (earth.trackingState != TrackingState.TRACKING) {
                trackingQualityIndicator?.text = "Tracking: INITIALIZING"
                trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_orange_light)
                return
            }
            
            val pose = earth.cameraGeospatialPose
            val horizontalAccuracy = pose.horizontalAccuracy
            
            val qualityText = when {
                horizontalAccuracy <= 1.0 -> "HIGH"
                horizontalAccuracy <= 3.0 -> "MEDIUM"
                else -> "LOW"
            }
            
            trackingQualityIndicator?.text = "Tracking: $qualityText (±${horizontalAccuracy.toInt()}m)"
            
            val colorRes = when {
                horizontalAccuracy <= 1.0 -> android.R.color.holo_green_light
                horizontalAccuracy <= 3.0 -> android.R.color.holo_orange_light
                else -> android.R.color.holo_red_light
            }
            
            trackingQualityIndicator?.setBackgroundResource(colorRes)
            
        } catch (e: Exception) {
            trackingQualityIndicator?.text = "Tracking: ERROR"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
        }
    }
    
    private fun setupUIControls() {
        // Setup search bar
        val searchBar = findViewById<EditText>(R.id.searchBar)
        searchBar.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString()
                if (query.isNotBlank()) {
                    searchLocation(query)
                    return@setOnEditorActionListener true
                }
            }
            return@setOnEditorActionListener false
        }
        
        // Setup navigation button
        val navigateButton = findViewById<Button>(R.id.navigateButton)
        navigateButton.setOnClickListener {
            destinationLatLng?.let { destination ->
                startNavigation(destination)
            }
        }
        
        // Setup mode toggle buttons
        val arModeButton = findViewById<Button>(R.id.ar_mode_button)
        arModeButton.setOnClickListener {
            switchToAROnlyMode()
        }
        
        val mapModeButton = findViewById<Button>(R.id.map_mode_button)
        mapModeButton.setOnClickListener {
            switchToMapOnlyMode()
        }
    }
    
    private fun searchLocation(query: String) {
        try {
            // Show loading indicator
            findViewById<View>(R.id.map_loading_container)?.visibility = View.VISIBLE
            
            val geocoder = Geocoder(this, Locale.getDefault())
            
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                
                // Hide loading indicator
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)
                    
                    // Update map
                    googleMap?.apply {
                        clear()
                        addMarker(MarkerOptions().position(latLng).title(query))
                        animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    }
                    
                    // Store as destination
                    destinationLatLng = latLng
                    
                    // Show navigation button
                    findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                Toast.makeText(this, "Error searching: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startNavigation(destination: LatLng) {
        try {
            isNavigating = true
            
            // Create AR anchors if Earth is tracking
            val session = arCoreSessionHelper.session
            val earth = session?.earth
            
            if (earth?.trackingState == TrackingState.TRACKING) {
                val cameraGeospatialPose = earth.cameraGeospatialPose
                val currentLatLng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
                
                // Create a simple path from current to destination
                val simplePath = listOf(currentLatLng, destination)
                
                // Create anchors for the path
                renderer.createPathAnchors(simplePath)
                
                Toast.makeText(this, "AR navigation started", Toast.LENGTH_SHORT).show()
            } else {
                // If AR is not ready, fall back to Google Maps navigation
                openGoogleMapsNavigation(destination)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting navigation", e)
            Toast.makeText(this, "Error starting navigation: ${e.message}", Toast.LENGTH_SHORT).show()
            
            // Fall back to Google Maps navigation
            openGoogleMapsNavigation(destination)
        }
    }
    
    private fun openGoogleMapsNavigation(destination: LatLng) {
        val uri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
        mapIntent.setPackage("com.google.android.apps.maps")
        
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            Toast.makeText(this, "Google Maps app is not installed", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun switchToAROnlyMode() {
        val intent = Intent(this, ARActivity::class.java)
        
        // Pass destination data if we have it
        destinationLatLng?.let {
            intent.putExtra("DESTINATION_LAT", it.latitude)
            intent.putExtra("DESTINATION_LNG", it.longitude)
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun switchToMapOnlyMode() {
        fallbackToMapOnlyMode()
    }
    
    private fun fallbackToMapOnlyMode() {
        val intent = Intent(this, FallbackActivity::class.java)
        
        // Pass destination data if we have it
        destinationLatLng?.let {
            intent.putExtra("DESTINATION_LAT", it.latitude)
            intent.putExtra("DESTINATION_LNG", it.longitude)
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun checkAndRequestPermissions() {
        // Check for location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
        
        // Check for camera permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, enable my location on the map
                    try {
                        googleMap?.isMyLocationEnabled = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not enable my location", e)
                    }
                } else {
                    Toast.makeText(this, "Location permission required for navigation", Toast.LENGTH_LONG).show()
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Camera permission required for AR", Toast.LENGTH_LONG).show()
                    // Switch to map-only mode if camera permission denied
                    switchToMapOnlyMode()
                }
            }
        }
    }
    
    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        
        // Hide the loading indicator
        findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
        
        try {
            // Configure map settings
            map.uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isMyLocationButtonEnabled = true
            }
            
            // Enable my location if we have permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                    == PackageManager.PERMISSION_GRANTED) {
                map.isMyLocationEnabled = true
            }
            
            // Set click listener for selecting location
            map.setOnMapClickListener { latLng ->
                map.clear()
                map.addMarker(MarkerOptions().position(latLng).title("Selected Location"))
                
                // Store as destination
                destinationLatLng = latLng
                
                // Show navigation button
                findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
            }
            
            // Set initial position
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(37.7749, -122.4194), 10f))
            
            // If we already have a destination, show it
            destinationLatLng?.let { destination ->
                map.addMarker(MarkerOptions().position(destination).title("Destination"))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring map", e)
            Toast.makeText(this, "Error configuring map: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        try {
            arCoreSessionHelper.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming AR session", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            arCoreSessionHelper.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AR session", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            // Clean up any resources
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }
} 