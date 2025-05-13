package com.google.ar.core.codelabs.hellogeospatial

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
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
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
import com.google.android.gms.maps.model.LatLng

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
    private var isNavigating = false
    private var arStatusMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set a default uncaught exception handler to prevent crashes
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "AR Error: ${throwable.message}", Toast.LENGTH_LONG).show()
                returnToMapMode()
            }
        }

        try {
            // Get destination from intent if available
            if (intent.hasExtra("DESTINATION_LAT") && intent.hasExtra("DESTINATION_LNG")) {
                val lat = intent.getDoubleExtra("DESTINATION_LAT", 0.0)
                val lng = intent.getDoubleExtra("DESTINATION_LNG", 0.0)
                
                if (lat != 0.0 && lng != 0.0) {
                    destinationLatLng = LatLng(lat, lng)
                }
            }

            // Create and initialize ARCore session
            arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
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

            // Set up the AR view
            setContentView(R.layout.activity_ar)
            
            // Add tracking quality indicator
            trackingQualityIndicator = findViewById(R.id.tracking_quality)
            
            // Add button to return to map view
            findViewById<Button>(R.id.return_to_map_button).setOnClickListener {
                returnToMapMode()
            }
            
            // Initialize AR rendering
            view = findViewById(R.id.ar_view)
            renderer = HelloGeoRenderer(this)
            
            // Set AR session lifecycle
            arCoreSessionHelper.beforeSessionResume = ::configureSession
            arCoreSessionHelper.onSessionCreated = { session: Session ->
                // Set up the renderer with the session
                renderer.setSession(session)
                view.setupSession(session)
            }
            
            // Setup renderer with SampleRender
            SampleRender(view.surfaceView, renderer, assets)
            
            // Set timeout for AR initialization
            startARInitializationTimeout()
            
            // Attempt to set up AR destination if available
            destinationLatLng?.let { destination ->
                setARDestination(destination)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AR", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            returnToMapMode()
        }
    }
    
    private fun startARInitializationTimeout() {
        arInitializationTimeoutHandler.postDelayed({
            if (!isEarthTrackingStable()) {
                // Show dialog to user
                AlertDialog.Builder(this)
                    .setTitle("AR Initialization Issue")
                    .setMessage("Could not initialize AR tracking. Would you like to continue waiting or return to map mode?")
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
    
    private fun isEarthTrackingStable(): Boolean {
        try {
            val session = arCoreSessionHelper.session ?: return false
            val earth = session.earth
            return earth != null && earth.trackingState == TrackingState.TRACKING
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Earth tracking", e)
            return false
        }
    }
    
    private fun configureSession(session: Session) {
        session.configure(
            session.config.apply {
                // Enable geospatial mode
                geospatialMode = Config.GeospatialMode.ENABLED
                
                // Basic features for navigation
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
        )
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
            trackingQualityIndicator?.visibility = View.VISIBLE
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tracking quality", e)
            trackingQualityIndicator?.text = "Tracking: ERROR"
            trackingQualityIndicator?.setBackgroundResource(android.R.color.holo_red_light)
        }
    }
    
    private fun setARDestination(destination: LatLng) {
        try {
            isNavigating = true
            
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
                        
                        // Create path from current location to destination
                        val path = listOf(currentLatLng, destination)
                        
                        // Create anchors for path using renderer
                        renderer.createPathAnchors(path)
                        
                        Toast.makeText(this@ARActivity, "AR navigation started", Toast.LENGTH_SHORT).show()
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
    
    override fun onResume() {
        super.onResume()
        
        try {
            arCoreSessionHelper.onResume()
            view.onResume()
            
            // Start updating tracking quality indicator
            val handler = Handler(Looper.getMainLooper())
            handler.post(object : Runnable {
                override fun run() {
                    updateTrackingQualityIndicator()
                    handler.postDelayed(this, 1000)
                }
            })
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        
        try {
            view.onPause()
            arCoreSessionHelper.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        try {
            arCoreSessionHelper.onDestroy()
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
} 