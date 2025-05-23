package com.google.ar.core.codelabs.hellogeospatial.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * This class renders the AR background from camera feed. It creates and hosts the texture
 * that will be passed to ARCore to be filled with the camera image.
 */
class BackgroundRenderer {
    private var cameraTextureId = -1
    private var depthTextureId = -1
    private var cameraProgram = 0
    private var depthProgram = 0
    private var cameraPositionAttrib = 0
    private var cameraTexCoordAttrib = 0
    private var cameraTextureUniform = 0
    private var quadCoords: FloatBuffer? = null
    private var quadTexCoords: FloatBuffer? = null

    companion object {
        private const val TAG = "BackgroundRenderer"
        
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f, -1.0f, +1.0f, 0.0f, +1.0f, -1.0f, 0.0f, +1.0f, +1.0f, 0.0f
        )
        
        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f
        )
        
        private val CAMERA_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
            }
        """
        
        private val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
               gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }

    /**
     * Create the texture used to receive the camera feed
     */
    fun createCameraTexture() {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        cameraTextureId = textureIds[0]
        val textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(textureTarget, cameraTextureId)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(textureTarget, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }

    /**
     * Initialize OpenGL resources used to render camera background
     */
    fun initialize(context: Context) {
        // Create shader program
        try {
            cameraProgram = ShaderUtil.createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create shader program", e)
        }
        
        // Initialize attributes and uniforms
        cameraPositionAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        cameraTexCoordAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
        cameraTextureUniform = GLES20.glGetUniformLocation(cameraProgram, "u_Texture")
        
        // Initialize vertex buffers
        quadCoords = ByteBuffer.allocateDirect(QUAD_COORDS.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }
        
        quadTexCoords = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_TEXCOORDS)
                position(0)
            }
    }

    /**
     * Draw the camera background
     */
    fun draw(frame: Frame) {
        // Make sure we have a valid texture
        if (cameraTextureId == -1) {
            return
        }

        // No need to test or write depth, camera feed is the background
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        
        GLES20.glUseProgram(cameraProgram)
        
        // Set the camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureUniform, 0)
        
        // Bind vertex attributes
        GLES20.glEnableVertexAttribArray(cameraPositionAttrib)
        GLES20.glVertexAttribPointer(
            cameraPositionAttrib,
            3, // Position has 3 components (x, y, z)
            GLES20.GL_FLOAT,
            false,
            0,
            quadCoords
        )
        
        GLES20.glEnableVertexAttribArray(cameraTexCoordAttrib)
        GLES20.glVertexAttribPointer(
            cameraTexCoordAttrib,
            2, // Texture coordinates have 2 components (u, v)
            GLES20.GL_FLOAT,
            false,
            0,
            quadTexCoords
        )
        
        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Clean up
        GLES20.glDisableVertexAttribArray(cameraPositionAttrib)
        GLES20.glDisableVertexAttribArray(cameraTexCoordAttrib)
        
        // Reset depth testing for 3D objects
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        // Check for GL errors
        ShaderUtil.checkGLError(TAG, "Draw")
    }

    /**
     * Get the camera texture ID
     */
    fun getTextureId(): Int {
        return cameraTextureId
    }
} 