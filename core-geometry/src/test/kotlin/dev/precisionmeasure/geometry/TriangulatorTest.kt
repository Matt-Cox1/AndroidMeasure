package dev.precisionmeasure.geometry

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TriangulatorTest {

    private val intrinsics = CameraIntrinsics(
        fx = 1100.0,
        fy = 1100.0,
        cx = 640.0,
        cy = 360.0,
        width = 1280,
        height = 720,
        distortion = DistortionCoeffs(k1 = -0.12, k2 = 0.03),
    )

    @Test
    fun `recovers exact 3D tabletop point from noiseless lateral sweep`() {
        val target = Vec3(0.04, 0.02, -0.65)
        val observations = (-4..4).map { i ->
            val eye = Vec3(i * 0.035, 0.25, 0.0)
            val pose = CameraPose.lookAt(eye, Vec3(0.0, 0.0, -0.65))
            val px = pose.projectWorldPoint(target, intrinsics)!!
            RayObservation(pose, intrinsics, px, sigmaPx = 0.35)
        }

        val result = Triangulator.triangulate(observations)
        assertTrue(result is TriangulationResult.Success)
        val pt = (result as TriangulationResult.Success).point
        assertEquals(0.0, pt.position.distanceTo(target), 1e-6)
        assertEquals(0.0, pt.reprojectionRmsPx, 1e-5)
        assertTrue(pt.maxParallaxDeg > 20.0)
    }

    @Test
    fun `Huber refinement resists a 25-pixel outlier frame`() {
        val target = Vec3(-0.05, 0.01, -0.70)
        val rng = Random(42)
        val observations = (0 until 10).map { i ->
            val x = -0.15 + i * 0.033
            val eye = Vec3(x, 0.20, 0.0)
            val pose = CameraPose.lookAt(eye, Vec3(0.0, 0.0, -0.70))
            val truePx = pose.projectWorldPoint(target, intrinsics)!!
            val noisyPx = if (i == 5) {
                Vec2(truePx.x + 25.0, truePx.y - 18.0) // Gross tracking outlier.
            } else {
                Vec2(truePx.x + rng.nextGaussian() * 0.25, truePx.y + rng.nextGaussian() * 0.25)
            }
            RayObservation(pose, intrinsics, noisyPx, sigmaPx = 0.30)
        }

        val result = Triangulator.triangulate(observations)
        assertTrue(result is TriangulationResult.Success)
        val pt = (result as TriangulationResult.Success).point
        val errorMm = pt.position.distanceTo(target) * 1000.0
        assertTrue(errorMm < 1.0, "Expected < 1 mm error despite outlier, got $errorMm mm")
        assertEquals(9, pt.inlierCount)
    }

    @Test
    fun `rejects stationary camera with insufficient parallax`() {
        val target = Vec3(0.0, 0.0, -0.80)
        val pose0 = CameraPose.lookAt(Vec3(0.0, 0.2, 0.0), target)
        val pose1 = CameraPose.lookAt(Vec3(0.002, 0.2, 0.0), target) // 2 mm baseline (~0.14 deg)
        val obs = listOf(
            RayObservation(pose0, intrinsics, pose0.projectWorldPoint(target, intrinsics)!!),
            RayObservation(pose1, intrinsics, pose1.projectWorldPoint(target, intrinsics)!!),
        )

        val result = Triangulator.triangulate(obs)
        assertTrue(result is TriangulationResult.InsufficientGeometry)
    }

    @Test
    fun `reported covariance matches empirical Monte Carlo distance errors`() {
        val pointA = Vec3(-0.075, 0.0, -0.65)
        val pointB = Vec3(0.075, 0.0, -0.65)
        val trueDistance = pointA.distanceTo(pointB)
        val sigmaPx = 0.40
        val poses = (0 until 8).map { i ->
            CameraPose.lookAt(Vec3(-0.14 + i * 0.04, 0.22, 0.0), Vec3(0.0, 0.0, -0.65))
        }

        val rng = Random(12345)
        val trials = 120
        var inside95Count = 0
        val exactScale = ScaleCalibration(1.0, 0.0, "exact", isCalibrated = true)

        repeat(trials) {
            val obsA = poses.map { pose ->
                val px = pose.projectWorldPoint(pointA, intrinsics)!!
                RayObservation(
                    pose,
                    intrinsics,
                    Vec2(px.x + rng.nextGaussian() * sigmaPx, px.y + rng.nextGaussian() * sigmaPx),
                    sigmaPx,
                )
            }
            val obsB = poses.map { pose ->
                val px = pose.projectWorldPoint(pointB, intrinsics)!!
                RayObservation(
                    pose,
                    intrinsics,
                    Vec2(px.x + rng.nextGaussian() * sigmaPx, px.y + rng.nextGaussian() * sigmaPx),
                    sigmaPx,
                )
            }

            val resA = (Triangulator.triangulate(obsA) as TriangulationResult.Success).point
            val resB = (Triangulator.triangulate(obsB) as TriangulationResult.Success).point
            val meas = MeasurementSolver.solve(resA, resB, exactScale)

            if (kotlin.math.abs(meas.distanceMeters - trueDistance) <= meas.ci95Meters) {
                inside95Count++
            }
        }

        val coverage = inside95Count.toDouble() / trials
        assertTrue(coverage >= 0.88, "Expected ~95% empirical coverage (conservative), got ${coverage * 100}%")
    }
}
