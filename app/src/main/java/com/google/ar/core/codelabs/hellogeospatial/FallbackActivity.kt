package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
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
import java.util.Locale

/**
 * A simple fallback activity with Map functionality for devices that don't support AR
 */
class FallbackActivity : AppCompatActivity() {
    private lateinit var mapFragment: SupportMapFragment
    private var googleMap: GoogleMap? = null
    
    companion object {
        private const val LOCATION_PERMISSION_CODE = 100
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create a linear layout for our UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // Add a title
        val titleText = TextView(this).apply {
            text = "AR Navigation (Map Mode)"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }
        layout.addView(titleText)
        
        // Add a search bar
        val searchBar = EditText(this).apply {
            hint = "Search location"
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 0, 16, 16)
            }
            
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            
            setOnEditorActionListener { textView, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val query = textView.text.toString()
                    if (query.isNotBlank()) {
                        searchLocation(query)
                        return@setOnEditorActionListener true
                    }
                }
                return@setOnEditorActionListener false
            }
        }
        layout.addView(searchBar)
        
        // Set the content view to our layout
        setContentView(layout)
        
        // Add the map fragment
        mapFragment = SupportMapFragment()
        supportFragmentManager.beginTransaction()
            .add(View.generateViewId(), mapFragment)
            .commit()
        
        mapFragment.getMapAsync { map ->
            googleMap = map
            setupMap()
        }
        
        // Check for location permissions
        checkLocationPermission()
    }
    
    private fun setupMap() {
        googleMap?.apply {
            uiSettings.apply {
                isZoomControlsEnabled = true
                isCompassEnabled = true
                isMyLocationButtonEnabled = true
            }
            
            // Enable my location layer if we have permission
            if (hasLocationPermission()) {
                isMyLocationEnabled = true
            }
            
            // Move camera to a default location (can be replaced with actual user location)
            moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(37.7749, -122.4194), // San Francisco as default
                10f
            ))
        }
    }
    
    private fun searchLocation(query: String) {
        try {
            val geocoder = Geocoder(this, Locale.getDefault())
            
            // Use the geocoder to find the location
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocationName(query, 1)
            
            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                val latLng = LatLng(address.latitude, address.longitude)
                
                googleMap?.apply {
                    clear()
                    addMarker(MarkerOptions().position(latLng).title(query))
                    animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                }
            } else {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error searching for location", Toast.LENGTH_SHORT).show()
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
        
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, enable my location on the map
                googleMap?.isMyLocationEnabled = true
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
} 