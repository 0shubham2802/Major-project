package com.google.ar.core.codelabs.hellogeospatial.helpers

import kotlin.math.*

data class Quaternion(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var w: Float = 1f
) {
    fun length(): Float {
        return sqrt(x * x + y * y + z * z + w * w)
    }

    fun normalize() {
        val len = length()
        if (len > 0) {
            x /= len
            y /= len
            z /= len
            w /= len
        }
    }

    fun conjugate(): Quaternion {
        return Quaternion(-x, -y, -z, w)
    }

    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z
        )
    }

    fun toEulerAngles(): Vector3 {
        val angles = Vector3()

        // Roll (x-axis rotation)
        val sinr_cosp = 2 * (w * x + y * z)
        val cosr_cosp = 1 - 2 * (x * x + y * y)
        angles.x = atan2(sinr_cosp, cosr_cosp)

        // Pitch (y-axis rotation)
        val sinp = 2 * (w * y - z * x)
        angles.y = if (abs(sinp) >= 1)
            (PI.toFloat() / 2) * sign(sinp) // Use 90 degrees if out of range
        else
            asin(sinp)

        // Yaw (z-axis rotation)
        val siny_cosp = 2 * (w * z + x * y)
        val cosy_cosp = 1 - 2 * (y * y + z * z)
        angles.z = atan2(siny_cosp, cosy_cosp)

        return angles
    }

    companion object {
        fun fromEulerAngles(x: Float, y: Float, z: Float): Quaternion {
            val cx = cos(x * 0.5f)
            val sx = sin(x * 0.5f)
            val cy = cos(y * 0.5f)
            val sy = sin(y * 0.5f)
            val cz = cos(z * 0.5f)
            val sz = sin(z * 0.5f)

            return Quaternion(
                sx * cy * cz - cx * sy * sz,
                cx * sy * cz + sx * cy * sz,
                cx * cy * sz - sx * sy * cz,
                cx * cy * cz + sx * sy * sz
            )
        }

        fun fromAxisAngle(axis: Vector3, angle: Float): Quaternion {
            val normalizedAxis = axis.also { it.normalize() }
            val sinHalfAngle = sin(angle * 0.5f)
            return Quaternion(
                normalizedAxis.x * sinHalfAngle,
                normalizedAxis.y * sinHalfAngle,
                normalizedAxis.z * sinHalfAngle,
                cos(angle * 0.5f)
            )
        }

        val identity = Quaternion(0f, 0f, 0f, 1f)
    }
} 