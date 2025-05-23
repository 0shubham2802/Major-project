package com.google.ar.core.codelabs.hellogeospatial

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.widget.Toast
import androidx.multidex.MultiDex
import com.github.anrwatchdog.ANRWatchDog
import com.github.anrwatchdog.ANRError
import com.google.ar.core.codelabs.hellogeospatial.helpers.GoogleApiKeyValidator

/**
 * Application class for global error handling
 */
class HelloGeoApplication : Application() {

    companion object {
        private const val TAG = "HelloGeoApplication"
        
        // Static instance for global access
        private lateinit var instance: HelloGeoApplication
        
        // Memory and performance flags
        const val LOW_RESOURCE_MODE = "low_resource_mode"
        const val LOW_RESOURCE_MODE_AUTO = "auto"
        const val LOW_RESOURCE_MODE_FORCED = "forced"
        const val LOW_RESOURCE_MODE_DISABLED = "disabled"
        
        // For adjusting performance based on device capability
        private var lowResourceMode = LOW_RESOURCE_MODE_AUTO
        
        fun getInstance(): HelloGeoApplication {
            return instance
        }
        
        // Check if we should use low resource mode based on device specs
        fun shouldUseLowResourceMode(): Boolean {
            // Auto-detect based on device specs
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            
            // Consider low resource if < 512MB max heap
            if (maxMemory < 512) {
                return true
            }
            
            // Check if device is known to have issues
            val model = Build.MODEL.lowercase()
            val manufacturer = Build.MANUFACTURER.lowercase()
            
            // List of known low-end device keywords
            val lowEndKeywords = listOf("go", "lite", "a10", "a20", "j2", "j3", "j7")
            
            for (keyword in lowEndKeywords) {
                if (model.contains(keyword) || manufacturer.contains(keyword)) {
                    return true
                }
            }
            
            return false
        }
        
        // For forcing low resource mode for testing
        fun setLowResourceMode(mode: String) {
            lowResourceMode = mode
        }
    }
    
    // ANR watchdog
    private lateinit var anrWatchDog: ANRWatchDog
    
    // Memory warning flag
    private var hasShownLowMemoryWarning = false
    
    // For tracking and reporting crashes
    private var lastCrashTime = 0L
    
    // For storing detected device capability
    private var isLowResourceDevice = false
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Initialize multidex
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        // Add strict mode for development builds to detect issues early
        if (BuildConfig.DEBUG) {
            setupStrictMode()
        }
        
        // Set up memory trimming optimization
        registerActivityLifecycleCallbacks(AppActivityLifecycleCallbacks())
            
        super.onCreate()
        instance = this
        
        // Initialize multidex if needed
        setupMultidex()
        
        // Configure memory usage optimization
        setupMemoryOptimizations()
        
        // Check device compatibility early
        isLowResourceDevice = shouldUseLowResourceMode()
        Log.i(TAG, "Device running in ${if (isLowResourceDevice) "LOW RESOURCE" else "NORMAL"} mode")
        
        // For low resource devices, apply aggressive optimizations immediately
        if (isLowResourceDevice) {
            Log.i(TAG, "Applying aggressive optimizations for low-resource device")
            // Force GC to clear memory
            System.gc()
            
            // Show a notification to the user
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this,
                    "Running in compatibility mode for better performance",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        
        // Log device information for debugging
        logDeviceInfo()
        
        // Validate API keys early
        GoogleApiKeyValidator.validateApiKey(this)
        
        // Setup global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            try {
                // Track crash time to avoid loops
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCrashTime > 5000) { // Only show if 5 seconds since last crash
                    lastCrashTime = currentTime
                    
                    Toast.makeText(applicationContext, 
                        "Application error: ${throwable.message}", 
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing toast for uncaught exception", e)
            }
            
            // Let the default handler handle it too
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }
        
        // Initialize ANR watchdog with longer timeout for low-resource devices
        setupANRWatchdog()
        
