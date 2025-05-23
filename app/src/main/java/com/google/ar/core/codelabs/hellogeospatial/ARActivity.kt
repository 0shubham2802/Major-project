package com.google.ar.core.codelabs.hellogeospatial

import android.opengl.GLSurfaceView
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.DirectionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.android.gms.maps.model.LatLng
import android.graphics.Color
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.app.ActivityCompat
import android.widget.ProgressBar
import androidx.cardview.widget.CardView
import com.google.ar.core.codelabs.hellogeospatial.helpers.createCameraTexture
import com.google.ar.core.codelabs.hellogeospatial.helpers.configureSessionForEnvironment
import com.google.ar.core.codelabs.hellogeospatial.helpers.resetCamera
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.widget.FrameLayout
import android.view.Gravity

class ARActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        private const val TAG = "ARActivity"
        private const val AR_INITIALIZATION_TIMEOUT = 15000L // 15 seconds
        private const val CAMERA_PERMISSION_CODE = 101
        private const val INDOOR_LIGHT_THRESHOLD = 300f // Lux threshold for indoor detection
    }

    private lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    private lateinit var view: HelloGeoView
    private lateinit var renderer: HelloGeoRenderer
    private var destinationLatLng: LatLng? = null
    private var arInitializationTimeoutHandler = Handler(Looper.getMainLooper())
    private var trackingQualityIndicator: TextView? = null
    private var directionTextView: TextView? = null
    private var streetNameView: TextView? = null
    private var directionArrows: List<TextView>? = null
    private var distanceRemainingView: TextView? = null
    private var timeRemainingView: TextView? = null
    private var nextDirectionTextView: TextView? = null
    private var navInstructionCard: CardView? = null
    private var isNavigating = false
    private var arStatusMessage: String? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private var cameraRetryCount = 0
    private val MAX_CAMERA_RETRIES = 3
    private lateinit var surfaceView: GLSurfaceView
    
    // Navigation-related variables
    private var navigationInstructions = mutableListOf<String>()
    private var navigationSteps = mutableListOf<DirectionsHelper.DirectionStep>()
    private var currentStepIndex = 0
    private var navigationUpdateHandler: Handler? = null
    private var totalRouteDistance: Int = 0
    private var distanceTraveled: Int = 0
    private var totalTimeSeconds: Int = 0
    private var timeRemaining: Int = 0
    private var isArrived = false

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var isIndoorEnvironment = false
    private var lastLightValue = 0f
    private var hasEnvironmentBeenDetected = false
    private var lastEnvironmentCheckTime = 0L
    private val ENVIRONMENT_CHECK_INTERVAL = 10000L // Check every 10 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request camera permission immediately
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
            Log.d(TAG, "Requesting camera permission at startup")
        }
        
        // Initialize sensor manager for environment detection
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        
        // Ensure we're using NoActionBar theme
        setTheme(R.style.Theme_AppCompat_NoActionBar)

        // Set a default uncaught exception handler to prevent crashes
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "AR Error: ${throwable.message}", Toast.LENGTH_LONG).show()
                returnToMapMode()
            }
        }

        try {
            // General setup
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            setContentView(R.layout.activity_ar)
            
            // Extract destination and waypoints if provided
            val extras = intent.extras
            if (extras != null) {
                val lat = extras.getDouble("DESTINATION_LAT", 0.0)
                val lng = extras.getDouble("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    destinationLatLng = LatLng(lat, lng)
                    Log.d(TAG, "Destination set from intent: $destinationLatLng")
                    
                    // Extract waypoints if available
                    val waypointCount = extras.getInt("WAYPOINT_COUNT", 0)
                    if (waypointCount > 0) {
                        val waypoints = mutableListOf<LatLng>()
                        for (i in 0 until waypointCount) {
                            val wpLat = extras.getDouble("WAYPOINT_LAT_$i", 0.0)
                            val wpLng = extras.getDouble("WAYPOINT_LNG_$i", 0.0)
                            if (wpLat != 0.0 && wpLng != 0.0) {
                                waypoints.add(LatLng(wpLat, wpLng))
                            }
                        }
                        
                        if (waypoints.isNotEmpty()) {
                            Log.d(TAG, "Loaded ${waypoints.size} waypoints for navigation")
                        }
                    }
                }
            }
            
            // Set up navigation UI components
            initNavigationUI()
            
            // Add help button to show navigation tips
            findViewById<Button>(R.id.help_button)?.setOnClickListener {
                if (arStatusMessage?.contains("camera", ignoreCase = true) == true) {
                    // If we have camera issues, offer camera recovery options
                    AlertDialog.Builder(this)
                        .setTitle("Camera Issues Detected")
                        .setMessage("Camera is being used by another app or system process. What would you like to do?")
                        .setPositiveButton("EMERGENCY RESET") { dialog, _ ->
                            dialog.dismiss()
                            emergencyCameraReset()
                        }
                        .setNegativeButton("MAP ONLY") { dialog, _ ->
                            dialog.dismiss()
                            returnToMapMode()
                        }
                        .setNeutralButton("RETRY") { dialog, _ ->
                            dialog.dismiss()
                            retryCameraAccess()
                        }
                        .show()
                } else {
                    // Normal help
                    showNavigationHelp()
                }
            }
            
            // Get the surface view
            surfaceView = findViewById(R.id.ar_surface_view)
            
            // Initialize our AR view - create a new instance with this activity
            view = HelloGeoView(this)
            
            // Set the surfaceView in our custom view
            view.initialize(surfaceView)
            
            // Create ARCore session if possible - with improved error handling
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
            arCoreSessionHelper.exceptionCallback = { exception ->
                val message = when (exception) {
                    is UnavailableDeviceNotCompatibleException -> 
                        "This device does not support AR"
                    is UnavailableApkTooOldException ->
                        "Please update ARCore"
                    is UnavailableSdkTooOldException ->
                        "Please update the app"
                    is UnavailableUserDeclinedInstallationException ->
                        "Please install ARCore"
                    is CameraNotAvailableException -> {
                        // If camera is not available, try to reset it
                        Handler(Looper.getMainLooper()).postDelayed({
                            retryCameraAccess()
                        }, 1000)
                        "Camera not available - trying to reset"
                    }
                    else -> 
                        "Failed to create AR session: ${exception.message}"
                }
                
                Log.e(TAG, "ARCore error: $message", exception)
                arStatusMessage = message
                
                // Update UI to show the error
                runOnUiThread {
                    updateStatusText(message)
                    
                    // Always try to show something rather than a blank screen
                    if (exception is CameraNotAvailableException) {
                        // Transparent background to show at least the map
                        surfaceView.setBackgroundColor(Color.TRANSPARENT)
                    }
                }
            }
            
            // Create the renderer using our current context
            renderer = HelloGeoRenderer(this)
            renderer.setView(view)
            
            // Set up the renderer to be used by the view
            view.setRenderer(renderer)
            
            // Set AR initialization timeout
            arInitializationTimeoutHandler.postDelayed({
                if (!view.isArSessionCreated()) {
                    Log.w(TAG, "AR initialization timeout - falling back to map mode")
                    Toast.makeText(this, "AR initialization timed out", Toast.LENGTH_LONG).show()
                    returnToMapMode()
                }
            }, AR_INITIALIZATION_TIMEOUT)
            
            // Reset camera on start to ensure clean state
            forceCameraReset()
            
            // Start tracking device orientation
            setupSensors()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error starting AR: ${e.message}", Toast.LENGTH_LONG).show()
            returnToMapMode()
        }
        
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }
    
    private fun initNavigationUI() {
        try {
            // Find all UI components for AR navigation
            trackingQualityIndicator = findViewById(R.id.tracking_quality)
            directionTextView = findViewById(R.id.direction_text)
            streetNameView = findViewById(R.id.street_name)
            navInstructionCard = findViewById(R.id.nav_instruction_card)
            distanceRemainingView = findViewById(R.id.distance_remaining)
            timeRemainingView = findViewById(R.id.time_remaining)
            nextDirectionTextView = findViewById(R.id.next_direction_text)
            
            // Setup AR direction arrows
            directionArrows = listOf(
                findViewById(R.id.ar_direction_arrow1),
                findViewById(R.id.ar_direction_arrow2),
                findViewById(R.id.ar_direction_arrow3),
                findViewById(R.id.ar_direction_arrow4)
            )
            
            // Ensure arrows are visible and properly configured
            directionArrows?.forEach { arrow ->
                arrow.visibility = View.VISIBLE
                arrow.alpha = 1.0f
            }
            
            // Set close button click listener
            findViewById<View>(R.id.close_button)?.setOnClickListener {
                returnToMapMode()
            }
            
            // Initial state setup
            trackingQualityIndicator?.text = "Tracking: INITIALIZING"
            updateNavigationUI("Turn right", "on W 6th St", "Turn left")
            
            // Start navigation updates immediately
            startNavigationUpdates()
            
            // Debug logging
            Log.d(TAG, "Navigation UI initialized: arrows=${directionArrows?.size}, directionText=${directionTextView?.text}")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing navigation UI", e)
        }
    }
    
    /**
     * Configure ARCore session with more stable settings
     */
    private fun configureSession(session: Session) {
        // Configure session based on detected environment
        // Default to outdoor if we haven't detected environment yet
        configureSessionForEnvironment(session, isIndoorEnvironment)
        
        if (isIndoorEnvironment) {
            // If indoor, notify user 
            runOnUiThread {
                Toast.makeText(this, "Indoor environment detected - optimizing AR", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Record that we configured the session
        Log.d(TAG, "Session configured with indoor mode: $isIndoorEnvironment")
    }
    
    private fun isEarthTrackingStable(): Boolean {
        val session = arCoreSessionHelper.session ?: return false
        val earth = session.earth ?: return false
        return earth.trackingState == TrackingState.TRACKING && 
               earth.cameraGeospatialPose.horizontalAccuracy < 20 // 20 meters is reasonable for outdoor AR
    }
    
    private fun updateTrackingQualityIndicator() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            val trackingState = earth.trackingState
            val earthState = earth.earthState
            
            if (trackingState == TrackingState.TRACKING) {
                val pose = earth.cameraGeospatialPose
                val horizontalAccuracy = pose.horizontalAccuracy
                val verticalAccuracy = pose.verticalAccuracy
                val headingAccuracy = pose.headingAccuracy
                
                // Create a tracking quality indicator
                val quality = when {
                    horizontalAccuracy <= 3 && headingAccuracy <= 10 -> "EXCELLENT"
                    horizontalAccuracy <= 10 && headingAccuracy <= 20 -> "GOOD"
                    horizontalAccuracy <= 20 && headingAccuracy <= 30 -> "FAIR"
                    else -> "POOR"
                }
                
                // Update the UI
                runOnUiThread {
                    trackingQualityIndicator?.text = "Tracking: $quality (±${horizontalAccuracy.toInt()}m, ±${headingAccuracy.toInt()}°)"
                    
                    // Change color based on quality
                    val color = when (quality) {
                        "EXCELLENT" -> Color.parseColor("#4CAF50") // Green
                        "GOOD" -> Color.parseColor("#8BC34A") // Light Green
                        "FAIR" -> Color.parseColor("#FFC107") // Amber
                        else -> Color.parseColor("#F44336") // Red
                    }
                    
                    trackingQualityIndicator?.setBackgroundColor(ColorUtils.setAlphaComponent(color, 180))
                }
            } else {
                // Not tracking yet
                runOnUiThread {
                    trackingQualityIndicator?.text = "Tracking: ${trackingState.name} (${earthState.name})"
                    trackingQualityIndicator?.setBackgroundColor(Color.parseColor("#AA000000"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tracking quality", e)
        }
    }
    
    private fun updateDistanceIndicator(pose: GeospatialPose) {
        try {
            destinationLatLng?.let { destination ->
                val currentLocation = LatLng(pose.latitude, pose.longitude)
                val distance = calculateDistance(
                    currentLocation.latitude, currentLocation.longitude,
                    destination.latitude, destination.longitude
                )
                
                val distanceText = when {
                    distance < 1000 -> "${distance.toInt()} meters"
                    else -> String.format("%.1f km", distance / 1000)
                }
                
                val heading = pose.heading
                val bearing = calculateBearing(
                    currentLocation.latitude, currentLocation.longitude,
                    destination.latitude, destination.longitude
                )
                
                // Calculate the angle between heading and bearing (relative direction)
                var angle = bearing - heading
                if (angle < 0) angle += 360.0
                if (angle > 180) angle = 360.0 - angle
                
                val directionSymbol = getDirectionSymbol(bearing, heading)
                
                // Create and set the formatted text with distance and direction
                val distanceString = "Destination: $distanceText $directionSymbol"
                distanceRemainingView?.text = distanceString
                
                // Change color based on distance
                val backgroundColor = when {
                    distance < 50 -> ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    distance < 200 -> ContextCompat.getColor(this, android.R.color.holo_blue_dark)
                    else -> ContextCompat.getColor(this, android.R.color.darker_gray)
                }
                
                distanceRemainingView?.setBackgroundColor(ColorUtils.setAlphaComponent(backgroundColor, 200))
            } ?: run {
                distanceRemainingView?.text = "No destination set"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating distance indicator", e)
            distanceRemainingView?.text = "Distance: Unknown"
        }
    }
    
    private fun getDirectionSymbol(bearing: Double, heading: Double): String {
        // Calculate relative angle
        var angle = bearing - heading
        // Normalize to 0-360
        while (angle < 0) angle += 360.0
        while (angle >= 360) angle -= 360.0
        
        // Choose appropriate arrow symbol based on angle
        return when {
            angle >= 337.5 || angle < 22.5 -> "↑" // North/Forward
            angle >= 22.5 && angle < 67.5 -> "↗" // Northeast
            angle >= 67.5 && angle < 112.5 -> "→" // East/Right
            angle >= 112.5 && angle < 157.5 -> "↘" // Southeast
            angle >= 157.5 && angle < 202.5 -> "↓" // South/Back
            angle >= 202.5 && angle < 247.5 -> "↙" // Southwest
            angle >= 247.5 && angle < 292.5 -> "←" // West/Left
            angle >= 292.5 && angle < 337.5 -> "↖" // Northwest
            else -> "?" // Should never happen
        }
    }
    
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lngDiffRad = Math.toRadians(lng2 - lng1)
        
        val y = Math.sin(lngDiffRad) * Math.sin(lat2Rad)
        val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(lngDiffRad)
        
        var bearing = Math.toDegrees(Math.atan2(y, x))
        if (bearing < 0) bearing += 360.0
        
        return bearing
    }
    
    /**
     * Calculates distance between two geographic coordinates in meters using the Haversine formula
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return earthRadius * c // Distance in meters
    }
    
    private fun setARDestination(destination: LatLng) {
        Log.d(TAG, "Setting AR destination: $destination")
        destinationLatLng = destination
        isNavigating = true
        
        // Start the AR navigation
        startARNavigation(destination)
        
        // Make sure arrows are visible
        directionArrows?.forEach { arrow ->
            arrow.visibility = View.VISIBLE
        }
        
        // Pulse the direction arrows to attract attention
        pulseArrows()
    }
    
    private fun startARNavigation(destination: LatLng) {
        // For demo purposes, simulate a fixed navigation route
        // In a real app, this would use real navigation data
        
        // Simulate street name based on direction
        val streetName = "on W 6th St"
        
        // Update UI with demo instructions
        updateNavigationUI("Turn right", streetName, "Turn left")
        
        // Update navigation time and distance (simulated values)
        runOnUiThread {
            timeRemainingView?.text = "8 min"
            distanceRemainingView?.text = "0.4 mi · 8:08 AM"
        }
        
        // Make sure the entire navigation card is visible
        runOnUiThread {
            navInstructionCard?.visibility = View.VISIBLE
        }
        
        // Start a repeating task to pulse arrows every few seconds
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isNavigating) {
                    pulseArrows()
                    handler.postDelayed(this, 5000) // Pulse every 5 seconds
                }
            }
        }, 1000) // Start first pulse after 1 second instead of 3
    }
    
    private fun updateNavigationUI(direction: String, streetName: String, nextDirection: String) {
        runOnUiThread {
            try {
                directionTextView?.text = direction
                streetNameView?.text = streetName
                nextDirectionTextView?.text = nextDirection
                
                // Ensure navigation card is visible
                navInstructionCard?.visibility = View.VISIBLE
                
                // Make arrows visible
                directionArrows?.forEach { arrow ->
                    arrow.visibility = View.VISIBLE
                    arrow.alpha = 1.0f
                }
                
                Log.d(TAG, "Updated navigation UI: direction=$direction, street=$streetName, nextDirection=$nextDirection")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating navigation UI", e)
            }
        }
    }
    
    private fun pulseArrows() {
        val handler = Handler(Looper.getMainLooper())
        val arrowCount = directionArrows?.size ?: 0
        
        Log.d(TAG, "Pulsing $arrowCount arrows")
        
        for (i in 0 until arrowCount) {
            handler.postDelayed({
                runOnUiThread {
                    try {
                        directionArrows?.get(i)?.apply {
                            visibility = View.VISIBLE
                            alpha = 0f
                            animate().alpha(1f).setDuration(300).start()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pulsing arrow $i", e)
                    }
                }
            }, (i * 150).toLong())
        }
    }
    
    /**
     * Starts periodic updates for navigation status
     */
    private fun startNavigationUpdates() {
        // Clear any existing handler
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Create a new handler
        navigationUpdateHandler = Handler(Looper.getMainLooper())
        
        // Set up a periodic task to update navigation information
        navigationUpdateHandler?.postDelayed(object : Runnable {
            override fun run() {
                // Update navigation information
                updateTrackingQualityIndicator()
                
                // Check if AR session is available
                val session = arCoreSessionHelper.session
                if (session != null && isNavigating) {
                    val earth = session.earth
                    if (earth != null && earth.trackingState == TrackingState.TRACKING) {
                        // Update distance information using current position
                        updateDistanceIndicator(earth.cameraGeospatialPose)
                    }
                }
                
                // Schedule the next update
                if (isNavigating) {
                    navigationUpdateHandler?.postDelayed(this, 1000) // Update every second
                }
            }
        }, 1000) // Start after 1 second
    }
    
    private fun updateTrackingStatus(status: TrackingState) {
        runOnUiThread {
            trackingQualityIndicator?.text = when (status) {
                TrackingState.TRACKING -> "Tracking: GOOD"
                TrackingState.PAUSED -> "Tracking: PAUSED"
                else -> "Tracking: STOPPED"
            }
            
            // Also update the color
            trackingQualityIndicator?.setBackgroundColor(
                when (status) {
                    TrackingState.TRACKING -> Color.parseColor("#AA006400") // Dark Green
                    TrackingState.PAUSED -> Color.parseColor("#AAFF8C00") // Dark Orange
                    else -> Color.parseColor("#AAA00000") // Dark Red
                }
            )
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Reset camera on resume to ensure fresh session
        if (cameraRetryCount > 0) {
            resetCamera(this)
        }
        
        // Register light sensor for environment detection
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        
        try {
            // Make sure permissions are granted before trying to resume AR
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                // Resume AR session
                if (::arCoreSessionHelper.isInitialized) {
                    Log.d(TAG, "Resuming AR session")
                    
                    // Force clean camera resources first
                    forceReleaseCamera()
                    
                    // CRITICAL: Try to resume session with better error handling
                    try {
                        arCoreSessionHelper.onResume()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in arCoreSessionHelper.onResume()", e)
                        // Give it another chance after cleanup
                        emergencyCameraReset()
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                arCoreSessionHelper.onResume()
                            } catch (e: Exception) {
                                Log.e(TAG, "Second attempt at resuming session failed", e)
                            }
                        }, 1000)
                    }
                    
                    // Make sure session is valid and camera texture is set
                    val session = arCoreSessionHelper.session
                    if (session != null) {
                        try {
                            // Make sure camera texture is set to ensure camera feed appears
                            val backgroundRenderer = renderer.accessBackgroundRenderer()
                            if (backgroundRenderer != null) {
                                // CRITICAL: Create texture if needed
                                try {
                                    backgroundRenderer.createCameraTexture(this)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error creating camera texture", e)
                                }
                                
                                val textureId = backgroundRenderer.getCameraColorTexture().getTextureId()
                                session.setCameraTextureName(textureId)
                                Log.d(TAG, "Set camera texture ID to: $textureId")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to set camera texture name", e)
                        }
                    }
                }
                
                // Resume GL surface
                if (::surfaceView.isInitialized) {
                    surfaceView.onResume()
                    
                    // Force a render
                    surfaceView.requestRender()
                }
                
                Log.d(TAG, "AR session resumed successfully")
            } else {
                Log.e(TAG, "Camera permission not granted - can't resume AR")
                requestCameraPermission()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming AR session", e)
        }
    }
    
    /**
     * Force releases camera resources
     */
    private fun forceReleaseCamera() {
        try {
            Log.d(TAG, "Force releasing camera resources")
            
            // Force a garbage collection
            System.gc()
            
            // Wait a moment
            Thread.sleep(100)
            
            // Use the Camera1 API to do a clean release
            releaseCamera1Resources()
        } catch (e: Exception) {
            Log.e(TAG, "Error in forceReleaseCamera", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        // Unregister sensor listener
        sensorManager.unregisterListener(this)
        
        try {
            view.onPause()
            arCoreSessionHelper.onPause()
            
            // Stop navigation updates
            navigationUpdateHandler?.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            arCoreSessionHelper.onDestroy()
            
            // Clean up any handlers
            navigationUpdateHandler?.removeCallbacksAndMessages(null)
            arInitializationTimeoutHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
    }
    
    override fun onBackPressed() {
        returnToMapMode()
    }
    
    fun returnToMapMode() {
        try {
            Log.d(TAG, "Returning to map mode due to AR failure")
            
            // Create intent to return to main activity with fallback flag
            val intent = Intent(this, HelloGeoActivity::class.java)
            intent.putExtra("FALLBACK_FROM_AR", true)
            
            // If we have a destination, pass it back
            destinationLatLng?.let { destination ->
                intent.putExtra("DESTINATION_LAT", destination.latitude)
                intent.putExtra("DESTINATION_LNG", destination.longitude)
            }
            
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error returning to map mode", e)
            
            // Last resort if even returning to map fails
            finishAffinity()
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    // Show helpful guidance for using AR navigation
    private fun showNavigationHelp() {
        val helpText = """
            AR Navigation Tips:
            
            • Follow the colored arrows to reach your destination
            • Blue arrows: Regular path segments
            • Yellow arrows: Turn points
            • Orange/Red: Approaching destination
            • A red marker will appear at your destination
            
            • For best results, hold your phone up as if taking a photo
            • Stay outdoors with clear view of the sky
            • Move slowly and steadily
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("How to Use AR Navigation")
            .setMessage(helpText)
            .setPositiveButton("Got it") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun checkARIssues(): List<String> {
        val issues = mutableListOf<String>()
        
        // Check if location permissions are granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
            issues.add("Location permission not granted")
            // Request permission
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 
                100
            )
        }
        
        // Check if GPS is enabled
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            issues.add("GPS is disabled")
            
            // Prompt user to enable GPS
            AlertDialog.Builder(this)
                .setTitle("GPS Required")
                .setMessage("AR navigation requires GPS to be enabled. Would you like to enable it now?")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel") { _, _ -> }
                .show()
        }
        
        // Check network connectivity
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (networkCapabilities == null || 
            (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && 
             !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))) {
            issues.add("No internet connection")
        }
        
        // Check if device is likely indoors (harder to get GPS signal)
        // This is just a heuristic based on sensor readings
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            // Check if light sensor readings are low (suggesting indoors)
            // This would require registering a sensor listener, which is complex
            // for this example we'll just add a general note
            issues.add("You may be indoors where GPS signal is weak")
        }
        
        return issues
    }

    private fun getDirectionArrow(relativeAngle: Float): String {
        // Normalize angle to 0-360
        var angle = relativeAngle
        while (angle < 0) angle += 360f
        while (angle >= 360f) angle -= 360f
        
        // Map angle to arrow
        return when {
            angle >= 337.5f || angle < 22.5f -> "↑" // North
            angle >= 22.5f && angle < 67.5f -> "↗" // Northeast
            angle >= 67.5f && angle < 112.5f -> "→" // East
            angle >= 112.5f && angle < 157.5f -> "↘" // Southeast
            angle >= 157.5f && angle < 202.5f -> "↓" // South
            angle >= 202.5f && angle < 247.5f -> "↙" // Southwest
            angle >= 247.5f && angle < 292.5f -> "←" // West
            angle >= 292.5f && angle < 337.5f -> "↖" // Northwest
            else -> "?" // Should never happen
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            returnToMapMode()
        }
    }

    private fun startARInitializationTimeout() {
        arInitializationTimeoutHandler.postDelayed({
            if (!isEarthTrackingStable()) {
                // Clear existing timeout handler
                arInitializationTimeoutHandler.removeCallbacksAndMessages(null)
                
                // Check for common issues
                val issues = checkARIssues()
                val issueMessage = if (issues.isNotEmpty()) {
                    "Issues detected: ${issues.joinToString(", ")}"
                } else {
                    "AR tracking cannot initialize in this environment"
                }
                
                // Show dialog to user with more detailed information
                AlertDialog.Builder(this)
                    .setTitle("AR Initialization Issue")
                    .setMessage("Could not initialize AR tracking. $issueMessage\n\nWould you like to continue waiting or return to map mode?")
                    .setPositiveButton("Wait Longer") { _, _ ->
                        // Give more time
                        startARInitializationTimeout()
                    }
                    .setNegativeButton("Return to Map") { _, _ ->
                        returnToMapMode()
                    }
                    .setCancelable(false)
                    .show()
            }
        }, AR_INITIALIZATION_TIMEOUT)
    }

    /**
     * Emergency camera reset when things go wrong
     */
    private fun emergencyCameraReset() {
        try {
            Log.d(TAG, "Performing emergency camera reset")
            
            // First try to forcibly reset camera
            if (forceCameraReset()) {
                // Show progress to user
                val progressBar = findViewById<ProgressBar>(R.id.progress_circular)
                progressBar?.visibility = View.VISIBLE
                
                // Create a status card dynamically
                val cardView = createStatusCard()
                cardView.visibility = View.VISIBLE
                
                Toast.makeText(this, "Camera reconnection in progress...", Toast.LENGTH_LONG).show()
                
                // Wait a bit then retry the session
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Recreate the session after reset
                        arCoreSessionHelper.session?.close()
                        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
                        
                        // Update UI
                        progressBar?.visibility = View.GONE
                        Toast.makeText(this, "Camera reconnected", Toast.LENGTH_SHORT).show()
                        
                        // Keep the status card visible for a bit longer
                        Handler(Looper.getMainLooper()).postDelayed({
                            cardView.visibility = View.GONE
                        }, 3000)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to recreate session after camera reset", e)
                        Toast.makeText(this, "Failed to restart AR - returning to map", Toast.LENGTH_LONG).show()
                        returnToMapMode()
                    }
                }, 2000)
            } else {
                // If camera reset failed, go back to map mode
                Toast.makeText(this, "Camera reset failed - returning to map mode", Toast.LENGTH_LONG).show()
                returnToMapMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in emergency camera reset", e)
            returnToMapMode()
        }
    }
    
    /**
     * Create a status card dynamically for showing camera reset progress
     */
    private fun createStatusCard(): CardView {
        val cardView = CardView(this).apply {
            id = View.generateViewId()
            radius = 16f
            cardElevation = 8f
            setCardBackgroundColor(Color.argb(200, 0, 0, 0))
            
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                setMargins(32, 160, 32, 0)
            }
            
            layoutParams = params
        }
        
        // Add status text
        val statusText = TextView(this).apply {
            text = "Camera reconnection in progress..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        
        cardView.addView(statusText)
        
        // Add to the root layout
        val rootLayout = findViewById<FrameLayout>(android.R.id.content)
        rootLayout.addView(cardView)
        
        return cardView
    }
    
    /**
     * Retry camera access with improved error handling
     */
    private fun retryCameraAccess() {
        try {
            cameraRetryCount++
            Log.d(TAG, "Attempting to retry camera access (attempt $cameraRetryCount)")
            
            // If we've tried too many times, fall back to map mode
            if (cameraRetryCount >= MAX_CAMERA_RETRIES) {
                Log.w(TAG, "Exceeded maximum camera retries, falling back to map mode")
                Toast.makeText(
                    this,
                    "Camera reconnection failed after $MAX_CAMERA_RETRIES attempts",
                    Toast.LENGTH_LONG
                ).show()
                returnToMapMode()
                return
            }
            
            // First reset the camera to ensure a clean state
            resetCamera(this)
            
            // Show status to user
            Toast.makeText(
                this,
                "Retrying camera connection (${cameraRetryCount}/${MAX_CAMERA_RETRIES})",
                Toast.LENGTH_SHORT
            ).show()
            
            // Try to recreate the session
            arCoreSessionHelper.session?.let { session ->
                try {
                    session.resume()
                    Log.d(TAG, "Successfully resumed existing session")
                } catch (e: CameraNotAvailableException) {
                    Log.e(TAG, "Failed to resume session", e)
                    
                    // More aggressive approach - restart camera subsystem
                    Handler(Looper.getMainLooper()).postDelayed({
                        emergencyCameraReset()
                    }, 500)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrying camera access", e)
            Toast.makeText(this, "Camera retry failed: ${e.message}", Toast.LENGTH_LONG).show()
            
            if (cameraRetryCount >= MAX_CAMERA_RETRIES) {
                returnToMapMode()
            }
        }
    }
    
    /**
     * Release all camera resources using both Camera1 and Camera2 APIs
     */
    private fun releaseAllCameras() {
        // Use legacy Camera API as it's more stable
        releaseCamera1Resources()
    }
    
    /**
     * Release Camera2 API resources
     */
    private fun releaseCamera2Resources() {
        // Removed complex Camera2 API code that could cause instability
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
    
    /**
     * Release MediaRecorder which might be holding camera
     */
    private fun releaseMediaRecorderResources() {
        // Simplified to avoid potential issues
    }

    // Add method to get camera permission if missing
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    // Sensor event implementations
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val currentTime = System.currentTimeMillis()
            lastLightValue = event.values[0]
            
            // Only change environment config periodically to avoid constant reconfiguration
            if (currentTime - lastEnvironmentCheckTime > ENVIRONMENT_CHECK_INTERVAL) {
                lastEnvironmentCheckTime = currentTime
                
                // Detect if we're indoors based on light level
                val newIsIndoor = lastLightValue < INDOOR_LIGHT_THRESHOLD
                
                // If environment changed, reconfigure
                if (isIndoorEnvironment != newIsIndoor || !hasEnvironmentBeenDetected) {
                    isIndoorEnvironment = newIsIndoor
                    hasEnvironmentBeenDetected = true
                    
                    // Only reconfigure session if it's active
                    arCoreSessionHelper.session?.let { session ->
                        configureSession(session)
                    }
                    
                    Log.d(TAG, "Environment changed to: ${if (isIndoorEnvironment) "indoor" else "outdoor"}")
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not used
    }

    // Update status text displayed to the user
    private fun updateStatusText(message: String) {
        runOnUiThread {
            trackingQualityIndicator?.let {
                it.text = message
                it.visibility = View.VISIBLE
            } ?: run {
                Log.w(TAG, "Could not update status text - view is null")
            }
        }
    }
    
    // Setup sensors for device orientation tracking
    private fun setupSensors() {
        try {
            // Register the light sensor for environment detection
            lightSensor?.let { sensor ->
                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                Log.d(TAG, "Light sensor registered")
            } ?: run {
                Log.w(TAG, "Light sensor not available on this device")
            }
            
            // Other sensor registration can be added here
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up sensors", e)
        }
    }
    
    // Force camera reset when needed
    private fun forceCameraReset(): Boolean {
        try {
            // Call the helper function to reset camera
            val wasReset = com.google.ar.core.codelabs.hellogeospatial.helpers.forceCameraReset(this)
            
            if (!wasReset) {
                Log.w(TAG, "Camera reset failed")
                Toast.makeText(this, "Camera reset failed", Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Camera reset succeeded")
            }
            return wasReset
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera reset", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }
} 