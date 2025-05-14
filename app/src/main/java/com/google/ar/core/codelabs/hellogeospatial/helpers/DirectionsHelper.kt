package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.graphics.Color
import android.os.AsyncTask
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList

/**
 * Helper class for fetching directions from Google Maps Directions API
 * and drawing them on the map
 */
class DirectionsHelper(private val context: Context) {
    companion object {
        private const val TAG = "DirectionsHelper"
        private const val DIRECTIONS_API_URL = "https://maps.googleapis.com/maps/api/directions/json"
    }
    
    interface DirectionsListener {
        fun onDirectionsReady(pathPoints: List<LatLng>)
        fun onDirectionsError(errorMessage: String)
    }
    
    /**
     * Fetch directions between two points
     */
    fun getDirections(origin: LatLng, destination: LatLng, listener: DirectionsListener) {
        FetchDirectionsTask(listener).execute(origin, destination)
    }
    
    /**
     * Draw the route on the map
     */
    fun drawRouteOnMap(map: GoogleMap, pathPoints: List<LatLng>) {
        try {
            // Clear previous polylines first
            map.clear()
            
            // Create polyline options
            val polylineOptions = PolylineOptions()
                .addAll(pathPoints)
                .width(10f)
                .color(Color.BLUE)
                
            // Add polyline to map
            map.addPolyline(polylineOptions)
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing route on map", e)
        }
    }
    
    /**
     * AsyncTask to fetch directions in background
     */
    private inner class FetchDirectionsTask(private val listener: DirectionsListener) : 
            AsyncTask<LatLng, Void, String>() {
        
        private var errorMessage: String? = null
        
        override fun doInBackground(vararg params: LatLng): String {
            val origin = params[0]
            val destination = params[1]
            
            var result = ""
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            
            try {
                // Build the URL for Google Directions API
                val apiKey = getApiKey()
                val urlString = "$DIRECTIONS_API_URL?origin=${origin.latitude},${origin.longitude}" +
                        "&destination=${destination.latitude},${destination.longitude}" +
                        "&mode=walking" +
                        "&key=$apiKey"
                
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connect()
                
                // If connection is successful
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = connection.inputStream
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = StringBuilder()
                    var line: String?
                    
                    // Read the response
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line)
                    }
                    
                    result = stringBuilder.toString()
                } else {
                    errorMessage = "Error connecting to Directions API: ${connection.responseCode}"
                    Log.e(TAG, errorMessage!!)
                }
            } catch (e: Exception) {
                errorMessage = "Error fetching directions: ${e.message}"
                Log.e(TAG, errorMessage!!, e)
            } finally {
                // Close connections
                inputStream?.close()
                connection?.disconnect()
            }
            
            return result
        }
        
        override fun onPostExecute(result: String) {
            super.onPostExecute(result)
            
            if (result.isEmpty() || errorMessage != null) {
                listener.onDirectionsError(errorMessage ?: "Unknown error fetching directions")
                return
            }
            
            try {
                // Parse the JSON response
                val pathPoints = parseDirectionsJson(result)
                
                if (pathPoints.isEmpty()) {
                    listener.onDirectionsError("No route found")
                } else {
                    listener.onDirectionsReady(pathPoints)
                }
            } catch (e: Exception) {
                listener.onDirectionsError("Error parsing directions: ${e.message}")
                Log.e(TAG, "Error parsing directions", e)
            }
        }
        
        /**
         * Parse the Google Directions API JSON response
         */
        private fun parseDirectionsJson(jsonData: String): List<LatLng> {
            val pathPoints = ArrayList<LatLng>()
            
            try {
                val jsonObject = JSONObject(jsonData)
                
                // Get the routes array
                val routes = jsonObject.getJSONArray("routes")
                
                if (routes.length() == 0) {
                    return pathPoints
                }
                
                // Get the first route
                val route = routes.getJSONObject(0)
                
                // Get the overview polyline
                val overviewPolyline = route.getJSONObject("overview_polyline")
                val encodedPolyline = overviewPolyline.getString("points")
                
                // Decode the polyline points
                pathPoints.addAll(decodePolyline(encodedPolyline))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing directions JSON", e)
                throw e
            }
            
            return pathPoints
        }
        
        /**
         * Decode an encoded polyline string into a list of LatLng
         */
        private fun decodePolyline(encoded: String): List<LatLng> {
            val poly = ArrayList<LatLng>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0
            
            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                
                // Decode latitude
                do {
                    b = encoded[index++].toInt() - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                
                val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lat += dlat
                
                // Decode longitude
                shift = 0
                result = 0
                do {
                    b = encoded[index++].toInt() - 63
                    result = result or (b and 0x1f shl shift)
                    shift += 5
                } while (b >= 0x20)
                
                val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                lng += dlng
                
                val position = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
                poly.add(position)
            }
            
            return poly
        }
    }
    
    /**
     * Get the Google Maps API key from the resources
     */
    private fun getApiKey(): String {
        val resourceId = context.resources.getIdentifier(
            "google_maps_key", 
            "string", 
            context.packageName
        )
        
        if (resourceId > 0) {
            return context.resources.getString(resourceId)
        }
        
        throw IllegalStateException("Google Maps API Key not found in resources")
    }
} 