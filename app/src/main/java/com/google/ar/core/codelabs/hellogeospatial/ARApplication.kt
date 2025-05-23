package com.google.ar.core.codelabs.hellogeospatial

import android.app.Application
import android.util.Log

/**
 * Application class for the AR application.
 */
class ARApplication : Application() {
    companion object {
        private const val TAG = "ARApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AR Application initialized")
    }
} 