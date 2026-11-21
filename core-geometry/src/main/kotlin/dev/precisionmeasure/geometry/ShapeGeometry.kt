package dev.precisionmeasure.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

data class PolygonMetrics(
    val perimeterMeters: Double,
    val areaSquareMeters: Double,
    val centroid: Vec3,
    val normal: Vec3,
    val segmentLengthsMeters: List<Double>,
)

data class BoxMetrics(
    val lengthMeters: Double,
    val widthMeters: Double,
    val heightMeters: Double,
    val footprintAreaSqMeters: Double,
    val volumeCubicMeters: Double,
    val corners: List<Vec3>, // 8 corners: 0..3 bottom loop, 4..7 top loop
)

data class CircleMetrics(
    val center: Vec3,
    val normal: Vec3,
    val radiusMeters: Double,
    val diameterMeters: Double,
    val circumferenceMeters: Double,
    val areaSquareMeters: Double,
)

/**
 * Pure 3D computational geometry for polylines, polygons (perimeter + area on arbitrary 3D planes),
 * boxes (L x W x H + volume), 3-point angles, vertical heights, and 3-point circles.
 */
object ShapeGeometry {

    fun computeSegmentLengths(points: List<Vec3>, closed: Boolean, scaleFactor: Double = 1.0): List<Double> {
        require(scaleFactor > 0.0 && scaleFactor.isFinite()) { "scaleFactor must be positive: $scaleFactor" }
        if (points.size < 2) return emptyList()
        val count = if (closed && points.size >= 3) points.size else points.size - 1
        return List(count) { i ->
            val a = points[i]
            val b = points[(i + 1) % points.size]
            a.distanceTo(b) * scaleFactor
        }
    }

    fun computePolylineLength(points: List<Vec3>, closed: Boolean, scaleFactor: Double = 1.0): Double =
        computeSegmentLengths(points, closed, scaleFactor).sum()

    /**
     * Computes exact 3D perimeter and planar-projected area for a closed polygon on any oriented
     * 3D plane using Newell's method for the best-fit normal and 2D shoelace area in that plane.
     */
    fun computePolygonMetrics(points: List<Vec3>, scaleFactor: Double = 1.0): PolygonMetrics {
        require(points.size >= 3) { "Polygon requires at least 3 vertices, got ${points.size}" }
        require(scaleFactor > 0.0 && scaleFactor.isFinite()) { "scaleFactor must be positive: $scaleFactor" }

        val segments = computeSegmentLengths(points, closed = true, scaleFactor = scaleFactor)
        val perimeter = segments.sum()

        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        for (p in points) {
            cx += p.x
            cy += p.y
            cz += p.z
        }
        val centroid = Vec3(cx / points.size, cy / points.size, cz / points.size)

        // Newell's normal vector (robust for arbitrary tilted non-planar slightly noisy 3D polygons).
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
        for (i in points.indices) {
            val cur = points[i] - centroid
            val nxt = points[(i + 1) % points.size] - centroid
            nx += (cur.y - nxt.y) * (cur.z + nxt.z)
            ny += (cur.z - nxt.z) * (cur.x + nxt.x)
            nz += (cur.x - nxt.x) * (cur.y + nxt.y)
        }
        val nVec = Vec3(nx, ny, nz)
        val normal = if (nVec.norm() > 1e-10) nVec.normalized() else Vec3.UNIT_Y

        // Orthonormal basis (u, v) on the polygon plane.
        val ref = if (abs(normal.y) < 0.9) Vec3.UNIT_Y else Vec3.UNIT_X
        val uAxis = normal.cross(ref).normalized()
        val vAxis = normal.cross(uAxis).normalized()

        // 2D Shoelace area on the fitted plane.
        var signedArea2 = 0.0
        for (i in points.indices) {
            val d0 = points[i] - centroid
            val d1 = points[(i + 1) % points.size] - centroid
            val x0 = d0.dot(uAxis)
            val y0 = d0.dot(vAxis)
            val x1 = d1.dot(uAxis)
            val y1 = d1.dot(vAxis)
            signedArea2 += (x0 * y1 - x1 * y0)
        }
        val areaSqMeters = abs(signedArea2) * 0.5 * scaleFactor * scaleFactor

        return PolygonMetrics(
            perimeterMeters = perimeter,
            areaSquareMeters = areaSqMeters,
            centroid = centroid,
            normal = normal,
            segmentLengthsMeters = segments,
        )
    }

