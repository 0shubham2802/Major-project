package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Helper class for diagnosing map loading issues
 */
object MapErrorHelper {
    private const val TAG = "MapErrorHelper"
    
    /**
     * Checks if Google Play Services are available and properly configured
     * @return true if Google Play Services are available and properly configured
     */
    fun checkGooglePlayServices(context: Context): Boolean {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        
        if (resultCode != ConnectionResult.SUCCESS) {
            Log.e(TAG, "Google Play Services not available: ${googleApiAvailability.getErrorString(resultCode)}")
            
            if (googleApiAvailability.isUserResolvableError(resultCode)) {
                Log.d(TAG, "User resolvable error: ${googleApiAvailability.getErrorString(resultCode)}")
            }
            
            return false
        }
        
        Log.d(TAG, "Google Play Services available and properly configured")
        return true
    }
    
    /**
     * Performs additional checks for map initialization
     */
    fun diagnoseMapIssues(context: Context): String {
        val sb = StringBuilder()
        
        // Check if API key is properly configured
        try {
            val applicationInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            
            val mapsApiKey = applicationInfo.metaData?.getString("com.google.android.geo.API_KEY")
            if (mapsApiKey.isNullOrEmpty()) {
                sb.append("Missing Google Maps API key in AndroidManifest.xml. ")
            } else if (!mapsApiKey.startsWith("AIza")) {
                sb.append("Invalid Google Maps API key format. ")
            }
        } catch (e: Exception) {
            sb.append("Error checking API key: ${e.message}. ")
        }
        
        // Check Google Play Services
        if (!checkGooglePlayServices(context)) {
            sb.append("Google Play Services unavailable or outdated. ")
        }
        
        // Check internet connectivity
        try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("ping -c 1 maps.googleapis.com")
            val exitValue = process.waitFor()
            if (exitValue != 0) {
                sb.append("Internet connectivity issue detected. ")
            }
        } catch (e: Exception) {
            sb.append("Error checking internet: ${e.message}. ")
        }
        
        return if (sb.isEmpty()) "No issues detected" else sb.toString()
    }
} 