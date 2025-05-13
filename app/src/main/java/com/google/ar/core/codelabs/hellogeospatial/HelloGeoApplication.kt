package com.google.ar.core.codelabs.hellogeospatial

import android.app.Application
import android.util.Log
import android.widget.Toast

/**
 * Application class for global error handling
 */
class HelloGeoApplication : Application() {

    companion object {
        private const val TAG = "HelloGeoApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Setup global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            try {
                Toast.makeText(applicationContext, 
                    "Application error: ${throwable.message}", 
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing toast for uncaught exception", e)
            }
            
            // Let the default handler handle it too
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }
        
        Log.d(TAG, "Application initialized")
    }
} 