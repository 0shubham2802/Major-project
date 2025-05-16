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
import android.widget.CardView

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
    private var directionArrowView: TextView? = null
    private var distanceRemainingView: TextView? = null
    private var timeRemainingView: TextView? = null
    private var totalDistanceView: TextView? = null
    private var navigationProgressBar: ProgressBar? = null
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
            
            // Extract destination if provided
            val extras = intent.extras
            if (extras != null) {
                val lat = extras.getDouble("DESTINATION_LAT", 0.0)
                val lng = extras.getDouble("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    destinationLatLng = LatLng(lat, lng)
                    Log.d(TAG, "Destination set from intent: $destinationLatLng")
                }
            }
            
            // Set up navigation UI components
            initNavigationUI()
            
            // Set return button listener
            findViewById<Button>(R.id.return_to_map_button)?.setOnClickListener {
                returnToMapMode()
            }
            
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
            // Find all UI components from the included layout
            val navigationOverlay = findViewById<View>(R.id.navigation_overlay)
            
            trackingQualityIndicator = navigationOverlay.findViewById(R.id.tracking_quality)
            directionTextView = navigationOverlay.findViewById(R.id.direction_text)
            directionArrowView = navigationOverlay.findViewById(R.id.direction_arrow)
            distanceRemainingView = navigationOverlay.findViewById(R.id.distance_remaining)
            timeRemainingView = navigationOverlay.findViewById(R.id.time_remaining)
            totalDistanceView = navigationOverlay.findViewById(R.id.total_distance)
            navigationProgressBar = navigationOverlay.findViewById(R.id.navigation_progress)
            navInstructionCard = navigationOverlay.findViewById(R.id.nav_instruction_card)
            
            // Initially hide the navigation card until navigation starts
            navInstructionCard?.visibility = View.GONE
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing navigation UI", e)
            Toast.makeText(this, "Error setting up UI: ${e.message}", Toast.LENGTH_SHORT).show()
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
    
    private fun setARDestination(destination: LatLng) {
        try {
            isNavigating = true
            
            // Show loading state
            directionTextView?.text = "Preparing navigation..."
            directionArrowView?.text = "↑"
            distanceRemainingView?.text = "..."
            timeRemainingView?.text = "Calculating..."
            navInstructionCard?.visibility = View.VISIBLE
            
            // Wait for Earth to start tracking
            val earthTrackingHandler = Handler(Looper.getMainLooper())
            val earthTrackingRunnable = object : Runnable {
                override fun run() {
                    val session = arCoreSessionHelper.session
                    val earth = session?.earth
                    
                    if (earth?.trackingState == TrackingState.TRACKING) {
                        // Earth is tracking, create anchor
                        val cameraGeospatialPose = earth.cameraGeospatialPose
                        val currentLatLng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
                        
                        // Use DirectionsHelper to get real directions instead of just a direct line
                        val directionsHelper = DirectionsHelper(this@ARActivity)
                        
                        // Show loading indicator
                        runOnUiThread {
                            trackingQualityIndicator?.text = "Getting directions..."
                        }
                        
                        directionsHelper.getDirectionsWithInstructions(
                            currentLatLng, 
                            destination,
                            object : DirectionsHelper.DirectionsWithInstructionsListener {
                                override fun onDirectionsReady(
                                    pathPoints: List<LatLng>,
                                    instructions: List<String>,
                                    steps: List<DirectionsHelper.DirectionStep>
                                ) {
                                    // Create anchors for the real path
                                    renderer.createPathAnchors(pathPoints)
                                    
                                    // Store navigation data
                                    navigationInstructions.clear()
                                    navigationInstructions.addAll(instructions)
                                    navigationSteps.clear()
                                    navigationSteps.addAll(steps)
                                    currentStepIndex = 0
                                    
                                    // Calculate total distance and time
                                    totalRouteDistance = steps.sumBy { it.distance }
                                    totalTimeSeconds = (totalRouteDistance / 1.4).toInt() // Estimate walking speed at 1.4 m/s
                                    timeRemaining = totalTimeSeconds
                                    
                                    // Display the navigation information
                                    updateNavigationUI(0, totalRouteDistance, totalTimeSeconds, 0)
                                    
                                    // Make the navigation card visible
                                    runOnUiThread {
                                        navInstructionCard?.visibility = View.VISIBLE
                                        Toast.makeText(this@ARActivity, "AR navigation started with turn-by-turn directions", Toast.LENGTH_SHORT).show()
                                        
                                        // Start navigation updates
                                        startNavigationUpdates()
                                    }
                                }
                                
                                override fun onDirectionsError(errorMessage: String) {
                                    Log.e(TAG, "Directions error: $errorMessage")
                                    
                                    // Fall back to direct path
                                    val simplePath = listOf(currentLatLng, destination)
                                    renderer.createPathAnchors(simplePath)
                                    
                                    // Calculate direct distance
                                    val directDistance = calculateDistance(
                                        currentLatLng.latitude, currentLatLng.longitude,
                                        destination.latitude, destination.longitude
                                    ).toInt()
                                    
                                    // Create simple instruction
                                    navigationInstructions.clear()
                                    navigationInstructions.add("Follow the direct path to destination")
                                    
                                    // Create a single step for the direct path
                                    navigationSteps.clear()
                                    navigationSteps.add(
                                        DirectionsHelper.DirectionStep(
                                            currentLatLng,
                                            destination,
                                            "Follow the direct path",
                                            directDistance,
                                            simplePath
                                        )
                                    )
                                    
                                    // Set navigation metrics
                                    totalRouteDistance = directDistance
                                    totalTimeSeconds = (directDistance / 1.4).toInt() // Walking speed ~1.4 m/s
                                    timeRemaining = totalTimeSeconds
                                    
                                    // Update UI
                                    updateNavigationUI(0, totalRouteDistance, totalTimeSeconds, 0)
                                    
                                    runOnUiThread {
                                        Toast.makeText(this@ARActivity, "Using direct route: $errorMessage", Toast.LENGTH_SHORT).show()
                                        navInstructionCard?.visibility = View.VISIBLE
                                        
                                        // Start navigation updates
                                        startNavigationUpdates()
                                    }
                                }
                            }
                        )
                    } else {
                        // Not tracking yet, check again in a second
                        earthTrackingHandler.postDelayed(this, 1000)
                    }
                }
            }
            
            // Start checking for Earth tracking
            earthTrackingHandler.post(earthTrackingRunnable)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error setting AR destination", e)
            Toast.makeText(this, "Error starting AR navigation: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateNavigationUI(stepDistance: Int, totalDistance: Int, totalTimeSeconds: Int, distanceTraveled: Int) {
        runOnUiThread {
            // Calculate remaining values
            val distanceRemaining = totalDistance - distanceTraveled
            
            // Format distance
            val distanceText = when {
                distanceRemaining >= 1000 -> String.format("%.1f km", distanceRemaining / 1000.0)
                else -> "$distanceRemaining m"
            }
            
            // Calculate remaining time
            val remainingTimeSeconds = (totalTimeSeconds * (distanceRemaining.toFloat() / totalDistance.toFloat())).toInt()
            val timeText = when {
                remainingTimeSeconds >= 3600 -> String.format("%d hr %d min", remainingTimeSeconds / 3600, (remainingTimeSeconds % 3600) / 60)
                remainingTimeSeconds >= 60 -> String.format("%d min", remainingTimeSeconds / 60)
                else -> "$remainingTimeSeconds sec"
            }
            
            // Format total distance
            val totalDistanceText = when {
                totalDistance >= 1000 -> String.format("%.1f km", totalDistance / 1000.0)
                else -> "$totalDistance m"
            }
            
            // Calculate progress percentage
            val progressPercent = ((distanceTraveled.toFloat() / totalDistance.toFloat()) * 100).toInt()
            
            // Update UI components
            distanceRemainingView?.text = distanceText
            timeRemainingView?.text = timeText
            totalDistanceView?.text = totalDistanceText
            navigationProgressBar?.progress = progressPercent
            
            // Get the next instruction
            if (navigationInstructions.isNotEmpty() && currentStepIndex < navigationInstructions.size) {
                directionTextView?.text = navigationInstructions[currentStepIndex]
                
                // Calculate and set direction arrow
                if (currentStepIndex < navigationSteps.size && navigationSteps.isNotEmpty()) {
                    updateDirectionArrow()
                }
            }
        }
    }
    
    private fun updateDirectionArrow() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            if (earth.trackingState != TrackingState.TRACKING) return
            if (navigationSteps.isEmpty() || currentStepIndex >= navigationSteps.size) return
            
            // Get current position and orientation
            val cameraGeospatialPose = earth.cameraGeospatialPose
            val currentHeading = cameraGeospatialPose.heading
            val currentLatLng = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
            
            // Get current step
            val currentStep = navigationSteps[currentStepIndex]
            
            // Calculate bearing to next point
            val targetLatLng = if (currentStepIndex < navigationSteps.size - 1) {
                navigationSteps[currentStepIndex + 1].startLocation
            } else {
                currentStep.endLocation
            }
            
            val bearingToTarget = calculateBearing(
                currentLatLng.latitude, currentLatLng.longitude,
                targetLatLng.latitude, targetLatLng.longitude
            )
            
            // Calculate relative angle (between heading and bearing)
            var relativeAngle = bearingToTarget - currentHeading
            // Normalize to -180 to 180
            if (relativeAngle > 180) relativeAngle -= 360
            if (relativeAngle < -180) relativeAngle += 360
            
            // Select appropriate arrow direction
            val directionArrow = getDirectionArrow(relativeAngle.toFloat())
            
            // Update the UI
            runOnUiThread {
                directionArrowView?.text = directionArrow
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating direction arrow", e)
        }
    }
    
    private fun startNavigationUpdates() {
        // Cancel any existing handler
        navigationUpdateHandler?.removeCallbacksAndMessages(null)
        
        // Create new handler
        navigationUpdateHandler = Handler(Looper.getMainLooper())
        
        // Create runnable that updates the current instruction based on user's location
        val navigationUpdateRunnable = object : Runnable {
            override fun run() {
                updateNavigationInstruction()
                updateTrackingQualityIndicator()
                navigationUpdateHandler?.postDelayed(this, 1000) // Update every second for smoother updates
            }
        }
        
        // Start the updates
        navigationUpdateHandler?.post(navigationUpdateRunnable)
    }
    
    private fun updateNavigationInstruction() {
        try {
            val session = arCoreSessionHelper.session ?: return
            val earth = session.earth ?: return
            
            if (earth.trackingState != TrackingState.TRACKING) return
            if (navigationSteps.isEmpty()) return
            
            // Get current position
            val pose = earth.cameraGeospatialPose
            val currentLatLng = LatLng(pose.latitude, pose.longitude)
            
            // Find the closest step
            var closestStepIndex = 0
            var minDistance = Double.MAX_VALUE
            var distanceTraveled = 0
            
            for (i in navigationSteps.indices) {
                val step = navigationSteps[i]
                val stepStart = step.startLocation
                
                // Calculate distance to step start
                val distance = calculateDistance(
                    currentLatLng.latitude, currentLatLng.longitude,
                    stepStart.latitude, stepStart.longitude
                )
                
                if (distance < minDistance) {
                    minDistance = distance
                    closestStepIndex = i
                }
                
                // Add up distances of completed steps
                if (i < closestStepIndex) {
                    distanceTraveled += step.distance
                }
            }
            
            // If we're on a step, calculate how far along we are
            if (closestStepIndex < navigationSteps.size) {
                val currentStep = navigationSteps[closestStepIndex]
                val stepStart = currentStep.startLocation
                val stepEnd = currentStep.endLocation
                
                // Calculate total step distance
                val totalStepDistance = calculateDistance(
                    stepStart.latitude, stepStart.longitude,
                    stepEnd.latitude, stepEnd.longitude
                )
                
                // Calculate distance from start of step
                val distanceFromStart = calculateDistance(
                    currentLatLng.latitude, currentLatLng.longitude,
                    stepStart.latitude, stepStart.longitude
                )
                
                // Calculate distance from end of step
                val distanceFromEnd = calculateDistance(
                    currentLatLng.latitude, currentLatLng.longitude,
                    stepEnd.latitude, stepEnd.longitude
                )
                
                // If we're closer to the end than the start, and the triangle inequality roughly holds,
                // we've made progress along the step
                if (distanceFromEnd < distanceFromStart && 
                    (distanceFromStart + distanceFromEnd < totalStepDistance * 1.3)) { // Allow some wiggle room
                    // Calculate how far along the step we are (as a percentage)
                    val stepProgress = 1.0 - (distanceFromEnd / totalStepDistance)
                    // Add the appropriate portion of the current step's distance
                    distanceTraveled += (currentStep.distance * stepProgress).toInt()
                }
            }
            
            // If we've moved to a new step, update the instruction
            if (closestStepIndex != currentStepIndex && closestStepIndex < navigationInstructions.size) {
                currentStepIndex = closestStepIndex
            }
            
            // Update navigation UI with current status
            this.distanceTraveled = distanceTraveled
            updateNavigationUI(0, totalRouteDistance, totalTimeSeconds, distanceTraveled)
            
            // If we're close to destination, show arrival message
            val destination = destinationLatLng ?: return
            val distanceToDestination = calculateDistance(
                currentLatLng.latitude, currentLatLng.longitude,
                destination.latitude, destination.longitude
            )
            
            if (distanceToDestination < 20) { // Within 20 meters
                runOnUiThread {
                    directionTextView?.text = "You have arrived at your destination"
                    navInstructionCard?.setCardBackgroundColor(Color.parseColor("#AA006400")) // Dark green
                    
                    // Show toast only once when arriving
                    if (!isArrived) {
                        Toast.makeText(this@ARActivity, "You have arrived at your destination!", Toast.LENGTH_LONG).show()
                        isArrived = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating navigation", e)
        }
    }
    
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
        try {
            // If we had any destination, pass it back
            val resultIntent = Intent()
            destinationLatLng?.let { dest ->
                resultIntent.putExtra("DESTINATION_LAT", dest.latitude)
                resultIntent.putExtra("DESTINATION_LNG", dest.longitude)
            }
            
            // Pass back error message if there was one
            arStatusMessage?.let {
                resultIntent.putExtra("AR_ERROR", it)
            }
            
            setResult(RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error returning to map mode", e)
            setResult(RESULT_CANCELED)
            finish()
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
} 