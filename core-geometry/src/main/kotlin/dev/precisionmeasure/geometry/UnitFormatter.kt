package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

enum class UnitSystem(val shortLabel: String, val displayName: String, val isImperial: Boolean) {
    AUTO_METRIC("AUTO", "Metric (Auto mm/cm/m)", false),
    MM("mm", "Millimeters (mm)", false),
    CM("cm", "Centimeters (cm)", false),
    M("m", "Meters (m)", false),
    IN_DECIMAL("in", "Inches (Decimal)", true),
    IN_FRACTION("in 1/16", "Inches (Fractional 1/16\")", true),
    FT_IN("ft+in", "Feet & Inches", true);

    fun nextQuick(): UnitSystem = when (this) {
        AUTO_METRIC -> MM
        MM -> CM
        CM -> IN_FRACTION
        IN_FRACTION -> IN_DECIMAL
        IN_DECIMAL -> FT_IN
        FT_IN -> AUTO_METRIC
        M -> AUTO_METRIC
    }
}

/**
 * Formats lengths, uncertainties, areas, volumes, and angles consistently across
 * Metric and Imperial (decimal + carpenter fractional inches) systems.
 */
object UnitFormatter {

    private const val METERS_TO_INCHES = 39.37007874015748
    private const val SQ_METERS_TO_SQ_INCHES = 1550.0031000062
    private const val SQ_METERS_TO_SQ_FEET = 10.763910416709722
    private const val CU_METERS_TO_CU_INCHES = 61023.74409473228
    private const val CU_METERS_TO_CU_FEET = 35.31466672148859

    fun formatLength(
        meters: Double,
        unit: UnitSystem,
        decimals: Int = 1,
    ): String {
        val d = decimals.coerceIn(0, 3)
        val absM = abs(meters)
        return when (unit) {
            UnitSystem.AUTO_METRIC -> when {
                absM < 0.10 -> "%.${d}f mm".format(meters * 1000.0)
                absM < 1.0 -> "%.${maxOf(1, d)}f cm".format(meters * 100.0)
                else -> "%.${maxOf(2, d)}f m".format(meters)
            }
            UnitSystem.MM -> "%.${d}f mm".format(meters * 1000.0)
            UnitSystem.CM -> "%.${maxOf(1, d)}f cm".format(meters * 100.0)
            UnitSystem.M -> "%.${maxOf(2, d)}f m".format(meters)
            UnitSystem.IN_DECIMAL -> "%.${maxOf(2, d)}f in".format(meters * METERS_TO_INCHES)
            UnitSystem.IN_FRACTION -> formatFractionalInches(meters * METERS_TO_INCHES, denominator = 16)
            UnitSystem.FT_IN -> formatFeetAndInches(meters * METERS_TO_INCHES, denominator = 16)
        }
    }

    fun formatUncertainty(
        meters: Double,
        unit: UnitSystem,
        decimals: Int = 1,
    ): String = "± ${formatLength(abs(meters), unit, decimals)}"

    fun formatArea(sqMeters: Double, unit: UnitSystem): String {
        val absA = abs(sqMeters)
        return if (unit.isImperial) {
            val sqIn = absA * SQ_METERS_TO_SQ_INCHES
            if (sqIn < 144.0) {
                "%.1f in²".format(sqIn)
            } else {
                "%.2f ft²".format(absA * SQ_METERS_TO_SQ_FEET)
            }
        } else {
            val sqCm = absA * 10_000.0
            if (sqCm < 10_000.0) {
                "%.1f cm²".format(sqCm)
            } else {
                "%.3f m²".format(absA)
            }
        }
    }

    fun formatVolume(cubicMeters: Double, unit: UnitSystem): String {
        val absV = abs(cubicMeters)
        return if (unit.isImperial) {
            val cuIn = absV * CU_METERS_TO_CU_INCHES
            if (cuIn < 1728.0) {
                "%.1f in³".format(cuIn)
            } else {
                "%.2f ft³".format(absV * CU_METERS_TO_CU_FEET)
            }
        } else {
            val liters = absV * 1000.0
            if (liters < 1.0) {
                "%.0f cm³".format(absV * 1_000_000.0)
            } else {
                "%.2f L".format(liters)
            }
        }
    }

    fun formatAngle(degrees: Double): String = "%.1f°".format(degrees)

    internal fun formatFractionalInches(inches: Double, denominator: Int = 16): String {
        val sign = if (inches < 0.0) "-" else ""
        val totalSixteenths = (abs(inches) * denominator).roundToInt()
        val whole = totalSixteenths / denominator
        val rem = totalSixteenths % denominator
        if (rem == 0) {
            return "$sign$whole\""
        }
        val g = gcd(rem, denominator)
        val num = rem / g
        val den = denominator / g
        return if (whole == 0) {
            "$sign$num/$den\""
        } else {
            "$sign$whole $num/$den\""
        }
    }

    internal fun formatFeetAndInches(inches: Double, denominator: Int = 16): String {
        val sign = if (inches < 0.0) "-" else ""
        val totalSixteenths = (abs(inches) * denominator).roundToInt()
        val totalInchesInt = totalSixteenths / denominator
        val feet = floor(totalInchesInt / 12.0).toInt()
        val remInches = (totalSixteenths - feet * 12 * denominator) / denominator.toDouble()
        val inchStr = formatFractionalInches(remInches, denominator)
        return if (feet == 0) {
            "$sign$inchStr"
        } else {
            "$sign$feet' $inchStr"
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return maxOf(1, x)
    }
}