    /**
     * Computes interior angle in degrees [0, 180] at [vertex] formed by rays vertex->pA and vertex->pC.
     */
    fun computeAngleDegrees(pA: Vec3, vertex: Vec3, pC: Vec3): Double {
        val v1 = pA - vertex
        val v2 = pC - vertex
        val n1 = v1.norm()
        val n2 = v2.norm()
        require(n1 > 1e-9 && n2 > 1e-9) { "Angle arms must be non-degenerate" }
        val cosTheta = (v1.dot(v2) / (n1 * n2)).coerceIn(-1.0, 1.0)
        return acos(cosTheta) * (180.0 / PI)
    }

    /**
     * Computes a right-rectangular 3D box from 4 picked points:
     * - p0: base corner
     * - p1: length edge endpoint (defines L vector along p0->p1)
     * - p2: width point (orthogonalized against L so footprint is a clean rectangle)
     * - p3: height point (projected onto the footprint normal, or vertical Y if collinear)
     */
    fun computeBoxMetrics(
        p0: Vec3,
        p1: Vec3,
        p2: Vec3,
        p3: Vec3,
        scaleFactor: Double = 1.0,
    ): BoxMetrics {
        require(scaleFactor > 0.0 && scaleFactor.isFinite()) { "scaleFactor must be positive: $scaleFactor" }
        val lVec = p1 - p0
        val lLen = lVec.norm()
        require(lLen > 1e-5) { "Box length edge is too short" }
        val lDir = lVec / lLen

        // Remove component along L so width is strictly orthogonal to length.
        val rawW = p2 - p1
        val wVec = rawW - lDir * rawW.dot(lDir)
        val wLen = wVec.norm()
        require(wLen > 1e-5) { "Box width edge is too short or collinear with length" }
        val wDir = wVec / wLen

        val normal = lDir.cross(wDir).let { c ->
            if (c.norm() > 1e-9) c.normalized() else Vec3.UNIT_Y
        }

        // Height along the footprint normal from the top point p3 relative to p2.
        val rawH = p3 - p2
        val signedH = rawH.dot(normal)
        val hVec = normal * signedH
        val hLen = abs(signedH)

        val b0 = p0
        val b1 = p0 + lVec
        val b2 = b1 + wVec
        val b3 = p0 + wVec

        val t0 = b0 + hVec
        val t1 = b1 + hVec
        val t2 = b2 + hVec
        val t3 = b3 + hVec

        val lengthMeters = lLen * scaleFactor
        val widthMeters = wLen * scaleFactor
        val heightMeters = hLen * scaleFactor
        val footprintArea = lengthMeters * widthMeters
        val volume = footprintArea * heightMeters

        return BoxMetrics(
            lengthMeters = lengthMeters,
            widthMeters = widthMeters,
            heightMeters = heightMeters,
            footprintAreaSqMeters = footprintArea,
            volumeCubicMeters = volume,
            corners = listOf(b0, b1, b2, b3, t0, t1, t2, t3),
        )
    }

    /**
     * Fits the unique 3D circle passing through 3 non-collinear points.
     * Returns null if points are collinear.
     */
    fun computeCircleFromThreePoints(
        p1: Vec3,
        p2: Vec3,
        p3: Vec3,
        scaleFactor: Double = 1.0,
    ): CircleMetrics? {
        require(scaleFactor > 0.0 && scaleFactor.isFinite()) { "scaleFactor must be positive: $scaleFactor" }
        val a = p2 - p1
        val b = p3 - p1
        val cross = a.cross(b)
        val crossNormSq = cross.normSquared()
        if (crossNormSq < 1e-12) return null

        val aSq = a.normSquared()
        val bSq = b.normSquared()
        // Exact 3D circumcenter offset from p1:
        // x . a = |a|^2 / 2 and x . b = |b|^2 / 2, where cross = a x b.
        val offset = ((cross.cross(a) * bSq) + (b.cross(cross) * aSq)) / (2.0 * crossNormSq)
        val center = p1 + offset
        val radiusRaw = center.distanceTo(p1)
        if (!radiusRaw.isFinite() || radiusRaw <= 1e-6) return null
        val rFinal = radiusRaw * scaleFactor
        val normal = cross / sqrt(crossNormSq)

        return CircleMetrics(
            center = center,
            normal = normal,
            radiusMeters = rFinal,
            diameterMeters = rFinal * 2.0,
            circumferenceMeters = 2.0 * PI * rFinal,
            areaSquareMeters = PI * rFinal * rFinal,
        )
    }

    fun computeVerticalHeight(basePoint: Vec3, topPoint: Vec3, scaleFactor: Double = 1.0): Double {
        require(scaleFactor > 0.0 && scaleFactor.isFinite()) { "scaleFactor must be positive: $scaleFactor" }
        return abs(topPoint.y - basePoint.y) * scaleFactor
    }
}
