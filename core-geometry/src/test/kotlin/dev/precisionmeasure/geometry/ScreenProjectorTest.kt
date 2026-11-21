package dev.precisionmeasure.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScreenProjectorTest {

    // Simple perspective projection matrix looking down -Z (OpenGL convention), 90 deg FOV, square aspect.
    private val simpleProj = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, -1.02f, -1f,
        0f, 0f, -0.2f, 0f,
    )

    @Test
    fun `projects center point to center of viewport`() {
        val px = ScreenProjector.projectPoint(simpleProj, Vec3(0.0, 0.0, -2.0), 1080, 2400)
        assertNotNull(px)
        assertEquals(540.0, px!!.x, 1e-3)
        assertEquals(1200.0, px.y, 1e-3)
    }

    @Test
    fun `clips segment smoothly when one endpoint moves behind camera`() {
        val seg = ScreenProjector.projectSegmentClipped(
            simpleProj,
            Vec3(0.0, 0.0, -1.0),
            Vec3(0.5, 0.0, +0.5), // Behind camera
            1000,
            1000,
        )
        assertNotNull(seg)
        assertEquals(500.0, seg!!.first.x, 1e-2)
    }

    @Test
    fun `returns null when both endpoints are behind camera`() {
        val seg = ScreenProjector.projectSegmentClipped(
            simpleProj,
            Vec3(0.0, 0.0, +0.5),
            Vec3(0.2, 0.0, +1.0),
            1000,
            1000,
        )
        assertNull(seg)
    }
}
