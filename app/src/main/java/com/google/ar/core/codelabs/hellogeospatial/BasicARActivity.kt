package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Simple activity showing camera feed
 */
class BasicARActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "BasicARActivity"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val CAMERA_OPEN_TIMEOUT_MS = 2500L
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // A semaphore to prevent the app from exiting before closing the camera
    private val cameraOpenCloseLock = Semaphore(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create a simple surface view for the camera
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        
        // Initialize the surface holder
        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)
        
        // Get the camera manager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun onResume() {
        super.onResume()
        
        // Start background thread
        startBackgroundThread()
        
        // Check camera permission
        if (checkCameraPermission()) {
            if (surfaceHolder.surface.isValid) {
                openCamera()
            }
        } else {
            requestCameraPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        
        // Close the camera
        closeCamera()
        
        // Stop background thread
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = backgroundThread?.looper?.let { Handler(it) }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (surfaceHolder.surface.isValid) {
                    openCamera()
                }
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is needed for this feature",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun openCamera() {
        if (!checkCameraPermission()) {
            requestCameraPermission()
            return
        }
        
        try {
            if (!cameraOpenCloseLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Toast.makeText(this, "Time out waiting to lock camera", Toast.LENGTH_LONG).show()
                return
            }
            
            val cameraId = findCameraIdByFacing(CameraCharacteristics.LENS_FACING_BACK)
            
            if (cameraId.isEmpty()) {
                Toast.makeText(this, "No back-facing camera found", Toast.LENGTH_LONG).show()
                cameraOpenCloseLock.release()
                return
            }
            
            // Open the camera
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        cameraDevice = camera
                        createCameraPreviewSession()
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                        Toast.makeText(
                            this@BasicARActivity, 
                            "Camera disconnected", 
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        cameraOpenCloseLock.release()
                        camera.close()
                        cameraDevice = null
                        
                        // Handle specific error codes
                        val errorMsg = when(error) {
                            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> 
                                "Camera is already in use"
                            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> 
                                "Maximum cameras in use"
                            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> 
                                "Camera is disabled"
                            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> 
                                "Camera device error"
                            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> 
                                "Camera service error"
                            else -> "Unknown camera error: $error"
                        }
                        
                        Toast.makeText(
                            this@BasicARActivity,
                            errorMsg,
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Try to recover by closing any existing camera resources and reopening
                        Handler(mainLooper).postDelayed({
                            closeCamera()
                            openCamera()
                        }, 1000)
                    }
                }, backgroundHandler)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Error opening camera", e)
                Toast.makeText(this, "Failed to open camera: ${e.message}", Toast.LENGTH_SHORT).show()
                cameraOpenCloseLock.release()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception opening camera", e)
                Toast.makeText(this, "No camera permission", Toast.LENGTH_SHORT).show()
                cameraOpenCloseLock.release()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error opening camera", e)
                Toast.makeText(this, "Unexpected error: ${e.message}", Toast.LENGTH_SHORT).show()
                cameraOpenCloseLock.release()
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while trying to lock camera opening", e)
            Toast.makeText(this, "Error locking camera", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun findCameraIdByFacing(facing: Int): String {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val cameraFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraFacing == facing) {
                    return cameraId
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error accessing camera characteristics", e)
        }
        return ""
    }
    
    private fun createCameraPreviewSession() {
        try {
            // Make sure we have a valid camera device
            val camera = cameraDevice ?: return
            
            // Get the surface from the SurfaceHolder
            val surface = surfaceHolder.surface
            
            // Create a capture request
            val captureRequestBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            captureRequestBuilder.addTarget(surface)
            
            // Create a capture session
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        
                        cameraCaptureSession = session
                        try {
                            // Start the preview
                            captureRequestBuilder.set(
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting camera preview", e)
                            Toast.makeText(
                                this@BasicARActivity,
                                "Error starting preview: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(
                            this@BasicARActivity,
                            "Failed to configure camera",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Try again after a delay
                        Handler(mainLooper).postDelayed({
                            closeCamera()
                            openCamera()
                        }, 1000)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera preview session", e)
            Toast.makeText(
                this,
                "Error setting up preview: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while trying to lock camera closing", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    // SurfaceHolder.Callback implementation
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (checkCameraPermission()) {
            openCamera()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // No need for special handling here
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        closeCamera()
    }
} 