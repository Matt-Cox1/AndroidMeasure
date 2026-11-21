package dev.precisionmeasure.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CameraIntrinsicsTest {

    @Test
    fun `project and unproject round-trip with radial and tangential distortion`() {
        val intrinsics = CameraIntrinsics(
            fx = 1050.0,
            fy = 1048.5,
            cx = 640.0,
            cy = 360.0,
            width = 1280,
            height = 720,
            distortion = DistortionCoeffs(
                k1 = -0.18,
                k2 = 0.06,
                p1 = 0.0012,
                p2 = -0.0009,
                k3 = -0.015,
            ),
        )

        val points = listOf(
            Vec3(0.0, 0.0, 0.8),
            Vec3(0.22, -0.14, 0.75),
            Vec3(-0.31, 0.18, 0.90),
            Vec3(0.15, 0.12, 0.45),
        )

        for (p in points) {
            val px = intrinsics.project(p)!!
            val norm = intrinsics.unprojectToNormalized(px)
            assertEquals(p.x / p.z, norm.x, 1e-7)
            assertEquals(p.y / p.z, norm.y, 1e-7)
        }
    }

    @Test
    fun `project returns null for point behind camera`() {
        val intrinsics = CameraIntrinsics(1000.0, 1000.0, 640.0, 360.0, 1280, 720)
        assertNull(intrinsics.project(Vec3(0.1, 0.2, -0.5)))
        assertNull(intrinsics.project(Vec3(0.0, 0.0, 0.0)))
    }

    @Test
    fun `fromArCorePose converts OpenGL camera frame to vision camera frame`() {
        // Identity ARCore pose at (0, 1, 0): GL camera looks along world -Z with +Y up.
        val pose = CameraPose.fromArCorePose(
            qx = 0.0,
            qy = 0.0,
            qz = 0.0,
            qw = 1.0,
            tx = 0.0,
            ty = 1.0,
            tz = 0.0,
        )

        // A world point 0.8m ahead (-Z), 0.1m right (+X), 0.2m below camera (y=0.8)
        // must map to vision camera coordinates (+0.1 right, +0.2 down, +0.8 forward).
        val pWorld = Vec3(0.1, 0.8, -0.8)
        val pCam = pose.worldToCamera(pWorld)
        assertEquals(0.1, pCam.x, 1e-9)
        assertEquals(0.2, pCam.y, 1e-9)
        assertEquals(0.8, pCam.z, 1e-9)

        val roundTrip = pose.cameraToWorld(pCam)
        assertEquals(pWorld.x, roundTrip.x, 1e-9)
        assertEquals(pWorld.y, roundTrip.y, 1e-9)
        assertEquals(pWorld.z, roundTrip.z, 1e-9)
    }
}
