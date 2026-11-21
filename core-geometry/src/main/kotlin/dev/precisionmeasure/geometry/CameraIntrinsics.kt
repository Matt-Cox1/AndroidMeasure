package dev.precisionmeasure.geometry

import kotlin.math.abs

/**
 * Brown-Conrady radial (k1, k2, k3) and tangential (p1, p2) lens distortion coefficients.
 */
data class DistortionCoeffs(
    val k1: Double = 0.0,
    val k2: Double = 0.0,
    val p1: Double = 0.0,
    val p2: Double = 0.0,
    val k3: Double = 0.0,
) {
    val isZero: Boolean
        get() = k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0

    fun distortNormalized(xn: Double, yn: Double): Vec2 {
        if (isZero) return Vec2(xn, yn)
        val r2 = xn * xn + yn * yn
        val r4 = r2 * r2
        val r6 = r4 * r2
        val radial = 1.0 + k1 * r2 + k2 * r4 + k3 * r6
        val dx = 2.0 * p1 * xn * yn + p2 * (r2 + 2.0 * xn * xn)
        val dy = p1 * (r2 + 2.0 * yn * yn) + 2.0 * p2 * xn * yn
        return Vec2(xn * radial + dx, yn * radial + dy)
    }

    /**
     * Inverts Brown-Conrady distortion using 2D Newton-Raphson iteration.
     */
    fun undistortNormalized(xd: Double, yd: Double, maxIterations: Int = 20): Vec2 {
        if (isZero) return Vec2(xd, yd)
        var x = xd
        var y = yd
        for (iter in 0 until maxIterations) {
            val r2 = x * x + y * y
            val r4 = r2 * r2
            val r6 = r4 * r2
            val radial = 1.0 + k1 * r2 + k2 * r4 + k3 * r6
            val dRadialDr2 = k1 + 2.0 * k2 * r2 + 3.0 * k3 * r4

            val fx = x * radial + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x) - xd
            val fy = y * radial + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y - yd

            if (fx * fx + fy * fy < 1e-24) {
                return Vec2(x, y)
            }

            val j00 = radial + 2.0 * x * x * dRadialDr2 + 2.0 * p1 * y + 6.0 * p2 * x
            val j01 = 2.0 * x * y * dRadialDr2 + 2.0 * p1 * x + 2.0 * p2 * y
            val j10 = j01
            val j11 = radial + 2.0 * y * y * dRadialDr2 + 6.0 * p1 * y + 2.0 * p2 * x

            val det = j00 * j11 - j01 * j10
            require(abs(det) > 1e-12) { "Distortion Jacobian became singular during undistortion" }

            val dx = (j11 * fx - j01 * fy) / det
            val dy = (-j10 * fx + j00 * fy) / det
            x -= dx
            y -= dy

            if (dx * dx + dy * dy < 1e-24) {
                return Vec2(x, y)
            }
        }
        return Vec2(x, y)
    }

    companion object {
        val ZERO = DistortionCoeffs()
    }
}

/**
 * Pinhole camera model in image-camera coordinates (+X right, +Y down, +Z forward).
 */
data class CameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val width: Int,
    val height: Int,
    val distortion: DistortionCoeffs = DistortionCoeffs.ZERO,
) {
    init {
        require(fx > 0.0 && fy > 0.0 && fx.isFinite() && fy.isFinite()) {
            "Focal lengths must be positive and finite: fx=$fx, fy=$fy"
        }
        require(cx.isFinite() && cy.isFinite()) {
            "Principal point must be finite: cx=$cx, cy=$cy"
        }
        require(width > 0 && height > 0) {
            "Image dimensions must be positive: ${width}x$height"
        }
    }

    fun isInsideImage(pixel: Vec2, marginPx: Double = 0.0): Boolean =
        pixel.x >= marginPx && pixel.x <= (width - 1.0 - marginPx) &&
            pixel.y >= marginPx && pixel.y <= (height - 1.0 - marginPx)

    /**
     * Projects a 3D point in image-camera coordinates onto distorted pixel coordinates.
     * Returns null if the point is behind or on the camera plane.
     */
    fun project(pointCamera: Vec3): Vec2? {
        if (pointCamera.z <= 1e-6) return null
        val xn = pointCamera.x / pointCamera.z
        val yn = pointCamera.y / pointCamera.z
        val distorted = distortion.distortNormalized(xn, yn)
        return Vec2(fx * distorted.x + cx, fy * distorted.y + cy)
    }

    /**
     * Unprojects a distorted pixel coordinate to an undistorted normalized plane coordinate (x/z, y/z).
     */
    fun unprojectToNormalized(pixel: Vec2): Vec2 {
        val xd = (pixel.x - cx) / fx
        val yd = (pixel.y - cy) / fy
        return distortion.undistortNormalized(xd, yd)
    }

    /**
     * Unprojects a pixel to a unit ray in image-camera coordinates (+X right, +Y down, +Z forward).
     */
    fun unprojectRay(pixel: Vec2): Vec3 {
        val norm = unprojectToNormalized(pixel)
        return Vec3(norm.x, norm.y, 1.0).normalized()
    }
}
