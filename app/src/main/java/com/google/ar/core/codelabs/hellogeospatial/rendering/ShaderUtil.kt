package com.google.ar.core.codelabs.hellogeospatial.rendering

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Shader helper functions.
 */
object ShaderUtil {
    /**
     * Tag for logging.
     */
    private const val TAG = "ShaderUtil"

    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     *
     * @param label Label to report in case of error.
     */
    fun checkGLError(tag: String, label: String) {
        var lastError = GLES20.GL_NO_ERROR
        // Drain the queue of all errors.
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(tag, "$label: glError $error")
            lastError = error
        }
        if (lastError != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$label: glError $lastError")
        }
    }

    /**
     * Converts a raw shader file into a string.
     *
     * @param filename the resource ID of the raw text file containing the shader.
     * @return The shader source code as a string.
     */
    @Throws(IOException::class)
    fun readRawTextFileFromAssets(context: Context, filename: String): String {
        InputStreamReader(context.assets.open(filename)).use { inputStreamReader ->
            BufferedReader(inputStreamReader).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                    sb.append("\n")
                }
                return sb.toString()
            }
        }
    }

    /**
     * Creates a shader program from a string source.
     *
     * @param vertexSource The vertex shader source.
     * @param fragmentSource The fragment shader source.
     * @return The shader program ID.
     */
    @Throws(IOException::class)
    fun createProgram(
        vertexSource: String,
        fragmentSource: String
    ): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("Failed to create program")
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: $info")
        }
        return program
    }

    /**
     * Loads a shader from source code.
     *
     * @param type The shader type (vertex or fragment).
     * @param source The shader source code.
     * @return The shader object ID.
     */
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("Failed to create shader")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type:\n$info")
        }
        return shader
    }
} 