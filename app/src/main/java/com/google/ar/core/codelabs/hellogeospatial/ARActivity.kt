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

class ARActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ARActivity"
        private const val AR_INITIALIZATION_TIMEOUT = 15000L // 15 seconds
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
                showNavigationHelp()
            }
            
            // Get the surface view
            surfaceView = findViewById(R.id.ar_surface_view)
            
            // Initialize our AR view - create a new instance with this activity
            view = HelloGeoView(this)
            
            // Set the surfaceView in our custom view
            // Need to access the view's surfaceView property to set it
            val field = HelloGeoView::class.java.getDeclaredField("surfaceView")
            field.isAccessible = true
            field.set(view, surfaceView)
            
            // Initialize renderer
            renderer = HelloGeoRenderer(this)
            
            // Set the renderer's view reference
            renderer.setView(view)
            
            // Create and initialize ARCore session
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
            
            // Configure ARCore session
            arCoreSessionHelper.beforeSessionResume = ::configureSession
            
            // Set lifecycle owner for session helper
            arCoreSessionHelper.onLifecycleOwner = this
            
            // Set exception handler
            arCoreSessionHelper.exceptionCallback = { exception ->
                val message = when (exception) {
                    is UnavailableUserDeclinedInstallationException -> "Please install ARCore"
                    is UnavailableApkTooOldException -> "Please update ARCore"
                    is UnavailableSdkTooOldException -> "Please update this app"
                    is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
                    is CameraNotAvailableException -> "Camera is not available"
                    else -> "Failed to initialize AR: $exception"
                }
                Log.e(TAG, "ARCore threw an exception", exception)
                arStatusMessage = message
                
                // Return to map mode with error
                returnToMapMode()
            }
            
            // Register the renderer as a lifecycle observer
            lifecycle.addObserver(renderer)
            
            // Need to call onResume to potentially create the session
            arCoreSessionHelper.onResume()
            
            // Now try to get the session - it might be available after onResume
            val session = arCoreSessionHelper.session

            if (session != null) {
                Log.d(TAG, "Session created successfully")
                // We have a session, set it up in the view and renderer
                view.setupSession(session)
                renderer.setSession(session)
                
                // Set up SampleRender to draw the AR scene
                SampleRender(surfaceView, renderer, assets)
                
                // Set timeout for AR initialization
                startARInitializationTimeout()
                
                // Set navigation state
                if (destinationLatLng != null) {
                    // Start navigation to provided destination
                    setARDestination(destinationLatLng!!)
                }
            } else {
                Log.e(TAG, "Failed to create ARCore session")
                Toast.makeText(this, "Could not initialize AR - please try again", Toast.LENGTH_SHORT).show()
                returnToMapMode()
                return
            }
            
            // Set up ARCore session lifecycle
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showError("Failed to initialize AR: ${e.message}")
        }
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
    
    private fun configureSession(session: Session) {
        try {
            Log.d(TAG, "Configuring AR session with enhanced settings")
            session.configure(
                session.config.apply {
                    // Enable geospatial mode
                    geospatialMode = Config.GeospatialMode.ENABLED
                    
                    // Basic features for navigation
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    
                    // Try adding some additional settings that might help with initialization
                    try {
                        // Check if the device supports depth
                        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                            Log.d(TAG, "Enabled depth mode for better tracking")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking depth support", e)
                    }
                    
                    // Enable cloud anchors for possible network-assisted positioning
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                }
            )
            
            Log.d(TAG, "AR session configured successfully with enhanced settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring session", e)
            Toast.makeText(this, "Error configuring AR: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        
        try {
            // Resume AR session
            arCoreSessionHelper.onResume()
            
            // Resume navigation updates if we were navigating
            if (isNavigating) {
                startNavigationUpdates()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        
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
        Log.d(TAG, "Returning to map mode")
        
        // Clean up any navigation resources
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Return to previous activity
        finish()
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
} 