package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.graphics.Color
import android.os.AsyncTask
import android.util.Log
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import com.google.maps.DirectionsApi
import com.google.maps.GeoApiContext
import com.google.maps.android.PolyUtil
import com.google.maps.model.DirectionsResult
import com.google.maps.model.TravelMode
import com.google.ar.core.codelabs.hellogeospatial.R
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for fetching directions from Google Maps Directions API
 * and drawing them on the map
 */
class DirectionsHelper(private val context: Context) {
    companion object {
        private const val TAG = "DirectionsHelper"
        private const val DIRECTIONS_API_URL = "https://maps.googleapis.com/maps/api/directions/json"
    }
    
    // Define transportation modes
    enum class TransportMode(val apiValue: String, val speedFactor: Double) {
        WALKING("walking", 1.4),              // ~5 km/h or 1.4 m/s average walking speed
        TWO_WHEELER("driving", 8.3),          // ~30 km/h or 8.3 m/s for 2-wheeler
        FOUR_WHEELER("driving", 13.9)         // ~50 km/h or 13.9 m/s for 4-wheeler
    }
    
    // Current transport mode
    var currentTransportMode = TransportMode.WALKING
        private set
    
    // Store last fetched instructions and steps for continuous updates
    var lastInstructions: List<String> = emptyList()
        private set
    
    var lastSteps: List<DirectionStep> = emptyList()
        private set
    
    // Data class for direction steps with instructions
    data class DirectionStep(
        val startLocation: LatLng,
        val endLocation: LatLng,
        val instruction: String,
        val distance: Int, // in meters
        val points: List<LatLng> // polyline points for this step
    )
    
    interface DirectionsListener {
        fun onDirectionsReady(pathPoints: List<LatLng>)
        fun onDirectionsError(errorMessage: String)
    }
    
    // Enhanced interface with instructions
    interface DirectionsWithInstructionsListener {
        fun onDirectionsReady(pathPoints: List<LatLng>, instructions: List<String>, steps: List<DirectionStep>)
        fun onDirectionsError(errorMessage: String)
    }
    
    private val geoApiContext: GeoApiContext by lazy {
        GeoApiContext.Builder()
            .apiKey(context.getString(R.string.google_maps_key))
            .build()
    }
    
    /**
     * Fetch directions between two points (basic)
     */
    suspend fun getDirections(origin: LatLng, destination: LatLng, travelMode: TravelMode): List<LatLng> = withContext(Dispatchers.IO) {
        try {
            val result = DirectionsApi.newRequest(geoApiContext)
                .origin(com.google.maps.model.LatLng(origin.latitude, origin.longitude))
                .destination(com.google.maps.model.LatLng(destination.latitude, destination.longitude))
                .mode(travelMode)
                .await()

            if (result.routes.isEmpty() || result.routes[0].legs.isEmpty()) {
                return@withContext emptyList()
            }

            // Convert encoded polyline to list of LatLng
            val encodedPath = result.routes[0].overviewPolyline.encodedPath
            return@withContext PolyUtil.decode(encodedPath).map { 
                LatLng(it.latitude, it.longitude)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }
    
    /**
     * Fetch directions with turn-by-turn instructions
     */
    fun getDirectionsWithInstructions(
        origin: LatLng, 
        destination: LatLng, 
        listener: DirectionsWithInstructionsListener,
        transportMode: TransportMode = TransportMode.WALKING
    ) {
        // Set the current transport mode
        currentTransportMode = transportMode
        FetchDirectionsTask(listener, transportMode).execute(origin, destination)
    }
    
    /**
     * Set transportation mode
     */
    fun setTransportMode(mode: TransportMode) {
        currentTransportMode = mode
    }
    
    /**
     * Clear directions data
     */
    fun clearDirections() {
        lastInstructions = emptyList()
        lastSteps = emptyList()
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
    private inner class FetchDirectionsTask(
        private val listener: DirectionsWithInstructionsListener,
        private val transportMode: TransportMode
    ) : AsyncTask<LatLng, Void, String>() {
        
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
                        "&mode=${transportMode.apiValue}" +
                        "&key=$apiKey"
                
                Log.d(TAG, "Making directions request to: ${urlString.replace(apiKey, "API_KEY_REDACTED")}")
                
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000 // 15 second timeout
                connection.readTimeout = 15000
                
                try {
                    connection.connect()
                } catch (e: IOException) {
                    errorMessage = "Network error: Please check your internet connection"
                    Log.e(TAG, "Failed to connect to Directions API", e)
                    return result
                }
                
                // If connection is successful
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    inputStream = connection.inputStream
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = StringBuilder()
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line).append("\n")
                    }
                    result = stringBuilder.toString()
                } else {
                    errorMessage = "Server error: ${connection.responseCode}"
                    Log.e(TAG, "Server error: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                errorMessage = "Error fetching directions: ${e.message}"
                Log.e(TAG, "Error fetching directions", e)
            } finally {
                inputStream?.close()
                connection?.disconnect()
            }
            
            return result
        }
        
        override fun onPostExecute(result: String) {
            if (errorMessage != null) {
                listener.onDirectionsError(errorMessage!!)
                return
            }
            
            try {
                val jsonResponse = JSONObject(result)
                val status = jsonResponse.getString("status")
                
                if (status == "OK") {
                    val routes = jsonResponse.getJSONArray("routes")
                    if (routes.length() > 0) {
                        val route = routes.getJSONObject(0)
                        val legs = route.getJSONArray("legs")
                        val leg = legs.getJSONObject(0)
                        
                        // Extract overview polyline
                        val overviewPolyline = route.getJSONObject("overview_polyline")
                        val points = overviewPolyline.getString("points")
                        val pathPoints = decodePolyline(points)
                        
                        // Extract steps and instructions
                        val steps = leg.getJSONArray("steps")
                        val directionSteps = ArrayList<DirectionStep>()
                        val instructions = ArrayList<String>()
                        
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)
                            val instruction = step.getString("html_instructions")
                            val distance = step.getJSONObject("distance").getInt("value")
                            val startLoc = step.getJSONObject("start_location")
                            val endLoc = step.getJSONObject("end_location")
                            val stepPolyline = step.getJSONObject("polyline").getString("points")
                            
                            val startLatLng = LatLng(startLoc.getDouble("lat"), startLoc.getDouble("lng"))
                            val endLatLng = LatLng(endLoc.getDouble("lat"), endLoc.getDouble("lng"))
                            val stepPoints = decodePolyline(stepPolyline)
                            
                            directionSteps.add(DirectionStep(startLatLng, endLatLng, instruction, distance, stepPoints))
                            instructions.add(instruction)
                        }
                        
                        // Store the fetched data
                        lastInstructions = instructions
                        lastSteps = directionSteps
                        
                        // Notify listener
                        listener.onDirectionsReady(pathPoints, instructions, directionSteps)
                    } else {
                        listener.onDirectionsError("No routes found")
                    }
                } else {
                    listener.onDirectionsError("Error: $status")
                }
            } catch (e: Exception) {
                listener.onDirectionsError("Error parsing directions: ${e.message}")
                Log.e(TAG, "Error parsing directions", e)
            }
        }
    }
    
    /**
     * Decode a polyline string into a list of LatLng points
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
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            
            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }
    
    /**
     * Get the API key from resources
     */
    private fun getApiKey(): String {
        return context.getString(R.string.google_maps_key)
    }
} 