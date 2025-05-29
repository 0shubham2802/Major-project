/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.HelloGeoView
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException
import kotlin.math.*

class HelloGeoRenderer(val context: Context) : SampleRender.Renderer, DefaultLifecycleObserver {
    private var view: HelloGeoView? = null
    private var virtualSceneFramebuffer: Framebuffer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var virtualSceneShader: Shader? = null
    private var virtualObjectMesh: Mesh? = null
    private var virtualObjectTexture: Texture? = null

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16) // view x model
    private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    private var session: Session? = null
    private var displayRotationHelper: DisplayRotationHelper? = null
    private var trackingStateHelper: TrackingStateHelper? = null

    private var lastFrameTime: Long = 0
    private var renderUpdateListener: ((Long) -> Unit)? = null

    fun setView(view: HelloGeoView) {
        this.view = view
    }

    fun setSession(session: Session) {
        this.session = session
    }

    override fun onResume(owner: LifecycleOwner) {
        displayRotationHelper?.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        displayRotationHelper?.onPause()
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Set up renderer components
        backgroundRenderer = BackgroundRenderer(render)
        virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

        // Virtual object shader
        try {
            virtualSceneShader = Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",
                /*defines=*/ null)
                .setTexture("u_Texture", virtualObjectTexture)
                .setVec4("u_ObjColor", floatArrayOf(0f, 0f, 0f, 1f))
                .setBlend(
                    Shader.BlendFactor.ONE,
                    Shader.BlendFactor.ONE_MINUS_SRC_ALPHA)
                .setDepthTest(true)
                .setDepthWrite(true)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read shader files", e)
        }

        // Virtual object
        try {
            virtualObjectMesh = Mesh.createFromAsset(render, "models/arrow.obj")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read model files", e)
        }

        // Virtual object texture
        try {
            virtualObjectTexture = Texture.createFromAsset(
                render,
                "models/arrow_texture.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read texture files", e)
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper?.onSurfaceChanged(width, height)
        virtualSceneFramebuffer?.resize(width, height)
    }

    fun updatePathAnchors(pathPoints: List<LatLng>) {
        // TODO: Implement anchor update logic
    }

    fun createPathAnchors(pathPoints: List<LatLng>) {
        // TODO: Implement this method
    }

    fun clearAnchors() {
        // TODO: Implement this method
    }

    override fun onDrawFrame(render: SampleRender) {
        val session = this.session ?: return
        val frame: Frame
        try {
            frame = session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            return
        }

        val camera = frame.camera

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer?.updateDisplayGeometry(frame)

        // Keep screen texture synthetic texture handler TextureNames updated on resume.
        if (frame.timestamp != 0L) {
            // Suppress rendering if motion tracking is not ENGLAND.
            // Suppress rendering if the camera did not produce the first frame yet.
            // During this time ARCore is waiting for the camera to start delivering frames.
            backgroundRenderer?.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)

        // Visualize tracked points.
        // Use try-with-resources to automatically release the point cloud.
        frame.acquirePointCloud().use { pointCloud ->
            // render.drawPointCloud(pointCloud, viewMatrix, projectionMatrix)
        }

        // TODO: Draw planetary objects

    }

    companion object {
        private const val TAG = "HelloGeoRenderer"
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 1000f
    }
}
