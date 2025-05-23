package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*

/**
 * Simple helper for managing ARCore session setup and lifecycle.
 */
class ARCoreHelper(private val context: Context) {
    companion object {
        private const val TAG = "ARCoreHelper"
    }

    private var session: Session? = null
    private var installRequested = false

    /**
     * Check if ARCore is installed and updated
     */
    fun checkARCoreAvailability(activity: Activity): Boolean {
        // Make sure ARCore is installed and up-to-date
        return try {
            when (ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> true
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    try {
                        // Request ARCore installation or update if needed
                        if (installRequested) {
                            return false
                        }
                        
                        if (ArCoreApk.getInstance()
                                .requestInstall(activity, !installRequested) ==
                            ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                            installRequested = true
                            return false
                        }
                        return false
                    } catch (e: Exception) {
                        Log.e(TAG, "ARCore installation failed", e)
                        Toast.makeText(activity, "ARCore installation failed", Toast.LENGTH_LONG).show()
                        return false
                    }
                }
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    Toast.makeText(activity, "ARCore is not supported on this device", Toast.LENGTH_LONG).show()
                    return false
                }
                else -> {
                    Toast.makeText(activity, "ARCore is not available", Toast.LENGTH_LONG).show()
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ARCore check failed", e)
            return false
        }
    }

    /**
     * Create an ARCore session, or null if there was an error
     */
    fun createARSession(activity: Activity): Session? {
        if (session != null) {
            return session
        }
        
        if (!CameraPermissionHelper.hasCameraPermission(activity)) {
            CameraPermissionHelper.requestCameraPermission(activity)
            return null
        }
        
        // ARCore requires camera permission
        if (!checkARCoreAvailability(activity)) {
            return null
        }
        
        // Create a new ARCore session
        session = try {
            Session(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session", e)
            when (e) {
                is UnavailableArcoreNotInstalledException,
                is UnavailableUserDeclinedInstallationException -> {
                    Toast.makeText(context, "Please install ARCore", Toast.LENGTH_LONG).show()
                }
                is UnavailableApkTooOldException -> {
                    Toast.makeText(context, "Please update ARCore", Toast.LENGTH_LONG).show()
                }
                is UnavailableSdkTooOldException -> {
                    Toast.makeText(context, "Please update this app", Toast.LENGTH_LONG).show()
                }
                is UnavailableDeviceNotCompatibleException -> {
                    Toast.makeText(context, "This device does not support AR", Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(context, "Failed to create AR session: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            null
        }
        
        // Configure the session if created successfully
        configureSession()
        
        return session
    }

    /**
     * Configure the session for best performance
     */
    private fun configureSession() {
        val session = this.session ?: return
        
        val config = session.config
        
        // Enable focus mode for better tracking
        config.focusMode = Config.FocusMode.AUTO
        
        // Enable plane detection for better placement of objects
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        
        // Enable depth for better occlusion
        config.depthMode = Config.DepthMode.AUTOMATIC
        
        // Enable light estimation
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        
        // Apply the configuration
        session.configure(config)
    }

    /**
     * Close the ARCore session and release resources
     */
    fun close() {
        session?.close()
        session = null
    }
    
    /**
     * Get the current ARCore session
     */
    fun getSession(): Session? {
        return session
    }
} 