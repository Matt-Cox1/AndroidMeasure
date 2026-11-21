package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Standard everyday objects with tight manufacturing tolerances for metric scale calibration.
 */
enum class ReferenceObject(
    val displayName: String,
    val lengthMeters: Double,
    val secondaryLengthMeters: Double? = null,
) {
    CREDIT_CARD("Credit Card (ISO ID-1)", 0.08560, 0.05398),
    US_QUARTER("US Quarter (diameter)", 0.02426),
    EURO_ONE("1 Euro Coin (diameter)", 0.02325),
    UK_ONE_POUND("UK £1 Coin (diameter)", 0.02343),
    A4_SHORT_EDGE("A4 Paper (short edge)", 0.2100, 0.2970),
    US_LETTER_SHORT_EDGE("US Letter (short edge)", 0.2159, 0.2794),
}

/**
 * Multiplicative metric scale factor applied to raw VIO coordinates:
 *   d_calibrated = factor * d_raw
 */
data class ScaleCalibration(
    val factor: Double,
    val sigmaFactor: Double,
    val label: String,
    val isCalibrated: Boolean,
) {
    init {
        require(factor in 0.5..2.0 && factor.isFinite()) { "Scale factor out of valid bounds: $factor" }
        require(sigmaFactor >= 0.0 && sigmaFactor.isFinite()) { "sigmaFactor must be non-negative: $sigmaFactor" }
    }

    companion object {
        /**
         * Honest default VIO scale prior: ARCore VIO typically holds scale within ~1.5% (1-sigma)
         * on handheld tabletop sweeps without LiDAR or external scale reference.
         */
        val UNCALIBRATED_VIO = ScaleCalibration(
            factor = 1.0,
            sigmaFactor = 0.015,
            label = "ARCore VIO (uncalibrated)",
            isCalibrated = false,
        )

        private const val MIN_VALID_SCALE = 0.75
        private const val MAX_VALID_SCALE = 1.30
        private const val PHYSICAL_REFERENCE_REL_TOLERANCE = 0.0005

        /**
         * Solves scale correction from a single known length (e.g., long edge of a credit card).
         * Fails fast if the implied scale is physically implausible.
         */
        fun fromTwoPointReference(
            trueLengthMeters: Double,
            measuredRawMeters: Double,
            measuredSigmaMeters: Double,
            label: String,
        ): ScaleCalibration {
            require(trueLengthMeters >= 0.01 && trueLengthMeters.isFinite()) {
                "Reference length must be at least 10 mm (got $trueLengthMeters m)"
            }
            require(measuredRawMeters >= 0.005 && measuredRawMeters.isFinite()) {
                "Measured length must be at least 5 mm (got $measuredRawMeters m)"
            }
            require(measuredSigmaMeters >= 0.0 && measuredSigmaMeters.isFinite()) {
                "Invalid measuredSigmaMeters: $measuredSigmaMeters"
            }

            val s = trueLengthMeters / measuredRawMeters
            require(s in MIN_VALID_SCALE..MAX_VALID_SCALE) {
                "Implied scale factor ${"%.3f".format(s)}x is outside [$MIN_VALID_SCALE, $MAX_VALID_SCALE]. " +
                    "Verify the chosen reference object and endpoints."
            }

            val relSigmaMeas = measuredSigmaMeters / measuredRawMeters
            val relSigma = sqrt(relSigmaMeas * relSigmaMeas + PHYSICAL_REFERENCE_REL_TOLERANCE * PHYSICAL_REFERENCE_REL_TOLERANCE)
            return ScaleCalibration(
                factor = s,
                sigmaFactor = s * relSigma,
                label = label,
                isCalibrated = true,
            )
        }

        /**
         * Solves scale correction from both width and height of a rectangular reference (e.g., credit card).
         * Validates the measured aspect ratio against the known card aspect ratio to catch bad clicks.
         */
        fun fromRectangularReference(
            trueWidthMeters: Double,
            trueHeightMeters: Double,
            measuredWidthMeters: Double,
            measuredHeightMeters: Double,
            sigmaWidthMeters: Double,
            sigmaHeightMeters: Double,
            label: String,
            maxAspectRatioError: Double = 0.035,
        ): ScaleCalibration {
            val sW = fromTwoPointReference(trueWidthMeters, measuredWidthMeters, sigmaWidthMeters, label)
            val sH = fromTwoPointReference(trueHeightMeters, measuredHeightMeters, sigmaHeightMeters, label)

            val trueAspect = trueWidthMeters / trueHeightMeters
            val measAspect = measuredWidthMeters / measuredHeightMeters
            val relAspectErr = abs(measAspect - trueAspect) / trueAspect
            require(relAspectErr <= maxAspectRatioError) {
                "Measured aspect ratio ${"%.3f".format(measAspect)} does not match reference " +
                    "${"%.3f".format(trueAspect)} (error ${"%.1f".format(relAspectErr * 100)}%)"
            }

            // Inverse-variance weighted fusion of the two orthogonal scale estimates.
            val w1 = 1.0 / (sW.sigmaFactor * sW.sigmaFactor).coerceAtLeast(1e-10)
            val w2 = 1.0 / (sH.sigmaFactor * sH.sigmaFactor).coerceAtLeast(1e-10)
            val fusedFactor = (w1 * sW.factor + w2 * sH.factor) / (w1 + w2)
            val fusedSigma = sqrt(1.0 / (w1 + w2))
            return ScaleCalibration(
                factor = fusedFactor,
                sigmaFactor = fusedSigma,
                label = label,
                isCalibrated = true,
            )
        }
    }
}
