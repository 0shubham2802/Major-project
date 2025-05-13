package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
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
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.ar.core.ArCoreApk
import java.util.Locale

/**
 * A map-based activity with optional AR capabilities
 */
class FallbackActivity : AppCompatActivity() {
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
        
        // Set a default uncaught exception handler
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception", throwable)
            Toast.makeText(this, "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
        }
        
        try {
            // Set content view from layout XML
            setContentView(R.layout.activity_fallback)
            
            // Setup search bar functionality
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
                    openGoogleMapsNavigation(destination)
                }
            }
            
            // Setup AR mode button (only if AR is potentially available)
            if (isARCorePotentiallySupported()) {
                arModeButton = Button(this).apply {
                    text = "Try AR Mode"
                    setBackgroundColor(ContextCompat.getColor(this@FallbackActivity, android.R.color.holo_blue_light))
                    setTextColor(Color.WHITE)
                    
                    // Add right after the navigate button
                    val layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(16, 0, 16, 16)
                    }
                    
                    setOnClickListener {
                        launchARMode()
                    }
                    
                    // Add to layout
                    val container = findViewById<LinearLayout>(R.id.container)
                    container.addView(this, container.indexOfChild(navigateButton) + 1, layoutParams)
                }
            }
            
            // Add the map fragment
            try {
                mapFragment = SupportMapFragment()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.map_container, mapFragment)
                    .commit()
                
                mapFragment.getMapAsync { map ->
                    googleMap = map
                    setupMap(navigateButton)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up map", e)
                Toast.makeText(this, "Error setting up map: ${e.message}", Toast.LENGTH_LONG).show()
            }
            
            // Check for location permissions
            checkLocationPermission()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing map view: ${e.message}", Toast.LENGTH_LONG).show()
            
            // Create a simple fallback for the fallback
            try {
                val simpleText = TextView(this).apply {
                    text = "Error loading map interface. Please restart the app."
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTextColor(Color.BLACK)
                }
                setContentView(simpleText)
            } catch (t: Throwable) {
                // At this point, there's not much else we can do
                Log.e(TAG, "Fatal error creating UI", t)
            }
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
            val geocoder = Geocoder(this, Locale.getDefault())
            
            // Use the geocoder to find the location
            try {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(query, 1)
                
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
                Log.e(TAG, "Error with geocoder", e)
                Toast.makeText(this, "Error looking up location: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
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
} 