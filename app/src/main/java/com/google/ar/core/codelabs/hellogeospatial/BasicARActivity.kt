package com.google.ar.core.codelabs.hellogeospatial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Simple activity showing camera feed
 */
class BasicARActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "BasicARActivity"
        private const val CAMERA_PERMISSION_CODE = 100
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null

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
        
        // Check camera permission
        if (checkCameraPermission()) {
            openCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        
        // Close the camera
        closeCamera()
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
                openCamera()
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
        try {
            if (checkCameraPermission()) {
                // Get the first back-facing camera
                val cameraId = cameraManager.cameraIdList.first()
                
                // Open the camera
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCameraPreviewSession()
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        Toast.makeText(
                            this@BasicARActivity,
                            "Camera error: $error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            Toast.makeText(this, "Error opening camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createCameraPreviewSession() {
        try {
            // Get the surface from the SurfaceHolder
            val surface = surfaceHolder.surface
            
            // Create a capture request
            val captureRequestBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            captureRequestBuilder?.addTarget(surface)
            
            // Create a capture session
            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        
                        cameraCaptureSession = session
                        try {
                            // Start the preview
                            captureRequestBuilder?.let {
                                it.set(
                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                session.setRepeatingRequest(it.build(), null, null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting camera preview", e)
                        }
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(
                            this@BasicARActivity,
                            "Failed to configure camera",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera preview session", e)
        }
    }
    
    private fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
    }

    // SurfaceHolder.Callback implementation
    override fun surfaceCreated(holder: SurfaceHolder) {
        openCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // No need to handle this
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        closeCamera()
    }
} 