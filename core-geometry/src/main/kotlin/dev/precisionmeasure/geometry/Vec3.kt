package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.sqrt

data class Vec2(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Vec2 components must be finite: ($x, $y)" }
    }

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Double) = Vec2(x * scale, y * scale)
    fun normSquared(): Double = x * x + y * y
    fun norm(): Double = sqrt(normSquared())
    fun distanceTo(other: Vec2): Double = (this - other).norm()
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "Vec3 components must be finite: ($x, $y, $z)"
        }
    }

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun times(scale: Double) = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double): Vec3 {
        require(scale != 0.0 && scale.isFinite()) { "Invalid scalar divisor: $scale" }
        return Vec3(x / scale, y / scale, z / scale)
    }

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun normSquared(): Double = x * x + y * y + z * z
    fun norm(): Double = sqrt(normSquared())
    fun distanceTo(other: Vec3): Double = (this - other).norm()

    fun normalized(): Vec3 {
        val n = norm()
        require(n > 1e-12) { "Cannot normalize near-zero vector: $this" }
        return this / n
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}

data class Mat3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double,
) {
    operator fun times(v: Vec3) = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    operator fun times(o: Mat3) = Mat3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
    )

    operator fun plus(o: Mat3) = Mat3(
        m00 + o.m00, m01 + o.m01, m02 + o.m02,
        m10 + o.m10, m11 + o.m11, m12 + o.m12,
        m20 + o.m20, m21 + o.m21, m22 + o.m22,
    )

    operator fun minus(o: Mat3) = Mat3(
        m00 - o.m00, m01 - o.m01, m02 - o.m02,
        m10 - o.m10, m11 - o.m11, m12 - o.m12,
        m20 - o.m20, m21 - o.m21, m22 - o.m22,
    )

    fun scaled(s: Double) = Mat3(
        m00 * s, m01 * s, m02 * s,
        m10 * s, m11 * s, m12 * s,
        m20 * s, m21 * s, m22 * s,
    )

    fun transpose() = Mat3(
        m00, m10, m20,
        m01, m11, m21,
        m02, m12, m22,
    )

    fun determinant(): Double =
        m00 * (m11 * m22 - m12 * m21) -
            m01 * (m10 * m22 - m12 * m20) +
            m02 * (m10 * m21 - m11 * m20)

    fun trace(): Double = m00 + m11 + m22

    fun quadraticForm(v: Vec3): Double = v.dot(this * v)

    fun inverse(): Mat3 {
        val c00 = m11 * m22 - m12 * m21
        val c01 = -(m10 * m22 - m12 * m20)
        val c02 = m10 * m21 - m11 * m20

        val c10 = -(m01 * m22 - m02 * m21)
        val c11 = m00 * m22 - m02 * m20
        val c12 = -(m00 * m21 - m01 * m20)

        val c20 = m01 * m12 - m02 * m11
        val c21 = -(m00 * m12 - m02 * m10)
        val c22 = m00 * m11 - m01 * m10

        val det = m00 * c00 + m01 * c01 + m02 * c02
        require(abs(det) > 1e-15 && det.isFinite()) { "Matrix is singular (det=$det)" }
        val invDet = 1.0 / det
        return Mat3(
            c00 * invDet, c10 * invDet, c20 * invDet,
            c01 * invDet, c11 * invDet, c21 * invDet,
            c02 * invDet, c12 * invDet, c22 * invDet,
        )
    }

    /** Solves A * x = b using Gaussian elimination with partial pivoting. */
    fun solve(b: Vec3): Vec3 {
        val a = arrayOf(
            doubleArrayOf(m00, m01, m02, b.x),
            doubleArrayOf(m10, m11, m12, b.y),
            doubleArrayOf(m20, m21, m22, b.z),
        )
        for (col in 0 until 3) {
            var pivotRow = col
            var maxAbs = abs(a[col][col])
            for (row in col + 1 until 3) {
                val candidate = abs(a[row][col])
                if (candidate > maxAbs) {
                    maxAbs = candidate
                    pivotRow = row
                }
            }
            require(maxAbs > 1e-14) { "Singular 3x3 linear system at column $col" }
            if (pivotRow != col) {
                val tmp = a[col]
                a[col] = a[pivotRow]
                a[pivotRow] = tmp
            }
            val pivot = a[col][col]
            for (row in col + 1 until 3) {
                val factor = a[row][col] / pivot
                for (k in col until 4) {
                    a[row][k] -= factor * a[col][k]
                }
            }
        }
        val x2 = a[2][3] / a[2][2]
        val x1 = (a[1][3] - a[1][2] * x2) / a[1][1]
        val x0 = (a[0][3] - a[0][1] * x1 - a[0][2] * x2) / a[0][0]
        return Vec3(x0, x1, x2)
    }

    companion object {
        val ZERO = Mat3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val IDENTITY = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun diagonal(d0: Double, d1: Double, d2: Double) = Mat3(
            d0, 0.0, 0.0,
            0.0, d1, 0.0,
            0.0, 0.0, d2,
        )

        fun outer(a: Vec3, b: Vec3) = Mat3(
            a.x * b.x, a.x * b.y, a.x * b.z,
            a.y * b.x, a.y * b.y, a.y * b.z,
            a.z * b.x, a.z * b.y, a.z * b.z,
        )

        fun fromColumns(c0: Vec3, c1: Vec3, c2: Vec3) = Mat3(
            c0.x, c1.x, c2.x,
            c0.y, c1.y, c2.y,
            c0.z, c1.z, c2.z,
        )
    }
}
