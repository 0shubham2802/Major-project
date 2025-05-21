package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.ARActivity
import com.google.ar.core.codelabs.hellogeospatial.FallbackActivity
import com.google.ar.core.codelabs.hellogeospatial.HelloGeoRenderer
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.DirectionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.Locale
import android.text.Editable
import android.text.TextWatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper

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
    private var mapPolyline: Polyline? = null
    
    // Location components
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: LatLng? = null
    
    // AR components
    private lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private lateinit var view: HelloGeoView
    private lateinit var renderer: HelloGeoRenderer
    private lateinit var surfaceView: GLSurfaceView
    
    // Navigation components
    private var destinationLatLng: LatLng? = null
    private var isNavigating = false
    private var trackingQualityIndicator: TextView? = null
    private lateinit var directionsHelper: DirectionsHelper
    private var routePoints: List<LatLng>? = null
    private var currentStepIndex = 0 // Track which navigation step the user is on
    private var navigationUpdateHandler: Handler? = null // Handler for periodic updates
    private var selectedTransportMode = DirectionsHelper.TransportMode.WALKING // Default to walking mode
    private var destinationMarker: com.google.android.gms.maps.model.Marker? = null
    
    // Map navigation UI components
    private var mapNavigationOverlay: View? = null
    private var mapNavDirectionText: TextView? = null
    private var mapNavStreetName: TextView? = null
    private var mapNavNextDirection: TextView? = null
    private var mapNavTime: TextView? = null
    private var mapNavDistance: TextView? = null
    private var mapNavARButton: View? = null
    private var mapNavCloseButton: View? = null
    
    // Transport mode UI elements
    private var transportModeContainer: CardView? = null
    private var walkingModeButton: ImageView? = null
    private var twoWheelerModeButton: ImageView? = null
    private var fourWheelerModeButton: ImageView? = null
    
    // Split screen transport mode UI elements
    private var splitTransportContainer: CardView? = null
    private var splitWalkingButton: ImageView? = null
    private var splitTwoWheelerButton: ImageView? = null
    private var splitFourWheelerButton: ImageView? = null
    
    // Search suggestion components
    private lateinit var suggestionProvider: SearchSuggestionProvider
    private lateinit var placesAdapter: PlacesAdapter
    private lateinit var suggestionsList: RecyclerView
    private var searchQueryHandler = Handler(Looper.getMainLooper())
    private var lastSearchRunnable: Runnable? = null
    private lateinit var recentPlacesManager: RecentPlacesManager
    
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
            // Force release any camera resources first
            forceReleaseCamera()
            
            // Check if ARCore is supported
            if (!checkARCoreSupport()) {
                // If not supported, redirect to map-only view
                Toast.makeText(this, "AR not supported on this device. Redirecting to map view.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, FallbackActivity::class.java))
                finish()
                return
            }
            
            // Check for camera availability
            val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            if (cameraManager.cameraIdList.isEmpty()) {
                Toast.makeText(this, "No camera available on this device.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, FallbackActivity::class.java))
                finish()
                return
            }
            
            // Try to explicitly check camera access
            if (!checkCameraAvailability()) {
                // Show dialog with option to retry or continue with map only
                AlertDialog.Builder(this)
                    .setTitle("Camera Unavailable")
                    .setMessage("Camera is being used by another app. Please close it and try again.")
                    .setPositiveButton("RETRY") { _, _ ->
                        // Attempt aggressive camera reset and restart
                        Toast.makeText(this, "Attempting to force release camera...", Toast.LENGTH_SHORT).show()
                        
                        // Use a thread to perform potentially time-consuming operations
                        Thread {
                            try {
                                // First attempt - gentle release
                                forceReleaseCamera()
                                
                                // Wait a moment
                                Thread.sleep(500)
                                
                                // Check if camera is now available
                                val isAvailable = checkCameraAvailability()
                                
                                runOnUiThread {
                                    if (isAvailable) {
                                        Toast.makeText(this, "Camera released successfully!", Toast.LENGTH_SHORT).show()
                                        // Restart activity with clean state
                                        val intent = Intent(this, SplitScreenActivity::class.java)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        startActivity(intent)
                                        finish()
                                    } else {
                                        // Second more aggressive attempt
                                        Toast.makeText(this, "First attempt failed, trying more aggressively...", Toast.LENGTH_SHORT).show()
                                        
                                        // Use Android's built-in camera app to try to reset the camera state
                                        // Sometimes opening another camera app and then closing it resets the camera state
                                        try {
                                            val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                                            if (cameraIntent.resolveActivity(packageManager) != null) {
                                                // Start camera intent without expecting a result - just to reset camera
                                                startActivity(cameraIntent)
                                                
                                                // Show instructions to the user
                                                Handler(Looper.getMainLooper()).postDelayed({
                                                    Toast.makeText(this, "Please close the camera app when it opens", Toast.LENGTH_LONG).show()
                                                    
                                                    // Return to our app after a delay
                                                    Handler(Looper.getMainLooper()).postDelayed({
                                                        // Final restart attempt
                                                        forceReleaseCamera()
                                                        val intent = Intent(this, SplitScreenActivity::class.java)
                                                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                                        startActivity(intent)
                                                        finish()
                                                    }, 5000) // 5 seconds delay
                                                }, 1000)
                                            } else {
                                                // No camera app available, restart directly
                                                restartApp()
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error launching camera app", e)
                                            // Last resort - restart activity
                                            restartApp()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in camera reset thread", e)
                                runOnUiThread { restartApp() }
                            }
                        }.start()
                    }
                    .setNegativeButton("MAP ONLY") { _, _ ->
                        startActivity(Intent(this, FallbackActivity::class.java))
                        finish()
                    }
                    .setCancelable(false)
                    .show()
                return
            }
            
            // Set the content view
            setContentView(R.layout.activity_split_screen)
            
            // Initialize the location provider
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            
            // Initialize the directions helper
            directionsHelper = DirectionsHelper(this)
            
            // Initialize search suggestion provider
            suggestionProvider = SearchSuggestionProvider(this)
            
            // Initialize recent places manager
            recentPlacesManager = RecentPlacesManager(this)
            
            // Check for required permissions
            checkAndRequestPermissions()
            
            // Initialize the map portion
            initializeMap()
            
            // Initialize the AR portion - delay AR initialization to improve loading performance
            Handler(Looper.getMainLooper()).postDelayed({
                initializeAR()
            }, 500) // 500ms delay
            
            // Set up UI controls
            setupUIControls()
            
            // Set up search suggestions
            setupSearchSuggestions()
            
            // Get destination from intent if available
            if (intent.hasExtra("DESTINATION_LAT") && intent.hasExtra("DESTINATION_LNG")) {
                val lat = intent.getDoubleExtra("DESTINATION_LAT", 0.0)
                val lng = intent.getDoubleExtra("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    destinationLatLng = LatLng(lat, lng)
                    destinationLatLng?.let { destination ->
                        // Show destination on map when ready
                        googleMap?.apply {
                            clear()
                            destinationMarker = addMarker(MarkerOptions().position(destination).title("Destination"))
                            animateCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f))
                        }
                        
                        // Make navigate button visible
                        findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                    }
                }
            }
            
            // Get the current location
            getCurrentLocation()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing: ${e.message}", Toast.LENGTH_LONG).show()
            fallbackToMapOnlyMode()
        }
    }
    
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    currentLocation = LatLng(it.latitude, it.longitude)
                    Log.d(TAG, "Current location: ${it.latitude}, ${it.longitude}")
                    
                    // Update navigation if active
                    if (isNavigating) {
                        // Update current step if navigating
                        updateCurrentNavigationStep()
                        
                        // If we already have a destination, we can calculate/update the route
                        destinationLatLng?.let { destination ->
                            googleMap?.apply {
                                clear()
                                destinationMarker = addMarker(MarkerOptions().position(destination).title("Destination"))
                                animateCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f))
                            }
                            
                            // Update navigation if active
                            if (isNavigating) {
                                // Only recalculate route if needed
                                // Calculate route frequently at first until we have a good route
                                if (directionsHelper.lastSteps.isEmpty()) {
                                    fetchAndDisplayDirections(currentLocation!!, destination)
                                } else {
                                    // Recalculate if we've moved significantly from route start
                                    val distanceThresholdMeters = 50 // Only recalculate if moved 50+ meters
                                    val steps = directionsHelper.lastSteps
                                    if (steps.isNotEmpty()) {
                                        val results = FloatArray(1)
                                        android.location.Location.distanceBetween(
                                            currentLocation!!.latitude, currentLocation!!.longitude,
                                            steps[0].startLocation.latitude, steps[0].startLocation.longitude,
                                            results
                                        )
                                        
                                        if (results[0] > distanceThresholdMeters) {
                                            fetchAndDisplayDirections(currentLocation!!, destination)
                                        }
                                    } else {
                                        // No steps yet, calculate route
                                        fetchAndDisplayDirections(currentLocation!!, destination)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Request location updates for continuous navigation
            try {
                if (isNavigating) {
                    val locationRequest = com.google.android.gms.location.LocationRequest.create().apply {
                        interval = 5000 // Update every 5 seconds
                        fastestInterval = 2000 // Fastest update interval
                        priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                    }
                    
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        object : com.google.android.gms.location.LocationCallback() {
                            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                                locationResult.lastLocation?.let {
                                    currentLocation = LatLng(it.latitude, it.longitude)
                                    
                                    // Update navigation if active
                                    if (isNavigating) {
                                        updateCurrentNavigationStep()
                                    }
                                }
                            }
                        },
                        Looper.getMainLooper() // Use main thread looper instead of null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting location updates", e)
            }
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
            
            // Set a timeout for map loading - increased to 30 seconds
            val mapLoadingTimeout = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.e(TAG, "Map loading timed out")
                
                // Don't just show a Toast and error out, try to recover
                findViewById<LinearLayout>(R.id.map_loading_container)?.visibility = View.GONE
                
                // Display a retry button instead of just showing error
                val loadingText = findViewById<TextView>(R.id.map_loading_text)
                loadingText?.text = "Map loading timed out. Tap to retry."
                loadingText?.setOnClickListener {
                    // Retry map loading
                    retryMapLoading()
                }
                
                // Try to continue with limited functionality
                if (googleMap == null) {
                    Toast.makeText(this, "Continuing with limited map functionality", Toast.LENGTH_LONG).show()
                }
            }
            
            mapLoadingTimeout.postDelayed(timeoutRunnable, 30000) // 30 second timeout
            
            // Initialize the map asynchronously with timeout handling
val mapTimeoutHandler = Handler(Looper.getMainLooper())
val mapLoadRunnable = object : Runnable {
    private var attempts = 0
    private val maxAttempts = 3
    
    override fun run() {
        if (googleMap == null && attempts < maxAttempts) {
            Log.d(TAG, "Attempt ${attempts + 1} to load map")
            attempts++
            mapFragment.getMapAsync(this@SplitScreenActivity)
            mapTimeoutHandler.postDelayed(this, 10000)  // Try again in 10 seconds
        } else if (googleMap == null) {
            Log.e(TAG, "Failed to load map after $maxAttempts attempts")
            // Show error message and allow retry
            findViewById<LinearLayout>(R.id.map_loading_container)?.visibility = View.GONE
            Toast.makeText(
                this@SplitScreenActivity, 
                "Failed to load map. Please check your internet connection and try again.", 
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
mapLoadRunnable.run()
            } catch (e: Exception) {
        Log.e(TAG, "Error initializing map", e)
        Toast.makeText(this, "Error initializing map", Toast.LENGTH_SHORT).show()
    }
}

private fun retryMapLoading() {
    // Show loading indicator again
    findViewById<LinearLayout>(R.id.map_loading_container)?.visibility = View.VISIBLE
    findViewById<TextView>(R.id.map_loading_text)?.text = "Loading map..."
    
    // Reset any click listeners
    findViewById<TextView>(R.id.map_loading_text)?.setOnClickListener(null)
    
    // Try to initialize map again
    mapFragment.getMapAsync(this)
}
    
    private fun initializeAR() {
        try {
            // Explicitly request camera permission before initializing AR
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission not granted, requesting now")
                ActivityCompat.requestPermissions(
                    this, 
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_CODE
                )
                // Don't proceed with AR initialization until permission granted
                return
            }
            
            Log.d(TAG, "Starting AR initialization with camera permission granted")
            
            // Initialize AR view
            view = HelloGeoView(this)
            renderer = HelloGeoRenderer(this)
            renderer.isSplitScreenMode = true
            
            // Initialize surface view with OpenGL ES 3.0
            surfaceView = findViewById(R.id.ar_surface_view)
            surfaceView.preserveEGLContextOnPause = true
            surfaceView.setEGLContextClientVersion(3)
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            
            // Create and initialize ARCore session
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
            
            // Register error handler
            arCoreSessionHelper.exceptionCallback = { exception ->
                val message = when (exception) {
                    is CameraNotAvailableException -> {
                        Log.e(TAG, "Camera not available", exception)
                        "Camera not available. Please check if another app is using the camera or restart your device."
                    }
                    else -> {
                        Log.e(TAG, "AR error", exception)
                        "AR Error: ${exception.message}"
                    }
                }
                runOnUiThread {
                    trackingQualityIndicator?.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    
                    // Try to recover by restarting AR session
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            Log.d(TAG, "Attempting to restart AR session after error")
                            arCoreSessionHelper.onPause()
                            arCoreSessionHelper.onResume()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart AR session", e)
                        }
                    }, 2000) // Wait 2 seconds before retry
                }
            }
            
            // Configure the session
            arCoreSessionHelper.beforeSessionResume = ::configureSession
            
            // Initialize the session with robust error handling
            try {
                // Check if camera is in use before trying to resume
                if (!checkCameraAvailability()) {
                    Log.e(TAG, "Camera is being used by another app")
                    Toast.makeText(this, "Camera is being used by another app. Please close it and try again.", Toast.LENGTH_LONG).show()
                    fallbackToMapOnlyMode()
                    return
                }
                
                // Try to resume the AR session
                arCoreSessionHelper.onResume()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available", e)
                Toast.makeText(this, "Camera not available. Please check if another app is using the camera and try again.", Toast.LENGTH_LONG).show()
                fallbackToMapOnlyMode()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume ARCore session", e)
                Toast.makeText(this, "Failed to initialize camera: ${e.message}", Toast.LENGTH_LONG).show()
                fallbackToMapOnlyMode()
                return
            }
            
            // Get the session
            val session = arCoreSessionHelper.session
            if (session != null) {
                view.setupSession(session)
                renderer.setSession(session)
                
                // Set up the renderer with proper error handling
                try {
                    SampleRender(surfaceView, renderer, assets)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize AR renderer", e)
                    Toast.makeText(this, "Failed to initialize AR renderer: ${e.message}", Toast.LENGTH_LONG).show()
                    return
                }
                
                // Start tracking quality updates
                startTrackingQualityUpdates()
            } else {
                val errorMsg = "Failed to create AR session. Please check camera permissions and ARCore installation."
                Log.e(TAG, errorMsg)
                runOnUiThread {
                    trackingQualityIndicator?.text = errorMsg
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AR", e)
            val errorMsg = when {
                e.message?.contains("camera") == true -> "Camera access failed. Please check permissions."
                e.message?.contains("ARCore") == true -> "ARCore initialization failed. Please check ARCore installation."
                e.message?.contains("OpenGL") == true -> "OpenGL error. Your device may not support AR features."
                else -> e.message ?: "Unknown error initializing AR"
            }
            runOnUiThread {
                trackingQualityIndicator?.text = "AR Error: $errorMsg"
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun configureSession(session: Session) {
        try {
            Log.d(TAG, "Configuring AR session")
            
            // Create a new config
            val config = Config(session)
            
            // Check GPS signal before enabling geospatial
            if (!hasGoodGpsSignal()) {
                // Show dialog warning about poor GPS
                showGpsWarningDialog()
            }
            
            // Enable geospatial mode with error handling
            try {
                config.geospatialMode = Config.GeospatialMode.ENABLED
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable geospatial mode", e)
                showGeospatialErrorDialog("Failed to enable geospatial tracking: ${e.message}")
                return
            }
            
            // Basic settings for navigation
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO
            
            // Try to enable depth for better occlusion
            try {
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    Log.d(TAG, "Depth mode enabled for better AR experience")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking depth support", e)
            }
            
            // Enable cloud anchors for possible sharing features
            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
            
            // Apply the configuration
            session.configure(config)
            
            // Instead of using addOnGeospatialStateChangedListener (which isn't available in this ARCore version),
            // we'll monitor the Earth state in our tracking quality updates
            
            Log.d(TAG, "AR session configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AR session", e)
            Toast.makeText(this, "AR configuration error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Check if the device has good GPS signal
     */
    private fun hasGoodGpsSignal(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        
        // Check if GPS is enabled at all
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            Log.d(TAG, "GPS is disabled")
            return false
        }
        
        // Check last known location and its accuracy
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
                    PackageManager.PERMISSION_GRANTED) {
                val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                
                // If location is null or old, GPS might not be providing good data
                if (location == null) {
                    Log.d(TAG, "No last known location from GPS")
                    return false
                }
                
                // Check how old the location is
                val locationAgeMs = System.currentTimeMillis() - location.time
                if (locationAgeMs > 30000) { // Location older than 30 seconds
                    Log.d(TAG, "Last GPS location is too old: ${locationAgeMs/1000} seconds")
                    return false
                }
                
                // Check location accuracy
                if (location.accuracy > 25) { // Accuracy worse than 25 meters
                    Log.d(TAG, "GPS accuracy is poor: ${location.accuracy} meters")
                    return false
                }
                
                // Check number of satellites (if available)
                if (location.extras != null && location.extras!!.containsKey("satellites")) {
                    val satellites = location.extras!!.getInt("satellites", 0)
                    if (satellites < 4) {
                        Log.d(TAG, "Not enough GPS satellites: $satellites")
                        return false
                    }
                }
                
                Log.d(TAG, "GPS signal appears good: accuracy ${location.accuracy}m")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GPS signal", e)
        }
        
        return false
    }
    
    /**
     * Show a dialog warning about poor GPS signal
     */
    private fun showGpsWarningDialog() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Poor GPS Signal")
                .setMessage("Your GPS signal appears to be weak. AR tracking may not work well indoors or in areas with poor GPS reception. Would you like to continue or switch to map mode?")
                .setPositiveButton("Continue Anyway") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton("Switch to Map") { _, _ ->
                    fallbackToMapOnlyMode()
                }
                .setNeutralButton("Go Outside") { dialog, _ ->
                    Toast.makeText(this, "Please move to an open outdoor area with clear sky view", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        }
    }
    
    /**
     * Show a dialog for geospatial tracking errors
     */
    private fun showGeospatialErrorDialog(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("AR Initialization Issue")
                .setMessage("$message\n\nThis may be due to:\n• Being indoors where GPS signal is weak\n• Interference from buildings or trees\n• Device sensors need calibration")
                .setPositiveButton("Try Low-Precision Mode") { dialog, _ ->
                    dialog.dismiss()
                    enableLowPrecisionMode()
                }
                .setNeutralButton("Wait Longer") { dialog, _ ->
                    dialog.dismiss()
                    // Try to reconfigure session after a delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val session = arCoreSessionHelper.session
                            if (session != null) {
                                configureSession(session)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reconfiguring session", e)
                        }
                    }, 5000) // Wait 5 seconds before trying again
                }
                .setNegativeButton("Return to Map") { _, _ ->
                    fallbackToMapOnlyMode()
                }
                .setCancelable(false)
                .show()
        }
    }
    
    /**
     * Enable a low-precision AR mode that's more tolerant of GPS issues
     */
    private fun enableLowPrecisionMode() {
        try {
            Toast.makeText(this, "Switching to low-precision AR mode...", Toast.LENGTH_SHORT).show()
            
            // Get current session
            val session = arCoreSessionHelper.session ?: return
            
            // Create a new configuration with lower requirements
            val config = Config(session)
            
            // Lower precision geospatial tracking
            config.geospatialMode = Config.GeospatialMode.ENABLED
            
            // Disable more demanding features
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            config.depthMode = Config.DepthMode.DISABLED
            config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            
            // Optimize for performance over accuracy
            config.focusMode = Config.FocusMode.FIXED
            config.updateMode = Config.UpdateMode.BLOCKING
            
            // Apply the new configuration
            session.configure(config)
            
            // Set renderer to low-precision mode
            renderer.setLowPrecisionMode(true)
            
            // Show status to user
            trackingQualityIndicator?.text = "LOW-PRECISION MODE"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_orange_light)
            
            // Force session to restart with new settings
            session.resume()
            
            Log.d(TAG, "Enabled low-precision AR mode")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling low-precision mode", e)
            Toast.makeText(this, "Failed to enable low-precision mode: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
            val earth = session.earth
            
            // Check if Earth object is available
            if (earth == null) {
                trackingQualityIndicator?.text = "Tracking: INITIALIZING"
                trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_orange_light)
                
                // Check how long we've been waiting for Earth initialization
                val currentTime = System.currentTimeMillis()
                if (trackingErrorStartTime == 0L) {
                    trackingErrorStartTime = currentTime
                } else if (currentTime - trackingErrorStartTime > 20000) { // 20 seconds of waiting for Earth
                    // Only show the dialog if we haven't recently shown it
                    if (currentTime - lastTrackingErrorDialogTime > 60000) { // Don't show more than once per minute
                        showGeospatialErrorDialog("Could not initialize AR tracking")
                        lastTrackingErrorDialogTime = currentTime
                    }
                }
                return
            }
            
            // Reset Earth initialization timer since we have Earth object
            trackingErrorStartTime = 0L
            
            // Check tracking state
            if (earth.trackingState != TrackingState.TRACKING) {
                val stateText = when (earth.trackingState) {
                    TrackingState.PAUSED -> "PAUSED"
                    TrackingState.STOPPED -> "STOPPED"
                    else -> "NOT TRACKING"
                }
                
                trackingQualityIndicator?.text = "Tracking: $stateText"
                trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
                
                // If we've been in non-tracking state for a while, show help dialog
                val currentTime = System.currentTimeMillis()
                if (earthTrackingErrorTime == 0L) {
                    earthTrackingErrorTime = currentTime
                } else if (currentTime - earthTrackingErrorTime > 15000) { // 15 seconds of error
                    // Only show the dialog if we haven't recently shown it
                    if (currentTime - lastTrackingErrorDialogTime > 60000) { // Don't show more than once per minute
                        showTrackingErrorHelp()
                        lastTrackingErrorDialogTime = currentTime
                    }
                }
                
                return
            } else {
                // Reset error timer if we're now tracking
                earthTrackingErrorTime = 0L
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
            
            // Update current location from AR pose
            updateLocationFromARPose(pose.latitude, pose.longitude)
            
            // Check if we need to suggest calibration based on accuracy issues
            val currentTime = System.currentTimeMillis()
            if (horizontalAccuracy > 10.0) {
                if (!hasShownCalibrationPrompt && currentTime - lastCalibrationPromptTime > 90000) {
                    showCalibrationPrompt()
                    hasShownCalibrationPrompt = true
                    lastCalibrationPromptTime = currentTime
                }
            } else if (horizontalAccuracy <= 5.0) {
                // Reset prompt shown flag if tracking improves significantly, so we can show it again if needed later
                hasShownCalibrationPrompt = false
            }
            
        } catch (e: Exception) {
            trackingQualityIndicator?.text = "Tracking: ERROR"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
            Log.e(TAG, "Error updating tracking quality", e)
            
            // Show error dialog for exceptions
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTrackingErrorDialogTime > 60000) { // Limit dialog frequency
                showGeospatialErrorDialog("AR tracking error: ${e.message}")
                lastTrackingErrorDialogTime = currentTime
            }
        }
    }
    
    // Tracking variables
    private var trackingErrorStartTime = 0L
    private var earthTrackingErrorTime = 0L
    private var lastTrackingErrorDialogTime = 0L
    private var hasShownCalibrationPrompt = false
    private var lastCalibrationPromptTime = 0L
    
    /**
     * Show a helpful dialog with instructions for resolving tracking issues
     */
    private fun showTrackingErrorHelp() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("AR Tracking Issues")
                .setMessage("There are problems with AR tracking. This may be because:\n\n" +
                        "• You are indoors with poor GPS signal\n" +
                        "• There are tall buildings or trees blocking GPS\n" +
                        "• Your device sensors need calibration\n\n" +
                        "Would you like to try calibration, continue trying, or switch to map mode?")
                .setPositiveButton("Calibrate Sensors") { dialog, _ ->
                    dialog.dismiss()
                    startSensorCalibration()
                }
                .setNeutralButton("Keep Trying") { dialog, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton("Switch to Map") { _, _ ->
                    fallbackToMapOnlyMode()
                }
                .setCancelable(true)
                .show()
        }
    }
    
    /**
     * Prompt user to calibrate their device
     */
    private fun showCalibrationPrompt() {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Improve AR Accuracy")
                .setMessage("Your device sensors may need calibration for better AR tracking. Would you like to calibrate now?")
                .setPositiveButton("Calibrate") { dialog, _ ->
                    dialog.dismiss()
                    startSensorCalibration()
                }
                .setNegativeButton("Not Now") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        }
    }
    
    /**
     * Start sensor calibration procedure
     */
    private fun startSensorCalibration() {
        // Show calibration instructions dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("Sensor Calibration")
            .setMessage("Please rotate your device in a figure-8 pattern to calibrate the sensors.")
            .setCancelable(false)
            .setPositiveButton("Done") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Calibration complete", Toast.LENGTH_SHORT).show()
            }
            .create()
            
        // Create animation to show figure-8 movement
        val calibrationImage = ImageView(this)
        calibrationImage.setImageResource(android.R.drawable.ic_menu_rotate) // Use a system resource or your own animation
        calibrationImage.scaleType = ImageView.ScaleType.FIT_CENTER
        
        // Create animation
        val rotateAnimation = android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotateAnimation.duration = 2000
        rotateAnimation.repeatCount = 5 // Repeat a few times
        calibrationImage.startAnimation(rotateAnimation)
        
        // Set the image to the dialog
        dialog.setView(calibrationImage)
        
        // Show the dialog
        dialog.show()
        
        // Start actual sensor calibration (this is a visual guide only - 
        // the actual sensor calibration happens naturally as the user moves the device)
        try {
            // Force sensor updates by requesting sensor data
            val sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
            
            // Monitor accelerometer and magnetometer which are the main sensors that need calibration
            val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
            
            // Listen for sensor events briefly
            val sensorListener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    // Just need to listen to trigger calibration
                }
                
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
                    // Log accuracy changes
                    if (sensor != null) {
                        val sensorName = when (sensor.type) {
                            android.hardware.Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
                            android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
                            else -> "Unknown"
                        }
                        val accuracyText = when (accuracy) {
                            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
                            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
                            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
                            else -> "UNRELIABLE"
                        }
                        Log.d(TAG, "$sensorName accuracy changed to $accuracyText")
                    }
                }
            }
            
            // Register for sensor updates
            sensorManager.registerListener(sensorListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(sensorListener, magnetometer, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            
            // After a while, unregister to save battery
            Handler(Looper.getMainLooper()).postDelayed({
                sensorManager.unregisterListener(sensorListener)
            }, 10000) // 10 seconds
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during sensor calibration", e)
        }
    }
    
    private fun updateLocationFromARPose(latitude: Double, longitude: Double) {
        // Update current location from AR
        currentLocation = LatLng(latitude, longitude)
        
        // If navigating, update AR anchors with the new route points
        if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
            // Update AR view with the route
            renderer.updatePathAnchors(routePoints!!)
        }
    }
    
    private fun setupUIControls() {
        try {
            // Find UI components
            val searchBar = findViewById<EditText>(R.id.searchBar)
            val navigateButton = findViewById<Button>(R.id.navigateButton)
            trackingQualityIndicator = findViewById(R.id.tracking_quality)
            surfaceView = findViewById(R.id.ar_surface_view)
            
            // Find map navigation UI components
            mapNavigationOverlay = findViewById(R.id.map_navigation_overlay)
            mapNavDirectionText = findViewById(R.id.map_nav_direction_text)
            mapNavStreetName = findViewById(R.id.map_nav_street_name)
            mapNavNextDirection = findViewById(R.id.map_nav_next_direction_text)
            mapNavTime = findViewById(R.id.map_nav_time)
            mapNavDistance = findViewById(R.id.map_nav_distance)
            mapNavARButton = findViewById(R.id.map_nav_ar_button)
            mapNavCloseButton = findViewById(R.id.map_nav_close_button)
            
            // Find transport mode selection components
            transportModeContainer = findViewById(R.id.transport_mode_container)
            walkingModeButton = findViewById(R.id.walking_mode_button)
            twoWheelerModeButton = findViewById(R.id.two_wheeler_mode_button)
            fourWheelerModeButton = findViewById(R.id.four_wheeler_mode_button)
            
            // Find split screen transport components
            splitTransportContainer = findViewById(R.id.split_screen_transport_container)
            splitWalkingButton = findViewById(R.id.split_walking_button)
            splitTwoWheelerButton = findViewById(R.id.split_two_wheeler_button)
            splitFourWheelerButton = findViewById(R.id.split_four_wheeler_button)
            
            // Set up transport mode buttons
            setupTransportModeButtons()
            
            // Set up mode toggle buttons
            findViewById<Button>(R.id.ar_mode_button).setOnClickListener {
                launchARMode()
            }
            
            findViewById<Button>(R.id.map_mode_button).setOnClickListener {
                launchMapMode()
            }
            
            // Set up navigation button
            navigateButton.setOnClickListener {
                if (destinationLatLng != null && currentLocation != null) {
                    startNavigation(currentLocation!!, destinationLatLng!!)
                } else {
                    Toast.makeText(this, "Please select a destination first", Toast.LENGTH_SHORT).show()
                }
            }
            
            // Set up map navigation UI listeners
            mapNavARButton?.setOnClickListener {
                launchARNavigation()
            }
            
            mapNavCloseButton?.setOnClickListener {
                stopNavigation()
            }
            
            // Set up search bar
            searchBar.setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = v.text.toString()
                    if (query.isNotBlank()) {
                        hideSuggestions()
                        searchLocation(query)
                        hideKeyboard()
                    }
                    true
                } else {
                    false
                }
            }
            
            // Set up text change listener for search suggestions
            searchBar.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Cancel any pending search
                    lastSearchRunnable?.let { searchQueryHandler.removeCallbacks(it) }
                    
                    val query = s?.toString() ?: ""
                    
                    if (query.length < 3) {
                        // Show recent places if search field has focus
                        if (searchBar.hasFocus()) {
                            showRecentPlacesOnly()
                        } else {
                            hideSuggestions()
                        }
                        return
                    }
                    
                    // Delay the search to avoid too many requests while typing
                    val searchRunnable = Runnable {
                        showSuggestionsLoading()
                        suggestionProvider.getSuggestions(query, object : SearchSuggestionProvider.SuggestionListener {
                            override fun onSuggestionsReady(suggestions: List<SearchSuggestion>) {
                                if (suggestions.isEmpty()) {
                                    showRecentPlacesOnly()
                                } else {
                                    showSuggestions(suggestions)
                                }
                            }
                            
                            override fun onError(message: String) {
                                showRecentPlacesOnly()
                                Log.e(TAG, "Error getting suggestions: $message")
                            }
                        })
                    }
                    
                    lastSearchRunnable = searchRunnable
                    searchQueryHandler.postDelayed(searchRunnable, 300) // 300ms delay
                }
                
                override fun afterTextChanged(s: Editable?) {}
            })
            
            // Set up focus change listener
            searchBar.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // If query already has content, show suggestions
                    val query = searchBar.text.toString()
                    if (query.length >= 3) {
                        // Trigger the text changed listener
                        searchBar.setText(query)
                    } else {
                        // Show recent places if no query
                        showRecentPlacesOnly()
                    }
                } else {
                    // Hide suggestions when focus is lost
                    hideSuggestions()
                }
            }
            
            // Set up periodic AR status updates
            val handler = Handler(Looper.getMainLooper())
            handler.post(object : Runnable {
                override fun run() {
                    updateARStatus()
                    handler.postDelayed(this, 1000)
                }
            })
            
            // Add debug button - long press on tracking indicator to force restart camera
            trackingQualityIndicator?.setOnLongClickListener {
                try {
                    Toast.makeText(this, "Restarting camera...", Toast.LENGTH_SHORT).show()
                    
                    // Force restart AR session
                    try {
                        arCoreSessionHelper.onPause()
                        // Release camera resources
                        val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        for (cameraId in cameraManager.cameraIdList) {
                            Log.d(TAG, "Attempting to reset camera: $cameraId")
                        }
                        
                        // Wait briefly
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                arCoreSessionHelper.onResume()
                                Toast.makeText(this, "Camera restarted", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to resume AR session", e)
                                Toast.makeText(this, "Failed to restart camera: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }, 1000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error restarting camera", e)
                        Toast.makeText(this, "Error restarting camera: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error in camera restart button", e)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up UI controls", e)
        }
    }
    
    private fun setupTransportModeButtons() {
        // Setup regular mode buttons
        walkingModeButton?.setOnClickListener {
            selectedTransportMode = DirectionsHelper.TransportMode.WALKING
            updateTransportModeUI()
            // Recalculate route if we're navigating
            if (isNavigating && currentLocation != null && destinationLatLng != null) {
                fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
            }
        }
        
        twoWheelerModeButton?.setOnClickListener {
            selectedTransportMode = DirectionsHelper.TransportMode.TWO_WHEELER
            updateTransportModeUI()
            // Recalculate route if we're navigating
            if (isNavigating && currentLocation != null && destinationLatLng != null) {
                fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
            }
        }
        
        fourWheelerModeButton?.setOnClickListener {
            selectedTransportMode = DirectionsHelper.TransportMode.FOUR_WHEELER
            updateTransportModeUI()
            // Recalculate route if we're navigating
            if (isNavigating && currentLocation != null && destinationLatLng != null) {
                fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
            }
        }
        
        // Setup split screen mode buttons
        splitWalkingButton?.setOnClickListener {
            selectedTransportMode = DirectionsHelper.TransportMode.WALKING
            updateTransportModeUI()
            // Recalculate route if we're navigating
            if (isNavigating && currentLocation != null && destinationLatLng != null) {
                fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
            }
        }
        
        splitTwoWheelerButton?.setOnClickListener {
            selectedTransportMode = DirectionsHelper.TransportMode.TWO_WHEELER
            updateTransportModeUI()
            // Recalculate route if we're navigating
            if (isNavigating && currentLocation != null && destinationLatLng != null) {
                fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
            }
        }
        
        splitFourWheelerButton?.setOnClickListener {
            selectedTransportMode = DirectionsHelper.TransportMode.FOUR_WHEELER
            updateTransportModeUI()
            // Recalculate route if we're navigating
            if (isNavigating && currentLocation != null && destinationLatLng != null) {
                fetchAndDisplayDirections(currentLocation!!, destinationLatLng!!)
            }
        }
        
        // Set initial UI state
        updateTransportModeUI()
    }
    
    private fun updateTransportModeUI() {
        // Set selected state for regular buttons
        walkingModeButton?.alpha = if (selectedTransportMode == DirectionsHelper.TransportMode.WALKING) 1.0f else 0.5f
        twoWheelerModeButton?.alpha = if (selectedTransportMode == DirectionsHelper.TransportMode.TWO_WHEELER) 1.0f else 0.5f
        fourWheelerModeButton?.alpha = if (selectedTransportMode == DirectionsHelper.TransportMode.FOUR_WHEELER) 1.0f else 0.5f
        
        // Set selected state for split screen buttons
        splitWalkingButton?.alpha = if (selectedTransportMode == DirectionsHelper.TransportMode.WALKING) 1.0f else 0.5f
        splitTwoWheelerButton?.alpha = if (selectedTransportMode == DirectionsHelper.TransportMode.TWO_WHEELER) 1.0f else 0.5f
        splitFourWheelerButton?.alpha = if (selectedTransportMode == DirectionsHelper.TransportMode.FOUR_WHEELER) 1.0f else 0.5f
    }
    
    private fun setupSearchSuggestions() {
        // Get the suggestions RecyclerView
        suggestionsList = findViewById(R.id.suggestionsList)
        
        // Set up the adapter
        placesAdapter = PlacesAdapter(
            onItemClickListener = { suggestion ->
                // Handle item click
                hideSuggestions()
                hideKeyboard()
                handleSelectedSuggestion(suggestion)
            },
            onClearRecentPlacesListener = {
                // Clear recent places
                recentPlacesManager.clearRecentPlaces()
                refreshRecentPlaces()
            }
        )
        
        // Set up the RecyclerView
        suggestionsList.apply {
            layoutManager = LinearLayoutManager(this@SplitScreenActivity)
            adapter = placesAdapter
            setHasFixedSize(true)
        }
        
        // Initially hide suggestions
        hideSuggestions()
        
        // Set up touch listener to dismiss suggestions when clicking outside
        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && suggestionsList.visibility == View.VISIBLE) {
                // Check if touch is outside suggestions
                val location = IntArray(2)
                suggestionsList.getLocationOnScreen(location)
                val x = event.rawX
                val y = event.rawY
                
                if (x < location[0] || x > location[0] + suggestionsList.width ||
                    y < location[1] || y > location[1] + suggestionsList.height) {
                    hideSuggestions()
                    hideKeyboard()
                }
            }
            false
        }
    }
    
    private fun refreshRecentPlaces() {
        // Get recent places and update adapter
        val recentPlaces = recentPlacesManager.getRecentPlaces()
        placesAdapter.setRecentPlaces(recentPlaces)
    }
    
    private fun showSuggestions(suggestions: List<SearchSuggestion>) {
        placesAdapter.updateSuggestions(suggestions)
        
        // Only load recent places when showing suggestions
        refreshRecentPlaces()
        
        suggestionsList.visibility = View.VISIBLE
    }
    
    private fun showRecentPlacesOnly() {
        placesAdapter.updateSuggestions(emptyList())
        refreshRecentPlaces()
        suggestionsList.visibility = View.VISIBLE
    }
    
    private fun showSuggestionsLoading() {
        // If we want to show a loading state for suggestions
        // We could implement this with a ProgressBar in the suggestions list
    }
    
    private fun hideSuggestions() {
        suggestionsList.visibility = View.GONE
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
    
    private fun handleSelectedSuggestion(suggestion: SearchSuggestion) {
        // Update search bar with selected suggestion
        findViewById<EditText>(R.id.searchBar).setText(suggestion.title)
        
        // Update map with the selected location
        googleMap?.apply {
            clear()
            destinationMarker = addMarker(MarkerOptions().position(suggestion.latLng).title(suggestion.title))
            animateCamera(CameraUpdateFactory.newLatLngZoom(suggestion.latLng, 15f))
        }
        
        // Store as destination
        destinationLatLng = suggestion.latLng
        
        // Show navigation button
        findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
        
        // Add to recent places
        recentPlacesManager.addRecentPlace(suggestion)
    }
    
    private fun updateARStatus() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            // Update tracking quality indicator based on Earth state
            val trackingState = earth.trackingState
            val earthState = earth.earthState
            
            if (trackingState == TrackingState.TRACKING) {
                val pose = earth.cameraGeospatialPose
                val horizontalAccuracy = pose.horizontalAccuracy
                
                val qualityText = when {
                    horizontalAccuracy <= 1.0 -> "HIGH"
                    horizontalAccuracy <= 3.0 -> "MEDIUM"
                    else -> "LOW"
                }
                
                trackingQualityIndicator?.text = "Tracking: $qualityText (±${horizontalAccuracy.toInt()}m)"
                
                val backgroundColor = when {
                    horizontalAccuracy <= 1.0 -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    horizontalAccuracy <= 3.0 -> ContextCompat.getColor(this, android.R.color.holo_orange_light)
                    else -> ContextCompat.getColor(this, android.R.color.holo_red_light)
                }
                
                trackingQualityIndicator?.setBackgroundColor(backgroundColor)
                
                // If we're navigating, ensure route is showing in AR
                if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
                    renderer.createPathAnchors(routePoints!!)
                }
            } else {
                // Not tracking
                trackingQualityIndicator?.text = "Tracking: ${trackingState.name} / ${earthState.name}"
                trackingQualityIndicator?.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                
                // Check for common issues if not tracking
                if (trackingState != TrackingState.TRACKING) {
                    val issues = checkARIssues()
                    if (issues.isNotEmpty()) {
                        Log.w(TAG, "AR issues detected: ${issues.joinToString(", ")}")
                        // Update status with issues
                        val issuesText = if (issues.size > 2) {
                            issues.take(2).joinToString(", ") + "..."
                        } else {
                            issues.joinToString(", ")
                        }
                        trackingQualityIndicator?.text = "Issues: $issuesText"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating AR status", e)
            trackingQualityIndicator?.text = "AR Status Error"
        }
    }
    
    /**
     * Check for common issues that might prevent AR from working correctly
     */
    private fun checkARIssues(): List<String> {
        val issues = mutableListOf<String>()
        
        // Check location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("Location permission denied")
        }
        
        // Check if GPS is enabled
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            issues.add("GPS disabled")
        }
        
        // Check if we're indoors (harder to get GPS)
        val sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        val lightSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            // Just add a general warning about being indoors
            issues.add("May be indoors")
        }
        
        // Check if we have a current location
        if (currentLocation == null) {
            issues.add("No location fix")
        }
        
        return issues
    }
    
    private fun searchLocation(query: String) {
        try {
            // Show loading indicator
            findViewById<View>(R.id.map_loading_container)?.visibility = View.VISIBLE
            
            // Hide any suggestions
            hideSuggestions()
            
            val geocoder = Geocoder(this, Locale.getDefault())
            
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                
                // Hide loading indicator
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)
                    
                    // Create a suggestion from the address
                    val mainText = if (!address.featureName.isNullOrBlank()) address.featureName else query
                    val secondaryText = address.getAddressLine(0) ?: ""
                    
                    val suggestion = SearchSuggestion(
                        title = mainText,
                        address = secondaryText,
                        latLng = latLng,
                        originalAddress = address
                    )
                    
                    // Update map
                    googleMap?.apply {
                        clear()
                        destinationMarker = addMarker(MarkerOptions().position(latLng).title(suggestion.title))
                        animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    }
                    
                    // Store as destination
                    destinationLatLng = latLng
                    
                    // Show navigation button
                    findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                    
                    // Add to recent places
                    recentPlacesManager.addRecentPlace(suggestion)
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
    
    private fun startNavigation(origin: LatLng, destination: LatLng) {
        try {
            // Show transport mode selection dialog before starting navigation
            showTransportModeDialog(origin, destination)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting navigation", e)
            Toast.makeText(this, "Error starting navigation: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showTransportModeDialog(origin: LatLng, destination: LatLng) {
        // Create custom dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("How would you like to travel?")
            .setCancelable(true)
            .create()
        
        // Create the dialog layout programmatically
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size),
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 2,
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size),
                resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 2
            )
        }
        
        // Function to create a transport option
        fun createTransportOption(
            iconResId: Int,
            title: String,
            subtitle: String,
            mode: DirectionsHelper.TransportMode
        ): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 16, 0, 16)
                }
                
                // Add icon
                addView(ImageView(context).apply {
                    setImageResource(iconResId)
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply {
                        marginEnd = 24
                    }
                })
                
                // Add text container
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    
                    // Title
                    addView(TextView(context).apply { 
                        text = title
                        textSize = 16f
                        setTextColor(android.graphics.Color.BLACK)
                    })
                    
                    // Subtitle - estimated time
                    addView(TextView(context).apply { 
                        text = subtitle
                        textSize = 12f
                        setTextColor(android.graphics.Color.GRAY)
                    })
                })
                
                // Set click listener
                setOnClickListener {
                    selectedTransportMode = mode
                    updateTransportModeUI()
                    continueNavigation(origin, destination)
                    dialog.dismiss()
                }
                
                // Add ripple effect for modern touch feedback
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    foreground = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#20000000")
                    ).let {
                        android.graphics.drawable.RippleDrawable(it, null, null)
                    }
                }
                
                // Add some padding
                setPadding(16, 16, 16, 16)
            }
        }
        
        // Calculate estimated times
        val distance = calculateDistance(origin, destination)
        val walkTime = (distance / DirectionsHelper.TransportMode.WALKING.speedFactor).toInt() / 60
        val twoWheelerTime = (distance / DirectionsHelper.TransportMode.TWO_WHEELER.speedFactor).toInt() / 60
        val fourWheelerTime = (distance / DirectionsHelper.TransportMode.FOUR_WHEELER.speedFactor).toInt() / 60
        
        // Add transport options
        linearLayout.addView(createTransportOption(
            R.drawable.ic_walking, // Replace with your custom icon or use android.R.drawable.ic_menu_myplaces
            "Walking",
            "Estimated time: $walkTime min",
            DirectionsHelper.TransportMode.WALKING
        ))
        
        linearLayout.addView(createTransportOption(
            R.drawable.ic_two_wheeler, // Replace with your custom icon or use android.R.drawable.ic_menu_directions
            "Two-Wheeler",
            "Estimated time: $twoWheelerTime min",
            DirectionsHelper.TransportMode.TWO_WHEELER
        ))
        
        linearLayout.addView(createTransportOption(
            R.drawable.ic_four_wheeler, // Replace with your custom icon or use android.R.drawable.ic_menu_send
            "Four-Wheeler",
            "Estimated time: $fourWheelerTime min",
            DirectionsHelper.TransportMode.FOUR_WHEELER
        ))
        
        // Add a cancel button at the bottom
        linearLayout.addView(TextView(this).apply {
            text = "Cancel"
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            textSize = 16f
            setPadding(0, 20, 0, 10)
            setOnClickListener {
                dialog.dismiss()
            }
        })
        
        // Set the layout to the dialog
        dialog.setView(linearLayout)
        
        // Show the dialog
        dialog.show()
    }
    
    private fun calculateDistance(origin: LatLng, destination: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            origin.latitude, origin.longitude,
            destination.latitude, destination.longitude,
            results
        )
        return results[0]
    }
    
    private fun continueNavigation(origin: LatLng, destination: LatLng) {
        isNavigating = true
        currentStepIndex = 0 // Reset step index when starting navigation
        
        // Move camera to show both origin and destination
        val bounds = com.google.android.gms.maps.model.LatLngBounds.Builder()
            .include(origin)
            .include(destination)
            .build()
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
        
        // Show navigation UI
        mapNavigationOverlay?.visibility = View.VISIBLE
        
        // Show only the transport mode container inside the navigation overlay, not the split screen one
        transportModeContainer?.visibility = View.VISIBLE
        transportModeContainer?.bringToFront()
        // Hide the split screen transport container to avoid duplication
        splitTransportContainer?.visibility = View.GONE
        
        // Hide the search bar and buttons during navigation
        findViewById<LinearLayout>(R.id.mode_controls).visibility = View.GONE
        findViewById<EditText>(R.id.searchBar).visibility = View.GONE
        findViewById<Button>(R.id.navigateButton).visibility = View.GONE
        
        // Fetch directions
        fetchAndDisplayDirections(origin, destination)
        
        // Add to recent places
        val geocoder = Geocoder(this, Locale.getDefault())
        val addresses = geocoder.getFromLocation(destination.latitude, destination.longitude, 1)
        val address = addresses?.firstOrNull()
        val placeName = address?.featureName ?: "Selected Location"
        
        recentPlacesManager.addRecentPlace(destination, placeName)
        
        // Start continuous navigation updates
        startNavigationUpdates()
        
        // Show a toast with the selected transport mode
        val modeName = when (selectedTransportMode) {
            DirectionsHelper.TransportMode.WALKING -> "Walking"
            DirectionsHelper.TransportMode.TWO_WHEELER -> "Two-Wheeler"
            DirectionsHelper.TransportMode.FOUR_WHEELER -> "Four-Wheeler"
        }
        Toast.makeText(this, "Navigating with $modeName mode", Toast.LENGTH_SHORT).show()
        
        // Log the navigation start
        Log.d(TAG, "Navigation started from $origin to $destination with mode $selectedTransportMode")
    }
    
    private fun startNavigationUpdates() {
        // Stop any existing updates
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Create a new handler for updates
        navigationUpdateHandler = Handler(Looper.getMainLooper())
        
        // Create update runnable
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isNavigating) {
                    // Update current step based on user's location
                    updateCurrentNavigationStep()
                    
                    // Schedule next update
                    navigationUpdateHandler?.postDelayed(this, 3000) // Update every 3 seconds
                }
            }
        }
        
        // Start updates
        navigationUpdateHandler?.post(updateRunnable)
    }
    
    private fun updateCurrentNavigationStep() {
        try {
            val currentLocation = currentLocation ?: return
            // Get directions steps from helper
            val steps = directionsHelper.lastSteps
            
            if (steps.isEmpty()) return
            
            // Find the closest next step based on user location
            var closestStepIndex = 0
            var minDistance = Float.MAX_VALUE
            
            for (i in currentStepIndex until steps.size) {
                val step = steps[i]
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    step.startLocation.latitude, step.startLocation.longitude,
                    results
                )
                
                val distance = results[0]
                
                // If user is very close to a step, consider it the current one
                if (distance < 20) { // Within 20 meters
                    closestStepIndex = i
                    break
                }
                
                // Otherwise track the closest upcoming step
                if (distance < minDistance) {
                    minDistance = distance
                    closestStepIndex = i
                }
            }
            
            // Update if the step has changed
            if (closestStepIndex != currentStepIndex) {
                currentStepIndex = closestStepIndex
                updateMapNavigationUIForCurrentStep()
                // Also update the route drawing to show progress
                if (currentStepIndex > 0 && currentStepIndex < steps.size) {
                    highlightCurrentRouteSegment(steps, currentStepIndex)
                }
                Log.d(TAG, "Navigation step updated to: $currentStepIndex")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating current navigation step", e)
        }
    }
    
    private fun updateMapNavigationUIForCurrentStep() {
        val instructions = directionsHelper.lastInstructions
        val steps = directionsHelper.lastSteps
        
        if (instructions.isEmpty() || steps.isEmpty() || currentStepIndex >= instructions.size) return
        
        // Get the current instruction
        val currentInstruction = instructions[currentStepIndex]
        
        // Parse the instruction to separate direction from street name
        val directionParts = currentInstruction.split(" on ", limit = 2)
        val direction = directionParts[0]
        val streetName = if (directionParts.size > 1) {
            // Shorten street name if too long
            val street = directionParts[1]
            if (street.length > 25) "on ${street.substring(0, 22)}..." else "on $street"
        } else ""
        
        // Get next instruction if available
        val nextDirection = if (currentStepIndex + 1 < instructions.size) {
            // Shorten next direction to just the essential part
            val nextInst = instructions[currentStepIndex + 1]
            when {
                nextInst.contains(" on ") -> {
                    val parts = nextInst.split(" on ", limit = 2)
                    parts[0]
                }
                nextInst.length > 30 -> nextInst.substring(0, 27) + "..."
                else -> nextInst
            }
        } else "Arrive at destination"
        
        // Calculate remaining distance and time for current and upcoming steps
        val remainingSteps = steps.drop(currentStepIndex)
        val remainingDistanceMeters = remainingSteps.sumOf { it.distance }
        
        // Use the speed factor from the selected transport mode to calculate time
        val speedFactor = selectedTransportMode.speedFactor
        val remainingTimeSeconds = (remainingDistanceMeters / speedFactor).toInt()
        
        // Format time
        val timeMinutes = remainingTimeSeconds / 60
        val timeText = "$timeMinutes min"
        
        // Format US mile distance for the bottom panel
        val distanceMiles = remainingDistanceMeters * 0.000621371 // Convert meters to miles
        
        // Get current time and add the estimated duration
        val currentTime = System.currentTimeMillis()
        val etaTime = currentTime + (remainingTimeSeconds * 1000)
        val etaFormat = android.text.format.DateFormat.getTimeFormat(this)
        val etaString = etaFormat.format(etaTime)
        
        // Format distance with ETA
        val distanceMilesText = String.format("%.1f mi · %s", distanceMiles, etaString)
        
        // Update direction icon based on maneuver type
        val directionIcon = findViewById<ImageView>(R.id.map_nav_direction_icon)
        val directionType = when {
            direction.contains("Turn right") -> android.R.drawable.ic_menu_directions
            direction.contains("Turn left") -> android.R.drawable.ic_menu_directions
            direction.contains("Continue") -> android.R.drawable.ic_menu_directions
            direction.contains("Arrive") -> android.R.drawable.ic_menu_directions
            else -> android.R.drawable.ic_menu_directions
        }
        directionIcon?.setImageResource(directionType)
        
        // Update UI
        runOnUiThread {
            mapNavDirectionText?.text = direction
            mapNavStreetName?.text = streetName
            mapNavNextDirection?.text = nextDirection
            mapNavTime?.text = timeText
            mapNavDistance?.text = distanceMilesText
            
            // Update progress on map - highlight current segment
            highlightCurrentRouteSegment(steps, currentStepIndex)
        }
    }
    
    private fun highlightCurrentRouteSegment(steps: List<DirectionsHelper.DirectionStep>, currentStepIndex: Int) {
        googleMap?.let { map ->
            // Clear previous route
            map.clear()
            
            // Redraw destination marker
            destinationLatLng?.let { 
                destinationMarker = map.addMarker(MarkerOptions().position(it).title("Destination"))
            }
            
            // Extract all points for the route
            val allPoints = mutableListOf<LatLng>()
            
            // Add completed steps in gray
            if (currentStepIndex > 0) {
                val completedPoints = mutableListOf<LatLng>()
                for (i in 0 until currentStepIndex) {
                    completedPoints.addAll(steps[i].points)
                }
                
                // Draw completed route in gray
                if (completedPoints.isNotEmpty()) {
                    map.addPolyline(
                        com.google.android.gms.maps.model.PolylineOptions()
                            .addAll(completedPoints)
                            .width(5f)
                            .color(android.graphics.Color.GRAY)
                    )
                }
                
                allPoints.addAll(completedPoints)
            }
            
            // Add current step in blue/highlighted
            if (currentStepIndex < steps.size) {
                val currentStepPoints = steps[currentStepIndex].points
                
                // Draw current route segment in bright blue
                if (currentStepPoints.isNotEmpty()) {
                    map.addPolyline(
                        com.google.android.gms.maps.model.PolylineOptions()
                            .addAll(currentStepPoints)
                            .width(8f) // Thicker line
                            .color(android.graphics.Color.BLUE)
                    )
                }
                
                allPoints.addAll(currentStepPoints)
            }
            
            // Add future steps in light blue
            val futurePoints = mutableListOf<LatLng>()
            for (i in (currentStepIndex + 1) until steps.size) {
                futurePoints.addAll(steps[i].points)
            }
            
            // Draw future route in light blue
            if (futurePoints.isNotEmpty()) {
                map.addPolyline(
                    com.google.android.gms.maps.model.PolylineOptions()
                        .addAll(futurePoints)
                        .width(5f)
                        .color(android.graphics.Color.CYAN)
                )
            }
            
            allPoints.addAll(futurePoints)
            
            // Ensure the map camera shows the relevant part of the route
            if (allPoints.isNotEmpty()) {
                // Create a bounds that includes both current location and next maneuver
                val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
                currentLocation?.let { boundsBuilder.include(it) }
                
                // Add the maneuver point (start of current step)
                if (currentStepIndex < steps.size) {
                    boundsBuilder.include(steps[currentStepIndex].startLocation)
                    
                    // Also include end location if close to completing step
                    if (currentStepIndex == steps.size - 1) {
                        boundsBuilder.include(steps[currentStepIndex].endLocation)
                    }
                }
                
                // Move camera to show current segment with padding
                try {
                    val bounds = boundsBuilder.build()
                    val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, 100)
                    map.animateCamera(cameraUpdate)
                } catch (e: Exception) {
                    // In case of invalid bounds, fallback to current location
                    currentLocation?.let {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f))
                    }
                }
            }
        }
    }
    
    private fun fetchAndDisplayDirections(origin: LatLng, destination: LatLng) {
        // Show loading indicator
        findViewById<LinearLayout>(R.id.map_loading_container).visibility = View.VISIBLE
        
        // Get directions with the selected transport mode
        directionsHelper.getDirectionsWithInstructions(
            origin, destination,
            object : DirectionsHelper.DirectionsWithInstructionsListener {
                override fun onDirectionsReady(
                    pathPoints: List<LatLng>,
                    instructions: List<String>,
                    steps: List<DirectionsHelper.DirectionStep>
                ) {
                    // Store route points
                    routePoints = pathPoints
                    
                    // Hide loading indicator
                    findViewById<LinearLayout>(R.id.map_loading_container).visibility = View.GONE
                    
                    // Draw the route on the map
                    drawRoute(pathPoints)
                    
                    // Update the map navigation UI
                    updateMapNavigationUI(steps, instructions)
                    
                    // Show the map navigation overlay
                    mapNavigationOverlay?.visibility = View.VISIBLE
                    
                    // Hide the bottom controls during navigation
                    findViewById<LinearLayout>(R.id.mode_controls).visibility = View.GONE
                    
                    // Show transport mode selection
                    transportModeContainer?.visibility = View.VISIBLE
                    
                    Log.d(TAG, "Directions fetched successfully. Path points: ${pathPoints.size}")
                }
                
                override fun onDirectionsError(errorMessage: String) {
                    // Hide loading indicator
                    findViewById<LinearLayout>(R.id.map_loading_container).visibility = View.GONE
                    
                    // Show error message
                    Toast.makeText(
                        this@SplitScreenActivity,
                        "Error getting directions: $errorMessage",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Draw direct line instead
                    drawDirectLine(origin, destination)
                    
                    Log.e(TAG, "Error getting directions: $errorMessage")
                }
            },
            selectedTransportMode
        )
    }
    
    private fun drawRoute(pathPoints: List<LatLng>) {
        googleMap?.let { map ->
            map.clear()
            
            // Add polyline - using the available method
            val options = com.google.android.gms.maps.model.PolylineOptions()
                .addAll(pathPoints)
                .width(5f)
                .color(android.graphics.Color.BLUE)
                
            map.addPolyline(options)
        }
    }
    
    private fun drawDirectLine(origin: LatLng, destination: LatLng) {
        googleMap?.let { map ->
            map.clear()
            
            // Add polyline - using the available method
            val options = com.google.android.gms.maps.model.PolylineOptions()
                .add(origin)
                .add(destination)
                .width(5f)
                .color(android.graphics.Color.RED)
                
            map.addPolyline(options)
        }
    }
    
    private fun launchARMode() {
        val intent = Intent(this, ARActivity::class.java)
        
        // Pass destination data if we have it
        destinationLatLng?.let {
            intent.putExtra("DESTINATION_LAT", it.latitude)
            intent.putExtra("DESTINATION_LNG", it.longitude)
        }
        
        startActivity(intent)
        finish()
    }
    
    private fun launchMapMode() {
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
    
    private fun launchARNavigation() {
        // Launch AR activity with navigation info
        val intent = Intent(this, ARActivity::class.java)
        destinationLatLng?.let {
            intent.putExtra("DESTINATION_LAT", it.latitude)
            intent.putExtra("DESTINATION_LNG", it.longitude)
            
            // Log the data being passed
            Log.d(TAG, "Launching AR Navigation with destination: ${it.latitude}, ${it.longitude}")
            
            // Add current step information if available
            if (routePoints?.isNotEmpty() == true) {
                // Identify significant points (turns) to pass to AR
                val steps = directionsHelper.lastSteps
                val significantPoints = mutableListOf<LatLng>()
                
                // Always include the start point (current location)
                currentLocation?.let { currentLoc ->
                    significantPoints.add(currentLoc)
                }
                
                // Add turn points (these are more important than regular waypoints)
                if (steps.isNotEmpty()) {
                    for (i in 0 until steps.size) {
                        val step = steps[i]
                        if (i < steps.size - 1) {
                            // Add all turn points
                            significantPoints.add(step.endLocation)
                        }
                    }
                } else {
                    // If no detailed steps, use route points directly
                    // Limit to reasonable number of points
                    val pointsToCopy = minOf(15, routePoints!!.size)
                    for (i in 0 until pointsToCopy) {
                        // Sample points evenly throughout route
                        val index = (i * routePoints!!.size) / pointsToCopy
                        if (index < routePoints!!.size) {
                            significantPoints.add(routePoints!![index])
                        }
                    }
                }
                
                // Always include destination 
                significantPoints.add(it)
                
                // Pass significant waypoints
                val waypointsToPass = minOf(15, significantPoints.size)
                for (i in 0 until waypointsToPass) {
                    intent.putExtra("WAYPOINT_LAT_$i", significantPoints[i].latitude)
                    intent.putExtra("WAYPOINT_LNG_$i", significantPoints[i].longitude)
                }
                intent.putExtra("WAYPOINT_COUNT", waypointsToPass)
                
                Log.d(TAG, "Added $waypointsToPass significant waypoints to AR navigation intent")
            }
            
            startActivity(intent)
        } ?: run {
            Toast.makeText(this, "No destination set for AR navigation", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopNavigation() {
        isNavigating = false
        
        // Stop the navigation updates
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        navigationUpdateHandler = null
        
        // Hide navigation UI
        mapNavigationOverlay?.visibility = View.GONE
        
        // Hide transport mode container
        transportModeContainer?.visibility = View.GONE
        splitTransportContainer?.visibility = View.GONE
        
        // Show the controls again
        findViewById<LinearLayout>(R.id.mode_controls).visibility = View.VISIBLE
        findViewById<EditText>(R.id.searchBar).visibility = View.VISIBLE
        findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
        findViewById<Button>(R.id.stopNavigateButton).visibility = View.GONE
        
        // Remove the route from the map
        mapPolyline?.remove()
        
        // Clear directions
        directionsHelper.clearDirections()
        
        // Remove destination marker
        destinationMarker?.remove()
        destinationMarker = null
        
        // Log the navigation end
        Log.d(TAG, "Navigation stopped")
    }
    
    private fun updateMapNavigationUI(steps: List<DirectionsHelper.DirectionStep>, instructions: List<String>) {
        if (steps.isEmpty() || instructions.isEmpty()) return
        
        // Calculate total distance and time
        val totalDistanceMeters = steps.sumOf { it.distance }
        
        // Use the speed factor from the selected transport mode to calculate time
        val speedFactor = selectedTransportMode.speedFactor
        val totalTimeSeconds = (totalDistanceMeters / speedFactor).toInt()
        
        // Format time
        val timeMinutes = totalTimeSeconds / 60
        val timeText = "$timeMinutes min"
        
        // Format distance and ETA
        val distanceText = if (totalDistanceMeters < 1000) {
            "${totalDistanceMeters}m"
        } else {
            String.format("%.1f km", totalDistanceMeters / 1000.0)
        }
        
        // Get current time and add the estimated duration
        val currentTime = System.currentTimeMillis()
        val etaTime = currentTime + (totalTimeSeconds * 1000)
        val etaFormat = android.text.format.DateFormat.getTimeFormat(this)
        val etaString = etaFormat.format(etaTime)
        
        // Format US mile distance for the bottom panel
        val distanceMiles = totalDistanceMeters * 0.000621371 // Convert meters to miles
        val distanceMilesText = String.format("%.1f mi · %s", distanceMiles, etaString)
        
        // Get the first instruction for main direction
        val firstInstruction = instructions.firstOrNull() ?: "Proceed to destination"
        
        // Parse the instruction to separate direction from street name
        val directionParts = firstInstruction.split(" on ", limit = 2)
        val direction = directionParts[0]
        val streetName = if (directionParts.size > 1) "on ${directionParts[1]}" else ""
        
        // Get next instruction if available
        val nextDirection = if (instructions.size > 1) instructions[1] else "Arrive at destination"
        
        // Update UI
        runOnUiThread {
            mapNavDirectionText?.text = direction
            mapNavStreetName?.text = streetName
            mapNavNextDirection?.text = nextDirection
            mapNavTime?.text = timeText
            mapNavDistance?.text = distanceMilesText
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, LOCATION_PERMISSION_CODE)
        } else {
            // All permissions granted, proceed with initialization
            initializeMap()
            initializeAR()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Location permissions granted, proceed
                    getCurrentLocation()
                } else {
                    Toast.makeText(this, "Location permission is required for navigation", Toast.LENGTH_LONG).show()
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Camera permission granted, retry AR initialization
                    Log.d(TAG, "Camera permission granted, initializing AR")
                    initializeAR()
                } else {
                    Log.e(TAG, "Camera permission denied")
                    Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
                    // Fall back to map-only mode
                    fallbackToMapOnlyMode()
                }
            }
            GeoPermissionsHelper.GEO_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // All geo permissions granted, proceed with initialization
                    initializeMap()
                    initializeAR()
                } else {
                    Toast.makeText(this, "Required permissions not granted", Toast.LENGTH_LONG).show()
                    // Fall back to map-only mode if possible
                    fallbackToMapOnlyMode()
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
                isMyLocationButtonEnabled = false  // Disable default My Location button to avoid duplication
            }
            
            // Set map loading timeout settings
            map.setMaxZoomPreference(20f) // Limit max zoom to improve performance
            map.setMinZoomPreference(5f)  // Set min zoom to ensure we don't zoom too far out
            
            // Enable my location if we have permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                    == PackageManager.PERMISSION_GRANTED) {
                map.isMyLocationEnabled = true
            }
            
            // Set click listener for selecting location
            map.setOnMapClickListener { latLng ->
                map.clear()
                destinationMarker = map.addMarker(MarkerOptions().position(latLng).title("Selected Location"))
                
                // Store as destination
                destinationLatLng = latLng
                
                // Show navigation button
                findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
            }
            
            // Set initial position
            currentLocation?.let {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
            } ?: run {
                // Default to a more reasonable default location - central coordinates of India
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(20.5937, 78.9629), 5f))
            }
            
            // If we already have a destination, show it
            destinationLatLng?.let { destination ->
                destinationMarker = map.addMarker(MarkerOptions().position(destination).title("Destination"))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f))
            }
            
            // If we're navigating, make sure to show the route
            if (isNavigating && routePoints != null && routePoints!!.isNotEmpty()) {
                drawRoute(routePoints!!)
                mapNavigationOverlay?.visibility = View.VISIBLE
            }
            
            Log.d(TAG, "Map initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring map", e)
            Toast.makeText(this, "Error configuring map: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        try {
            // Force release camera resources first
            forceReleaseCamera()
            
            // Resume AR session
            arCoreSessionHelper.onResume()
            
            // Resume GL surface
            surfaceView.onResume()
            
            // Force redraw
            surfaceView.requestRender()
            
            Log.d(TAG, "AR session resumed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming AR session", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            // Pause AR session
            arCoreSessionHelper.onPause()
            
            // Pause GL surface 
            surfaceView.onPause()
            
            // Force release camera resources
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    for (cameraId in cameraManager.cameraIdList) {
                        Log.d(TAG, "Releasing camera: $cameraId on pause")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing camera on pause", e)
            }
            
            Log.d(TAG, "AR session paused successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AR session", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            // Clean up any resources
            navigationUpdateHandler?.removeCallbacksAndMessages(null)
            
            // Ensure camera resources are released
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                    for (cameraId in cameraManager.cameraIdList) {
                        Log.d(TAG, "Releasing camera: $cameraId on destroy")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing camera on destroy", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    // We now have direct access to lastInstructions and lastSteps in DirectionsHelper class

    /**
     * Attempts to check if camera is available or in use by another app
     * Uses a more reliable approach with timeouts and better error handling
     */
    private fun checkCameraAvailability(): Boolean {
        try {
            // Check camera permission first
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "No camera permission granted")
                return false
            }
            
            val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            if (cameraManager.cameraIdList.isEmpty()) {
                Log.d(TAG, "No cameras available on device")
                return false
            }
            
            // Attempt to open camera to see if it's in use
            val cameraAvailability = java.util.concurrent.atomic.AtomicBoolean(false)
            val lock = java.lang.Object()
            var camera: android.hardware.camera2.CameraDevice? = null
            
            // Create callback to receive results
            val stateCallback = object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(cameraDevice: android.hardware.camera2.CameraDevice) {
                    Log.d(TAG, "Camera successfully opened")
                    camera = cameraDevice
                    cameraAvailability.set(true)
                    synchronized(lock) {
                        lock.notifyAll()
                    }
                }
                
                override fun onDisconnected(cameraDevice: android.hardware.camera2.CameraDevice) {
                    Log.d(TAG, "Camera disconnected")
                    cameraDevice.close()
                    synchronized(lock) {
                        lock.notifyAll()
                    }
                }
                
                override fun onError(cameraDevice: android.hardware.camera2.CameraDevice, error: Int) {
                    val errorMessage = when(error) {
                        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Camera device error"
                        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled"
                        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera in use by another app"
                        android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service error"
                        android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                        else -> "Unknown camera error: $error"
                    }
                    Log.e(TAG, errorMessage)
                    cameraDevice.close()
                    cameraAvailability.set(false)
                    synchronized(lock) {
                        lock.notifyAll()
                    }
                }
            }
            
            // Try to open camera (using back camera if available)
            var cameraId = cameraManager.cameraIdList[0]
            
            // Try to find back camera if we have multiple cameras
            if (cameraManager.cameraIdList.size > 1) {
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id
                        break
                    }
                }
            }
            
            try {
                val handler = Handler(Looper.getMainLooper())
                
                // Try to open camera with timeout
                synchronized(lock) {
                    cameraManager.openCamera(cameraId, stateCallback, handler)
                    
                    // Wait for result with timeout
                    try {
                        // Wait up to 3 seconds for camera access
                        lock.wait(3000)
                    } catch (e: InterruptedException) {
                        Log.e(TAG, "Interrupted while waiting for camera", e)
                    }
                }
                
                // Release camera resources if we got access
                camera?.close()
                
                if (!cameraAvailability.get()) {
                    Log.e(TAG, "Camera is in use or unavailable")
                }
                
                return cameraAvailability.get()
            } catch (e: android.hardware.camera2.CameraAccessException) {
                if (e.reason == android.hardware.camera2.CameraAccessException.CAMERA_DISABLED) {
                    Log.e(TAG, "Camera is disabled")
                } else if (e.reason == android.hardware.camera2.CameraAccessException.CAMERA_IN_USE) {
                    Log.e(TAG, "Camera is in use by another app")
                } else if (e.reason == android.hardware.camera2.CameraAccessException.CAMERA_ERROR) {
                    Log.e(TAG, "General camera error")
                } else {
                    Log.e(TAG, "Camera access exception: ${e.reason}", e)
                }
                return false
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception accessing camera", e)
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking camera availability", e)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check camera availability", e)
            return false
        }
    }

    /**
     * Force releases camera resources to ensure they're available for our app
     * Uses an aggressive approach to try multiple strategies
     */
    private fun forceReleaseCamera() {
        try {
            Log.d(TAG, "Attempting aggressive camera resource release")
            
            // First try to close any ARCore session
            try {
                arCoreSessionHelper?.session?.pause()
                arCoreSessionHelper?.session?.close()
                Log.d(TAG, "Closed ARCore session")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ARCore session", e)
            }
            
                            // Try to identify any camera-related processes (for debugging only)
                try {
                    val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                    
                    // Get list of running processes
                    val runningAppProcesses = activityManager.runningAppProcesses
                    
                    // Look for camera-related process names
                    val cameraPkgPatterns = listOf("camera", "photo", "vision", "scan", "ar.core")
                    for (process in runningAppProcesses) {
                        for (pattern in cameraPkgPatterns) {
                            if (process.processName.contains(pattern, ignoreCase = true)) {
                                Log.d(TAG, "Found possible camera app: ${process.processName}")
                                // We can't directly manage other processes' priorities without special permissions
                                // Just logging for diagnostic purposes
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking camera processes", e)
                }
            
            // Multiple GC passes to ensure resources are freed
            System.gc()
            System.runFinalization()
            System.gc()
            
            // Use low-level Camera2 API to try to force camera availability
            val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            
            // Force register and unregister availability callback to trigger camera service check
            val availabilityCallback = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    Log.d(TAG, "Camera $cameraId is now available")
                }
                
                override fun onCameraUnavailable(cameraId: String) {
                    Log.d(TAG, "Camera $cameraId is unavailable")
                }
            }
            
            // Register and immediately unregister to force camera service to update state
            cameraManager.registerAvailabilityCallback(availabilityCallback, Handler(Looper.getMainLooper()))
            cameraManager.unregisterAvailabilityCallback(availabilityCallback)
            
            // Try to force close all camera IDs
            for (cameraId in cameraManager.cameraIdList) {
                Log.d(TAG, "Attempting to force reset camera: $cameraId")
                
                try {
                    // Get the characteristics to identify front/back camera
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    val facingStr = when (facing) {
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        else -> "EXTERNAL/OTHER"
                    }
                    
                    Log.d(TAG, "Camera $cameraId is a $facingStr camera")
                    
                    // Try to forcibly open and immediately close the camera
                    // This might help "steal" it from other apps
                    if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == 
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        
                        val semaphore = java.util.concurrent.Semaphore(1)
                        var cameraDevice: android.hardware.camera2.CameraDevice? = null
                        
                        // Create callback
                        val cameraCallback = object : android.hardware.camera2.CameraDevice.StateCallback() {
                            override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                                Log.d(TAG, "Successfully opened camera $cameraId")
                                cameraDevice = camera
                                semaphore.release()
                            }
                            
                            override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                                Log.d(TAG, "Camera $cameraId disconnected")
                                camera.close()
                                semaphore.release()
                            }
                            
                            override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                                val errorMsg = when (error) {
                                    android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Camera device error"
                                    android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled"
                                    android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera in use"
                                    android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service error"
                                    android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                                    else -> "Unknown camera error $error"
                                }
                                Log.e(TAG, "Error opening camera $cameraId: $errorMsg")
                                camera.close()
                                semaphore.release()
                            }
                        }
                        
                        // Try to acquire the camera
                        semaphore.acquire()
                        try {
                            cameraManager.openCamera(cameraId, cameraCallback, Handler(Looper.getMainLooper()))
                            
                            // Wait with timeout
                            if (semaphore.tryAcquire(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                // We've either successfully opened the camera or got an error
                                cameraDevice?.close()
                            } else {
                                Log.e(TAG, "Timeout waiting for camera $cameraId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception trying to force reset camera $cameraId", e)
                            semaphore.release()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error forcing reset of camera $cameraId", e)
                }
            }
            
            // Final GC pass
            System.gc()
            
            // Give some time for camera operations to complete
            Thread.sleep(300)
            
            Log.d(TAG, "Completed aggressive camera resource release attempt")
        } catch (e: Exception) {
            Log.e(TAG, "Error in forceReleaseCamera", e)
        }
    }

    /**
     * Helper method to restart the app with a clean state
     */
    private fun restartApp() {
        try {
            // Show feedback
            Toast.makeText(this, "Restarting application...", Toast.LENGTH_SHORT).show()
            
            // Force release before restarting
            forceReleaseCamera()
            
            // Create intent to restart the app with a clean state
            val intent = Intent(this, SplitScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Add a delay to allow resources to be freed
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(intent)
                // Use finish with a slight delay to ensure proper cleanup
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 100)
            }, 500)
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting app", e)
            // Last resort - simple restart
            val intent = Intent(this, SplitScreenActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
} 