        Log.d(TAG, "Application initialized")
    }
    
    /**
     * Set up StrictMode to detect improper thread usage in debug builds
     */
    private fun setupStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }
    
    /**
     * Set up multidex if needed for API level < 21
     */
    private fun setupMultidex() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // For API levels below 21
            MultiDex.install(this)
        }
    }
    
    /**
     * Configure memory optimizations
     */
    private fun setupMemoryOptimizations() {
        // Request low memory target for better stability
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.isLowRamDevice // Just to verify we can access ActivityManager
            
            // Verify heap size
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            Log.d(TAG, "Max memory available: $maxMemory MB")
            
            if (maxMemory < 256) {
                Log.w(TAG, "Very limited memory available ($maxMemory MB). App may be unstable.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up memory optimizations", e)
        }
    }
    
    /**
     * Setup ANR watchdog to detect and recover from app freezes
     */
    private fun setupANRWatchdog() {
        try {
            // For low resource devices, use longer timeout
            val timeoutMs = if (isLowResourceDevice) 20000 else 15000
            
            anrWatchDog = ANRWatchDog(timeoutMs)
            
            // Set ANR listener to handle freezes
            anrWatchDog.setANRListener { anrError ->
                Log.e(TAG, "ANR detected!", anrError)
                
                // Get the stacktrace as string
                val stackTraceString = anrError.message ?: "Unknown ANR error"
                Log.e(TAG, "ANR stack trace: $stackTraceString")
                
                // Try to recover on the main thread
                Handler(Looper.getMainLooper()).post {
                    try {
                        // Show a toast about the issue
                        Toast.makeText(
                            applicationContext,
                            "Application freeze detected, attempting recovery...",
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Cancel any pending operations
                        cancelPendingOperations()
                        
                        // Give the UI thread a chance to breathe
                        Thread.sleep(100)
                        
                        // Launch fallback activity to recover
                        val fallbackIntent = Intent(applicationContext, FallbackActivity::class.java)
                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        fallbackIntent.putExtra("FROM_ANR_RECOVERY", true)
                        try {
                            startActivity(fallbackIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start FallbackActivity directly", e)
                            // Try with a delay as a last resort
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    startActivity(fallbackIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to start FallbackActivity even with delay", e)
                                    // Last resort - try to restart the app
                                    restartApp()
                                }
                            }, 1000)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in ANR recovery", e)
                        restartApp()
                    }
                }
            }
            
            // Configure ANR watchdog to be more resilient
            anrWatchDog.setReportMainThreadOnly()
            
            // Set a daemon flag to ensure app can still exit if ANR thread is stuck
            anrWatchDog.setDaemon(true)
            
            // Start the watchdog
            anrWatchDog.start()
            Log.d(TAG, "ANR watchdog started with ${timeoutMs}ms timeout")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up ANR watchdog", e)
        }
    }
    
    /**
     * Method to cancel any pending operations that might be blocking the UI thread
     */
    private fun cancelPendingOperations() {
        try {
            // This is a placeholder for canceling any known operations
            // For example, cancel network requests, release camera resources, etc.
            Log.d(TAG, "Canceling pending operations")
            
            // Force garbage collection to free up resources
            System.gc()
            System.runFinalization()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling operations", e)
        }
    }
    
    /**
     * Last resort method to restart the app
     */
    private fun restartApp() {
        try {
            Log.w(TAG, "Attempting to restart app as last resort")
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            
            // Force exit current process
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart app", e)
        }
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        // Handle low memory situation
        Log.w(TAG, "System reports low memory condition")
        
        // Force garbage collection
        System.gc()
        
        // Show warning to user (but only once per session)
        if (!hasShownLowMemoryWarning) {
            hasShownLowMemoryWarning = true
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext,
                    "Low memory detected. App may behave unexpectedly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        when (level) {
            // Critical system situation - must free memory
            TRIM_MEMORY_COMPLETE, 
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "Critical memory situation (level $level)")
                System.gc()
                
                // Consider switching to map-only mode if in AR
                considerFallbackToMapMode()
            }
            // App is in background and system needs memory
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE -> {
                Log.d(TAG, "Background memory trim (level $level)")
                // Release non-critical resources
            }
            // App is active but system wants to reclaim memory
            TRIM_MEMORY_RUNNING_MODERATE,
            TRIM_MEMORY_RUNNING_LOW -> {
                Log.d(TAG, "Active memory trim request (level $level)")
                // Maybe reduce quality of rendering, etc.
            }
            else -> {
                Log.d(TAG, "Memory trim level: $level")
            }
        }
    }
    
    /**
     * Consider switching to fallback map mode if we're in AR and memory is critical
     */
    private fun considerFallbackToMapMode() {
        // This is just a placeholder implementation that logs
        // In a full implementation, you would communicate with active activities
        // to suggest switching to map-only mode
        Log.d(TAG, "Considering fallback to map-only mode due to memory constraints")
    }
    
    /**
     * Logs device information for debugging API/device issues
     */
    private fun logDeviceInfo() {
        try {
            Log.d(TAG, "=== Device Information ===")
            Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            Log.d(TAG, "Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            Log.d(TAG, "Build: ${Build.DISPLAY}")
            
            // Check app version
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                Log.d(TAG, "App Version: ${packageInfo.versionName} (${packageInfo.versionCode})")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting package info", e)
            }
            
            // Log available features
            val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
            val hasLocation = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
            val hasGPS = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            
            Log.d(TAG, "Camera available: $hasCamera")
            Log.d(TAG, "Location available: $hasLocation")
            Log.d(TAG, "GPS available: $hasGPS")
            
            // Log memory information
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            val freeMemory = runtime.freeMemory() / (1024 * 1024)
            val totalMemory = runtime.totalMemory() / (1024 * 1024)
            val usedMemory = totalMemory - freeMemory
            
            Log.d(TAG, "Memory - Max: $maxMemory MB, Total: $totalMemory MB, Free: $freeMemory MB, Used: $usedMemory MB")
            
            // Check Google Play Services
            try {
                val googlePlayServicesInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
                Log.d(TAG, "Google Play Services version: ${googlePlayServicesInfo.versionName}")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking Google Play Services", e)
            }
            
            // Log OpenGL info if available
            try {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val configInfo = activityManager.deviceConfigurationInfo
                Log.d(TAG, "OpenGL ES version: ${configInfo.glEsVersion}")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking OpenGL version", e)
            }
            
            Log.d(TAG, "=========================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging device info", e)
        }
    }
    
    /**
     * Activity lifecycle callbacks to manage memory throughout app lifecycle
     */
    private inner class AppActivityLifecycleCallbacks : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        
        override fun onActivityStarted(activity: android.app.Activity) {}
        
        override fun onActivityResumed(activity: android.app.Activity) {}
        
        override fun onActivityPaused(activity: android.app.Activity) {}
        
        override fun onActivityStopped(activity: android.app.Activity) {
            // When activity stops, trigger GC to free memory
            System.gc()
        }
        
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        
        override fun onActivityDestroyed(activity: android.app.Activity) {
            // When activity is destroyed, trigger GC to free memory
            System.gc()
        }
    }
} 