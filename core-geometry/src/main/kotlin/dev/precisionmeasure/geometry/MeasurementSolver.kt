package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class MeasurementQuality {
    LOCKED,
    GOOD,
    NEEDS_MORE_PARALLAX,
}

data class MeasurementResult(
    val pointA: Point3DResult,
    val pointB: Point3DResult,
    val calibration: ScaleCalibration,
    val distanceMeters: Double,
    val horizontalMeters: Double,
    val verticalMeters: Double,
    val sigmaDistanceMeters: Double,
    val ci95Meters: Double,
    val sigmaHorizontalMeters: Double,
    val sigmaVerticalMeters: Double,
    val minParallaxDeg: Double,
    val minBaselineMeters: Double,
    val quality: MeasurementQuality,
    val guidanceMessage: String,
)

/**
 * Propagates 3D endpoint covariances and metric scale uncertainty into Euclidean, horizontal,
 * and vertical distances with calibrated 95% confidence intervals.
 */
object MeasurementSolver {

    private const val Z_95 = 1.959963984540054

    fun solve(
        pointA: Point3DResult,
        pointB: Point3DResult,
        calibration: ScaleCalibration = ScaleCalibration.UNCALIBRATED_VIO,
        worldUp: Vec3 = Vec3.UNIT_Y,
        targetCi95Meters: Double = 0.002,
    ): MeasurementResult {
        val up = worldUp.normalized()
        val deltaRaw = pointB.position - pointA.position
        val rawDist = deltaRaw.norm()
        require(rawDist > 1e-6) { "Endpoints A and B coincide (distance=$rawDist m)" }

        val s = calibration.factor
        val sigmaS = calibration.sigmaFactor
        val covSum = pointA.covarianceMeters2 + pointB.covarianceMeters2

        // Total Euclidean distance and first-order uncertainty propagation.
        val uDir = deltaRaw / rawDist
        val geomVarTotal = max(0.0, covSum.quadraticForm(uDir))
        val sigmaDist = sqrt(s * s * geomVarTotal + (rawDist * sigmaS) * (rawDist * sigmaS))

        // Vertical component along gravity axis.
        val rawVertSigned = deltaRaw.dot(up)
        val rawVert = abs(rawVertSigned)
        val geomVarVert = max(0.0, covSum.quadraticForm(up))
        val sigmaVert = sqrt(s * s * geomVarVert + (rawVert * sigmaS) * (rawVert * sigmaS))

        // Horizontal component orthogonal to gravity.
        val deltaHoriz = deltaRaw - up * rawVertSigned
        val rawHoriz = deltaHoriz.norm()
        val sigmaHoriz = if (rawHoriz > 1e-6) {
            val hDir = deltaHoriz / rawHoriz
            val geomVarHoriz = max(0.0, covSum.quadraticForm(hDir))
            sqrt(s * s * geomVarHoriz + (rawHoriz * sigmaS) * (rawHoriz * sigmaS))
        } else {
            sqrt(s * s * max(0.0, covSum.trace() / 3.0))
        }

        val ci95 = Z_95 * sigmaDist
        val minParallax = min(pointA.maxParallaxDeg, pointB.maxParallaxDeg)
        val minBaseline = min(pointA.maxBaselineMeters, pointB.maxBaselineMeters)

        val geomCi95 = Z_95 * s * sqrt(geomVarTotal)
        val (quality, guidance) = when {
            minParallax < 8.0 || geomCi95 > targetCi95Meters * 2.5 -> {
                MeasurementQuality.NEEDS_MORE_PARALLAX to
                    "Slide phone 15-25 cm sideways (parallax ${"%.1f".format(minParallax)} deg)"
            }
            !calibration.isCalibrated && ci95 > targetCi95Meters -> {
                MeasurementQuality.GOOD to
                    "Geometry tight; scan a credit card once to remove VIO scale drift"
            }
            ci95 <= targetCi95Meters -> {
                MeasurementQuality.LOCKED to "Precision locked"
            }
            else -> {
                MeasurementQuality.GOOD to "Slide slightly further to tighten error bar"
            }
        }

        return MeasurementResult(
            pointA = pointA,
            pointB = pointB,
            calibration = calibration,
            distanceMeters = s * rawDist,
            horizontalMeters = s * rawHoriz,
            verticalMeters = s * rawVert,
            sigmaDistanceMeters = sigmaDist,
            ci95Meters = ci95,
            sigmaHorizontalMeters = sigmaHoriz,
            sigmaVerticalMeters = sigmaVert,
            minParallaxDeg = minParallax,
            minBaselineMeters = minBaseline,
            quality = quality,
            guidanceMessage = guidance,
        )
    }
}
