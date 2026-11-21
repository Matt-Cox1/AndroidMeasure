package dev.precisionmeasure.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ShapeGeometryTest {

    @Test
    fun `polygon perimeter and area on horizontal plane`() {
        val pts = listOf(
            Vec3(0.0, 0.0, 0.0),
            Vec3(0.30, 0.0, 0.0),
            Vec3(0.30, 0.0, 0.20),
            Vec3(0.0, 0.0, 0.20),
        )
        val m = ShapeGeometry.computePolygonMetrics(pts, scaleFactor = 1.0)
        assertEquals(1.00, m.perimeterMeters, 1e-9)
        assertEquals(0.06, m.areaSquareMeters, 1e-9)
        assertEquals(4, m.segmentLengthsMeters.size)
    }

    @Test
    fun `polygon area on arbitrarily tilted 3D plane`() {
        // 45-degree tilted square of side 0.40 m -> area = 0.16 m^2, perimeter = 1.60 m
        val s = 0.40
        val k = s / sqrt(2.0)
        val pts = listOf(
            Vec3(1.0, 2.0, 3.0),
            Vec3(1.0 + s, 2.0, 3.0),
            Vec3(1.0 + s, 2.0 + k, 3.0 + k),
            Vec3(1.0, 2.0 + k, 3.0 + k),
        )
        val m = ShapeGeometry.computePolygonMetrics(pts, scaleFactor = 1.0)
        assertEquals(1.60, m.perimeterMeters, 1e-9)
        assertEquals(0.16, m.areaSquareMeters, 1e-9)
    }

    @Test
    fun `3D angle computes 90 and 45 degrees accurately`() {
        val a90 = ShapeGeometry.computeAngleDegrees(
            Vec3(1.0, 0.0, 0.0),
            Vec3(0.0, 0.0, 0.0),
            Vec3(0.0, 1.0, 0.0),
        )
        assertEquals(90.0, a90, 1e-9)

        val a45 = ShapeGeometry.computeAngleDegrees(
            Vec3(1.0, 0.0, 0.0),
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 1.0, 0.0),
        )
        assertEquals(45.0, a45, 1e-9)
    }

    @Test
    fun `3D box metrics compute length width height area and volume`() {
        val box = ShapeGeometry.computeBoxMetrics(
            p0 = Vec3(0.0, 0.0, 0.0),
            p1 = Vec3(0.50, 0.0, 0.0),
            p2 = Vec3(0.50, 0.0, 0.30),
            p3 = Vec3(0.50, 0.20, 0.30),
            scaleFactor = 1.0,
        )
        assertEquals(0.50, box.lengthMeters, 1e-9)
        assertEquals(0.30, box.widthMeters, 1e-9)
        assertEquals(0.20, box.heightMeters, 1e-9)
        assertEquals(0.15, box.footprintAreaSqMeters, 1e-9)
        assertEquals(0.03, box.volumeCubicMeters, 1e-9)
        assertEquals(8, box.corners.size)
    }

    @Test
    fun `3-point circle fit recovers radius and center in 3D`() {
        val r = 0.25
        val center = Vec3(1.0, -0.5, 2.0)
        val p1 = center + Vec3(r * cos(0.2), 0.0, r * sin(0.2))
        val p2 = center + Vec3(r * cos(2.1), 0.0, r * sin(2.1))
        val p3 = center + Vec3(r * cos(4.4), 0.0, r * sin(4.4))

        val c = ShapeGeometry.computeCircleFromThreePoints(p1, p2, p3)
        assertNotNull(c)
        c!!
        assertEquals(r, c.radiusMeters, 1e-7)
        assertEquals(2.0 * r, c.diameterMeters, 1e-7)
        assertEquals(2.0 * PI * r, c.circumferenceMeters, 1e-7)
        assertEquals(0.0, c.center.distanceTo(center), 1e-7)

        val collinear = ShapeGeometry.computeCircleFromThreePoints(
            Vec3(0.0, 0.0, 0.0),
            Vec3(1.0, 0.0, 0.0),
            Vec3(2.0, 0.0, 0.0),
        )
        assertNull(collinear)
    }

    @Test
    fun `unit formatter handles metric auto, fractional inches, and feet plus inches`() {
        assertEquals("85.6 mm", UnitFormatter.formatLength(0.0856, UnitSystem.AUTO_METRIC, decimals = 1))
        assertEquals("29.1 cm", UnitFormatter.formatLength(0.2906, UnitSystem.AUTO_METRIC, decimals = 1))
        assertEquals("1.45 m", UnitFormatter.formatLength(1.45, UnitSystem.AUTO_METRIC, decimals = 1))

        // 11.4375 inches = 11 7/16"
        assertEquals("11 7/16\"", UnitFormatter.formatFractionalInches(11.4375, denominator = 16))
        // 12.5 inches = 1' 1/2"
        assertEquals("1' 1/2\"", UnitFormatter.formatFeetAndInches(12.5, denominator = 16))
        // 24.0 inches = 2' 0"
        assertEquals("2' 0\"", UnitFormatter.formatFeetAndInches(24.0, denominator = 16))
    }
}
