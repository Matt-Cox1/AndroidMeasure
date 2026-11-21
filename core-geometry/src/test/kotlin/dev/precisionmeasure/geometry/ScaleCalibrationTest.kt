package dev.precisionmeasure.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScaleCalibrationTest {

    @Test
    fun `rectangular credit card calibration corrects VIO drift and shrinks uncertainty`() {
        // Suppose ARCore VIO reads 2.4% too long.
        val trueDrift = 1.024
        val trueW = ReferenceObject.CREDIT_CARD.lengthMeters
        val trueH = ReferenceObject.CREDIT_CARD.secondaryLengthMeters!!

        val cal = ScaleCalibration.fromRectangularReference(
            trueWidthMeters = trueW,
            trueHeightMeters = trueH,
            measuredWidthMeters = trueW * trueDrift,
            measuredHeightMeters = trueH * trueDrift,
            sigmaWidthMeters = 0.0003,
            sigmaHeightMeters = 0.0003,
            label = ReferenceObject.CREDIT_CARD.displayName,
        )

        assertEquals(1.0 / trueDrift, cal.factor, 1e-4)
        assertTrue(cal.sigmaFactor < 0.004)
        assertTrue(cal.isCalibrated)
    }

    @Test
    fun `rectangular calibration rejects wrong aspect ratio clicks`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScaleCalibration.fromRectangularReference(
                trueWidthMeters = 0.08560,
                trueHeightMeters = 0.05398,
                measuredWidthMeters = 0.0860,
                measuredHeightMeters = 0.0720, // Clearly not a credit card aspect ratio!
                sigmaWidthMeters = 0.0004,
                sigmaHeightMeters = 0.0004,
                label = "Bad Card",
            )
        }
    }

    @Test
    fun `two-point calibration rejects implausible scale factor`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScaleCalibration.fromTwoPointReference(
                trueLengthMeters = 0.08560,
                measuredRawMeters = 0.250, // Implies 0.34x scale -> wrong object selected.
                measuredSigmaMeters = 0.001,
                label = "Credit Card",
            )
        }
    }
}
