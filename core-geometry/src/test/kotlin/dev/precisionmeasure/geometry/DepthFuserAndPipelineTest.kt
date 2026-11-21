package dev.precisionmeasure.geometry

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DepthFuserAndPipelineTest {

    @Test
    fun `DepthFuser extracts clean median depth and rejects edge outliers`() {
        val w = 32
        val h = 32
        val depthBytes = ByteArray(w * h * 2)
        val confBytes = ByteArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                // Foreground object at 680 mm, with 4 background outlier pixels at 2200 mm.
                val mm = if (x == 18 && y in 14..17) 2200 else 680
                depthBytes[idx * 2] = (mm and 0xFF).toByte()
                depthBytes[idx * 2 + 1] = ((mm ushr 8) and 0xFF).toByte()
                confBytes[idx] = 210.toByte()
            }
        }

        val pose = CameraPose.lookAt(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))
        val prior = DepthFuser.extractDepthPrior(pose, depthBytes, confBytes, w, h, 16, 16)
        assertNotNull(prior)
        assertEquals(0.680, prior!!.depthMeters, 0.002)
    }

    @Test
    fun `DepthFuser returns null on blank low-confidence patch`() {
        val w = 16
        val h = 16
        val depthBytes = ByteArray(w * h * 2)
        val confBytes = ByteArray(w * h) { 20.toByte() }
        val pose = CameraPose.lookAt(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))
        assertNull(DepthFuser.extractDepthPrior(pose, depthBytes, confBytes, w, h, 8, 8))
    }

    @Test
    fun `end-to-end tabletop workflow achieves sub-millimeter accuracy after credit card calibration`() {
        val intrinsics = CameraIntrinsics(1150.0, 1150.0, 640.0, 360.0, 1280, 720)
        val vioScaleBias = 1.022 // ARCore reports translations 2.2% too large.
        val rng = Random(7)

        fun simulatePoint(truePoint: Vec3, posesTrue: List<CameraPose>): Point3DResult {
            val obs = posesTrue.map { truePose ->
                val pxTrue = truePose.projectWorldPoint(truePoint, intrinsics)!!
                val noisyPx = Vec2(
                    pxTrue.x + rng.nextGaussian() * 0.25,
                    pxTrue.y + rng.nextGaussian() * 0.25,
                )
                // ARCore reports scaled world positions (rotations remain accurate via gyroscope).
                val biasedPose = CameraPose(truePose.rotation, truePose.position * vioScaleBias)
                RayObservation(biasedPose, intrinsics, noisyPx, sigmaPx = 0.25)
            }
            return (Triangulator.triangulate(obs) as TriangulationResult.Success).point
        }

        val sweepPoses = (0 until 9).map { i ->
            CameraPose.lookAt(Vec3(-0.14 + i * 0.035, 0.22, 0.0), Vec3(0.0, 0.0, -0.60))
        }

        // Step 1: Measure credit card long edge (85.60 mm) in biased VIO frame.
        val cardA = simulatePoint(Vec3(-0.0428, 0.0, -0.60), sweepPoses)
        val cardB = simulatePoint(Vec3(0.0428, 0.0, -0.60), sweepPoses)
        val uncalCard = MeasurementSolver.solve(cardA, cardB, ScaleCalibration.UNCALIBRATED_VIO)

        val calibration = ScaleCalibration.fromTwoPointReference(
            trueLengthMeters = ReferenceObject.CREDIT_CARD.lengthMeters,
            measuredRawMeters = uncalCard.distanceMeters,
            measuredSigmaMeters = uncalCard.pointA.sigmaAlong(
                (cardB.position - cardA.position).normalized(),
            ) * kotlin.math.sqrt(2.0),
            label = ReferenceObject.CREDIT_CARD.displayName,
        )

        // Step 2: Measure a 150.0 mm target object with vertical + horizontal offset.
        val objA = simulatePoint(Vec3(-0.060, 0.0, -0.62), sweepPoses)
        val objB = simulatePoint(Vec3(0.060, 0.090, -0.62), sweepPoses) // 120 mm horiz, 90 mm vert -> 150.0 mm total
        val result = MeasurementSolver.solve(objA, objB, calibration)

        val errMm = kotlin.math.abs(result.distanceMeters - 0.150) * 1000.0
        assertTrue(errMm < 1.0, "Expected < 1.0 mm error after calibration, got ${"%.2f".format(errMm)} mm")
        assertEquals(0.120, result.horizontalMeters, 0.0015)
        assertEquals(0.090, result.verticalMeters, 0.0015)
        assertEquals(MeasurementQuality.LOCKED, result.quality)
    }
}
