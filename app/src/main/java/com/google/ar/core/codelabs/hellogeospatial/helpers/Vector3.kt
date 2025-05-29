package com.google.ar.core.codelabs.hellogeospatial.helpers

import kotlin.math.sqrt

data class Vector3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    fun length(): Float {
        return sqrt(x * x + y * y + z * z)
    }

    fun normalize() {
        val len = length()
        if (len > 0) {
            x /= len
            y /= len
            z /= len
        }
    }

    operator fun plus(other: Vector3): Vector3 {
        return Vector3(x + other.x, y + other.y, z + other.z)
    }

    operator fun minus(other: Vector3): Vector3 {
        return Vector3(x - other.x, y - other.y, z - other.z)
    }

    operator fun times(scalar: Float): Vector3 {
        return Vector3(x * scalar, y * scalar, z * scalar)
    }

    operator fun div(scalar: Float): Vector3 {
        return Vector3(x / scalar, y / scalar, z / scalar)
    }

    fun dot(other: Vector3): Float {
        return x * other.x + y * other.y + z * other.z
    }

    fun cross(other: Vector3): Vector3 {
        return Vector3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x
        )
    }

    companion object {
        val zero = Vector3(0f, 0f, 0f)
        val up = Vector3(0f, 1f, 0f)
        val forward = Vector3(0f, 0f, 1f)
        val right = Vector3(1f, 0f, 0f)
    }
} 