package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SE(3) camera-to-world pose in standard vision camera coordinates (+X right, +Y down, +Z forward).
 *
 * For a 3D point p_cam in camera coordinates:
 *   p_world = position + rotation * p_cam
 *   p_cam   = rotation^T * (p_world - position)
 */
data class CameraPose(
    val rotation: Mat3,
    val position: Vec3,
) {
    private val rotationTranspose: Mat3 = rotation.transpose()

    init {
        val det = rotation.determinant()
        require(abs(det - 1.0) < 1e-3) { "Camera rotation must be proper SO(3) (det=$det)" }
    }

    fun cameraToWorld(pointCamera: Vec3): Vec3 = position + (rotation * pointCamera)

    fun worldToCamera(pointWorld: Vec3): Vec3 = rotationTranspose * (pointWorld - position)

    fun cameraDirectionToWorld(directionCamera: Vec3): Vec3 = rotation * directionCamera

    fun worldDirectionToCamera(directionWorld: Vec3): Vec3 = rotationTranspose * directionWorld

    fun projectWorldPoint(pointWorld: Vec3, intrinsics: CameraIntrinsics): Vec2? =
        intrinsics.project(worldToCamera(pointWorld))

    companion object {
        private val GL_TO_VISION_FLIP = Mat3.diagonal(1.0, -1.0, -1.0)

        private fun rotationFromQuaternion(qx: Double, qy: Double, qz: Double, qw: Double): Mat3 {
            val norm = sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
            require(norm > 1e-9 && norm.isFinite()) { "Invalid quaternion norm: $norm" }
            val x = qx / norm
            val y = qy / norm
            val z = qz / norm
            val w = qw / norm

            val xx = x * x
            val yy = y * y
            val zz = z * z
            val xy = x * y
            val xz = x * z
            val yz = y * z
            val wx = w * x
            val wy = w * y
            val wz = w * z

            return Mat3(
                1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy),
                2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx),
                2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy),
            )
        }

        /**
         * Creates a CameraPose from an ARCore camera pose (OpenGL convention: +X right, +Y up, -Z forward).
         * Converts the local camera basis into standard vision coordinates (+X right, +Y down, +Z forward)
         * while keeping world coordinates in ARCore's gravity-aligned Y-up world frame.
         */
        fun fromArCorePose(
            qx: Double,
            qy: Double,
            qz: Double,
            qw: Double,
            tx: Double,
            ty: Double,
            tz: Double,
        ): CameraPose {
            val rGl = rotationFromQuaternion(qx, qy, qz, qw)
            return CameraPose(
                rotation = rGl * GL_TO_VISION_FLIP,
                position = Vec3(tx, ty, tz),
            )
        }

        /**
         * Constructs a vision-convention CameraPose (+X right, +Y down, +Z forward) at [eye] looking at [target].
         */
        fun lookAt(
            eye: Vec3,
            target: Vec3,
            worldUp: Vec3 = Vec3.UNIT_Y,
        ): CameraPose {
            val zAxis = (target - eye).normalized()
            val xAxis = zAxis.cross(worldUp).normalized()
            val yAxis = zAxis.cross(xAxis).normalized()
            return CameraPose(
                rotation = Mat3.fromColumns(xAxis, yAxis, zAxis),
                position = eye,
            )
        }
    }
}
