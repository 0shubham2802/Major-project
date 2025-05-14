package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.ArCoreApk
import com.google.ar.core.codelabs.hellogeospatial.helpers.MapErrorHelper
import java.util.Locale

/**
 * A map-based activity with optional AR capabilities
 */
class FallbackActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mapFragment: SupportMapFragment
    private var googleMap: GoogleMap? = null
    private var destinationLatLng: LatLng? = null
    private var arModeButton: Button? = null
    
    companion object {
        private const val TAG = "FallbackActivity"
        private const val LOCATION_PERMISSION_CODE = 100
        private const val CAMERA_PERMISSION_CODE = 101
        private const val AR_MODE_REQUEST_CODE = 200
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set a default uncaught exception handler to catch and log any unexpected errors
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            runOnUiThread {
                Toast.makeText(this, "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
                showMapErrorUI("Application error: ${throwable.message}")
            }
        }
        
        try {
            // Set content view from layout XML first, to avoid issues with findViewById later
            setContentView(R.layout.activity_fallback)
            
            // Perform Google Play Services check first
            val googleApiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
            
            if (resultCode != ConnectionResult.SUCCESS) {
                Log.e(TAG, "Google Play Services unavailable: ${resultCode}")
                if (googleApiAvailability.isUserResolvableError(resultCode)) {
                    Log.i(TAG, "Showing Google Play Services resolution dialog")
                    googleApiAvailability.getErrorDialog(this, resultCode, 1001)?.show()
                    return
                } else {
                    showMapErrorUI("Google Play Services unavailable and cannot be resolved")
                    return
                }
            }
            
            // Diagnose potential map issues
            val mapIssues = MapErrorHelper.diagnoseMapIssues(this)
            if (mapIssues != "No issues detected") {
                Log.w(TAG, "Potential map issues: $mapIssues")
            }
            
            // Setup search bar functionality
            try {
                val searchBar = findViewById<EditText>(R.id.searchBar)
                searchBar?.setOnEditorActionListener { textView, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        val query = textView.text.toString()
                        if (query.isNotBlank()) {
                            searchLocation(query)
                            return@setOnEditorActionListener true
                        }
                    }
                    return@setOnEditorActionListener false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up search bar", e)
            }
            
            // Setup navigation button
            try {
                val navigateButton = findViewById<Button>(R.id.navigateButton)
                navigateButton?.setOnClickListener {
                    destinationLatLng?.let { destination ->
                        openGoogleMapsNavigation(destination)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up navigation button", e)
            }
            
            // Setup AR mode button only if successfully initialized other UI elements
            try {
                if (isARCorePotentiallySupported()) {
                    val container = findViewById<LinearLayout>(R.id.container)
                    if (container != null) {
                        val navButton = findViewById<Button>(R.id.navigateButton)
                        
                        // Add AR Mode button
                        arModeButton = Button(this).apply {
                            text = "Try AR Mode"
                            setBackgroundColor(ContextCompat.getColor(this@FallbackActivity, android.R.color.holo_blue_light))
                            setTextColor(Color.WHITE)
                            
                            val layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(16, 0, 16, 16)
                            }
                            
                            setOnClickListener { launchARMode() }
                            
                            // Safely add to container
                            if (navButton != null) {
                                try {
                                    container.addView(this, container.indexOfChild(navButton) + 1, layoutParams)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error adding button after nav button", e)
                                    container.addView(this, layoutParams)
                                }
                            } else {
                                container.addView(this, layoutParams)
                            }
                        }
                        
                        // Add Split Screen Mode button
                        val splitModeButton = Button(this).apply {
                            text = "Split Screen Mode"
                            setBackgroundColor(ContextCompat.getColor(this@FallbackActivity, android.R.color.holo_purple))
                            setTextColor(Color.WHITE)
                            
                            val layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(16, 0, 16, 16)
                            }
                            
                            setOnClickListener { launchSplitScreenMode() }
                            
                            // Add to container
                            container.addView(this, layoutParams)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up AR mode button", e)
                // Continue without AR button
            }
            
            // Check for location permissions
            checkLocationPermission()
            
            // Add the map fragment with better error handling - do this last
            try {
                // Find the map fragment from layout instead of creating it
                mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
                
                // Add a safety timeout for map loading - increased to 30 seconds for poor connections
                val mapLoadingTimeout = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    Log.e(TAG, "Map loading timed out")
                    runOnUiThread {
                        // Check network connectivity
                        val isConnected = isNetworkAvailable()
                        val errorMessage = if (isConnected) {
                            "Map loading timed out. Possible API key issue or service unavailable."
                        } else {
                            "Map loading timed out - check internet connection"
                        }
                        
                        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                        showMapErrorUI(errorMessage)
                    }
                }
                
                // Set a longer 30-second timeout for map loading on slow connections
                mapLoadingTimeout.postDelayed(timeoutRunnable, 30000)
                
                // Use OnMapReadyCallback interface implementation for better error handling
                mapFragment.getMapAsync { map ->
                    try {
                        // Cancel the timeout since map loaded successfully
                        mapLoadingTimeout.removeCallbacks(timeoutRunnable)
                        
                        Log.d(TAG, "Google Maps loaded successfully")
                        googleMap = map
                        
                        // Hide the loading indicator
                        findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                        
                        setupMap(findViewById(R.id.navigateButton))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize map", e)
                        Toast.makeText(this, "Failed to initialize map: ${e.message}", Toast.LENGTH_LONG).show()
                        showMapErrorUI("Failed to initialize map: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up map fragment", e)
                Toast.makeText(this, "Error setting up map: ${e.message}", Toast.LENGTH_LONG).show()
                showMapErrorUI("Error setting up map fragment: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing map view: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Create a simple fallback for the fallback
            showMapErrorUI("Error initializing map view: ${e.message}")
        }
    }
    
    // This is the OnMapReadyCallback implementation
    override fun onMapReady(map: GoogleMap) {
        try {
            Log.d(TAG, "Google Map is ready")
            googleMap = map
            
            // Hide the loading indicator
            findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
            
            setupMap(findViewById(R.id.navigateButton))
        } catch (e: Exception) {
            Log.e(TAG, "Error in onMapReady", e)
            Toast.makeText(this, "Error initializing map: ${e.message}", Toast.LENGTH_LONG).show()
            showMapErrorUI("Error initializing map: ${e.message}")
        }
    }
    
    private fun isARCorePotentiallySupported(): Boolean {
        return try {
            // Check if ARCore is installed
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            availability == ArCoreApk.Availability.SUPPORTED_INSTALLED || 
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ARCore availability", e)
            false
        }
    }
    
    private fun launchARMode() {
        // First check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
            return
        }
        
        try {
            // Check if ARActivity exists
            val arActivityClass = try {
                Class.forName("com.google.ar.core.codelabs.hellogeospatial.ARActivity")
                true
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "ARActivity class not found", e)
                false
            }
            
            if (!arActivityClass) {
                Toast.makeText(
                    this,
                    "AR mode is not available in this version of the app", 
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            
            // Launch the AR activity but keep this one in background
            val arIntent = Intent(this, ARActivity::class.java)
            
            // Pass destination data if we have it
            destinationLatLng?.let {
                arIntent.putExtra("DESTINATION_LAT", it.latitude)
                arIntent.putExtra("DESTINATION_LNG", it.longitude)
            }
            
            startActivityForResult(arIntent, AR_MODE_REQUEST_CODE)
            
            Toast.makeText(this, "Starting AR Mode - please wait", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch AR mode", e)
            Toast.makeText(this, "Failed to launch AR mode: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == AR_MODE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // AR worked successfully, potentially with a new destination
                data?.let {
                    if (it.hasExtra("DESTINATION_LAT") && it.hasExtra("DESTINATION_LNG")) {
                        val lat = it.getDoubleExtra("DESTINATION_LAT", 0.0)
                        val lng = it.getDoubleExtra("DESTINATION_LNG", 0.0)
                        
                        if (lat != 0.0 && lng != 0.0) {
                            // Update the map with the new destination
                            val newDest = LatLng(lat, lng)
                            destinationLatLng = newDest
                            
                            googleMap?.apply {
                                clear()
                                addMarker(MarkerOptions().position(newDest).title("AR Destination"))
                                animateCamera(CameraUpdateFactory.newLatLngZoom(newDest, 15f))
                            }
                            
                            // Show navigation button
                            findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                        }
                    }
                }
            } else if (resultCode == RESULT_CANCELED) {
                // AR mode was cancelled or failed
                Toast.makeText(this, "Returned to map mode", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun setupMap(navigateButton: Button) {
        try {
            googleMap?.apply {
                uiSettings.apply {
                    isZoomControlsEnabled = true
                    isCompassEnabled = true
                    isMyLocationButtonEnabled = true
                }
                
                // Enable my location layer if we have permission
                if (hasLocationPermission()) {
                    try {
                        isMyLocationEnabled = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not enable my location", e)
                    }
                }
                
                try {
                    // Move camera to a default location (can be replaced with actual user location)
                    moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(37.7749, -122.4194), // San Francisco as default
                        10f
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Could not move camera", e)
                }
                
                // Add click listener to allow selecting a point on the map
                try {
                    setOnMapClickListener { latLng ->
                        // Clear previous markers
                        clear()
                        
                        // Add new marker
                        addMarker(MarkerOptions()
                            .position(latLng)
                            .title("Selected Location"))
                        
                        // Store as destination
                        destinationLatLng = latLng
                        
                        // Show navigation button
                        navigateButton.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not set map click listener", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up map", e)
            Toast.makeText(this, "Error with map controls: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun searchLocation(query: String) {
        try {
            // Show loading indicator when searching
            findViewById<View>(R.id.map_loading_container)?.visibility = View.VISIBLE
            findViewById<TextView>(R.id.map_loading_text)?.text = "Searching for location..."
            
            val geocoder = Geocoder(this, Locale.getDefault())
            
            // Use the geocoder to find the location
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                
                // Hide loading indicator whether search succeeds or fails
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val latLng = LatLng(address.latitude, address.longitude)
                    
                    try {
                        googleMap?.apply {
                            clear()
                            addMarker(MarkerOptions().position(latLng).title(query))
                            animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                        }
                        
                        // Store as destination
                        destinationLatLng = latLng
                        
                        // Show the navigation button
                        findViewById<Button>(R.id.navigateButton).visibility = View.VISIBLE
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating map with search result", e)
                        Toast.makeText(this, "Found location but couldn't display on map", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Hide loading indicator if an error occurs
                findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
                
                Log.e(TAG, "Error with geocoder", e)
                Toast.makeText(this, "Error looking up location: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // Hide loading indicator if an error occurs
            findViewById<View>(R.id.map_loading_container)?.visibility = View.GONE
            
            Log.e(TAG, "Error in searchLocation", e)
            Toast.makeText(this, "Error searching for location", Toast.LENGTH_SHORT).show()
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
    
    private fun checkLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_CODE
            )
        }
    }
    
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
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
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            CAMERA_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Camera permission granted, now launch AR mode
                    launchARMode()
                } else {
                    Toast.makeText(this, "Camera permission is required for AR mode", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showMapErrorUI(errorMessage: String) {
        try {
            Log.e(TAG, "Showing map error UI: $errorMessage")
            
            // Create a completely new layout to avoid any issues with existing views
            val mainLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.WHITE)
            }
            
            // Add title
            val titleText = TextView(this).apply {
                text = "AR Navigation (Map Mode)"
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(16, 16, 16, 16)
            }
            mainLayout.addView(titleText)
            
            // Add subtitle
            val subtitleText = TextView(this).apply {
                text = "Your device doesn't fully support AR features. Using map-only mode."
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(16, 0, 16, 32)
            }
            mainLayout.addView(subtitleText)
            
            // Add error text
            val errorText = TextView(this).apply {
                text = "Error loading map interface. Please restart the app.\n\n$errorMessage"
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(32, 32, 32, 32)
            }
            mainLayout.addView(errorText)
            
            // Create a button container for multiple options
            val buttonContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            
            // Add retry button
            val retryButton = Button(this).apply {
                text = "RETRY"
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 16, 8)
                }
                
                setOnClickListener {
                    // Restart the activity
                    val intent = intent
                    finish()
                    startActivity(intent)
                }
            }
            buttonContainer.addView(retryButton)
            
            // Add option to use maps app directly
            val openMapsButton = Button(this).apply {
                text = "OPEN GOOGLE MAPS APP"
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 16, 8)
                }
                
                setOnClickListener {
                    // Open Google Maps app
                    val gmmIntentUri = Uri.parse("geo:0,0?q=restaurants")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    if (mapIntent.resolveActivity(packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        Toast.makeText(context, "Google Maps app not installed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            buttonContainer.addView(openMapsButton)
            
            // Add option to use browser maps
            val openBrowserButton = Button(this).apply {
                text = "OPEN MAPS IN BROWSER"
                setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
                setTextColor(Color.WHITE)
                setPadding(32, 16, 32, 16)
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 8, 16, 24)
                }
                
                setOnClickListener {
                    // Open Google Maps in browser
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com"))
                    startActivity(browserIntent)
                }
            }
            buttonContainer.addView(openBrowserButton)
            
            mainLayout.addView(buttonContainer)
            
            // Set the completely new content view
            setContentView(mainLayout)
            
            // Log the error for debugging
            Log.e(TAG, "Map error: $errorMessage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show map error UI", e)
            
            // Last resort - create a minimal UI with just an error message and retry button
            try {
                val simpleLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.WHITE)
                }
                
                val simpleText = TextView(this).apply {
                    text = "Error loading map interface. Please restart the app or check your internet connection."
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTextColor(Color.BLACK)
                    setPadding(32, 32, 32, 32)
                }
                
                val simpleButton = Button(this).apply {
                    text = "RETRY"
                    setBackgroundColor(Color.BLUE)
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        recreate()
                    }
                }
                
                simpleLayout.addView(simpleText)
                simpleLayout.addView(simpleButton)
                setContentView(simpleLayout)
            } catch (t: Throwable) {
                // At this point, there's not much else we can do
                Log.e(TAG, "Fatal error creating UI", t)
            }
        }
    }
    
    private fun launchSplitScreenMode() {
        // First check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, 
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
            return
        }
        
        try {
            val splitScreenIntent = Intent(this, SplitScreenActivity::class.java)
            
            // Pass destination data if we have it
            destinationLatLng?.let {
                splitScreenIntent.putExtra("DESTINATION_LAT", it.latitude)
                splitScreenIntent.putExtra("DESTINATION_LNG", it.longitude)
            }
            
            startActivity(splitScreenIntent)
            Toast.makeText(this, "Loading split screen mode - please wait", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch split screen mode", e)
            Toast.makeText(this, "Failed to launch split screen mode: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                       capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                // Legacy method for older Android versions
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                return networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network state", e)
            return false
        }
    }
} 