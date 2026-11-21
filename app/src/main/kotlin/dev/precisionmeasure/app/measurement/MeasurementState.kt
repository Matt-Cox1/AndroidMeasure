package dev.precisionmeasure.app.measurement

import android.graphics.Bitmap
import dev.precisionmeasure.app.settings.AppSettings
import dev.precisionmeasure.app.settings.LineColorPreset
import dev.precisionmeasure.geometry.MeasurementQuality
import dev.precisionmeasure.geometry.MeasurementResult
import dev.precisionmeasure.geometry.ScaleCalibration
import dev.precisionmeasure.geometry.UnitFormatter
import dev.precisionmeasure.geometry.UnitSystem
import dev.precisionmeasure.geometry.Vec2
import dev.precisionmeasure.geometry.Vec3

enum class MeasurementMode(val label: String, val shortHint: String) {
    LINE("Line", "Measure point-to-point distance"),
    PERIMETER("Perimeter", "Chain edges or close loop for area"),
    BOX("Box", "4 taps: corner, length, width, height"),
    ANGLE("Angle", "3 taps: arm, vertex, arm"),
    HEIGHT("Height", "2 taps: base surface, then top"),
    LEVEL("Level", "Digital spirit level for surfaces & walls"),
}

enum class CapturePhase {
    ALIGN_A,
    ALIGN_B,
    LOCKED_AND_REFINING,
}

enum class SurfaceSource(val badge: String) {
    RAW_DEPTH("RAW DEPTH"),
    FULL_DEPTH("DEPTH"),
    PLANE("PLANE"),
    FEATURE_POINT("3D POINT"),
    NONE("SCANNING"),
}

enum class SnapTargetKind {
    NONE,
    CLOSE_LOOP,
    EXISTING_PIN,
}

enum class DepthMapOverlayMode(val label: String) {
    OFF("Off"),
    FULL_DEPTH("Full Depth"),
    RAW_DEPTH("Raw Depth");

    fun next(): DepthMapOverlayMode = entries[(ordinal + 1) % entries.size]
}

data class DevOverlayOptions(
    val depthMapMode: DepthMapOverlayMode = DepthMapOverlayMode.OFF,
    val showPointCloud: Boolean = false,
    val showPlanes: Boolean = false,
    val showTriangulationRays: Boolean = false,
    val showTelemetryHud: Boolean = false,
) {
    val anyActive: Boolean
        get() = depthMapMode != DepthMapOverlayMode.OFF ||
            showPointCloud ||
            showPlanes ||
            showTriangulationRays ||
            showTelemetryHud
}

data class DevTelemetry(
    val fps: Double = 0.0,
    val focalFx: Double = 0.0,
    val focalFy: Double = 0.0,
    val cpuImageSize: String = "-",
    val depthImageSize: String = "-",
    val centerDepthMm: Int = 0,
    val centerRawConfidence: Int = 0,
    val slamFeatureCount: Int = 0,
    val depthCloudCount: Int = 0,
    val planeCount: Int = 0,
    val cameraPosMeters: Vec3 = Vec3.ZERO,
)

data class BoxDimensions(
    val lengthMeters: Double,
    val widthMeters: Double,
    val heightMeters: Double,
)

/**
 * Completed measurement anchored in the live 3D scene.
 */
data class SceneItem(
    val id: Long,
    val label: String,
    val mode: MeasurementMode,
    val vertices: List<Vec3>,
    val isClosed: Boolean = false,
    val color: LineColorPreset = LineColorPreset.CYAN,
    val primaryMeters: Double? = null,
    val areaSqMeters: Double? = null,
    val volumeCubicMeters: Double? = null,
    val boxDimensions: BoxDimensions? = null,
    val angleDegrees: Double? = null,
    val ci95Meters: Double? = null,
    val isMultiView: Boolean = false,
    val refinedResult: MeasurementResult? = null,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    fun formatPrimary(unit: UnitSystem, decimals: Int): String = when (mode) {
        MeasurementMode.LINE -> UnitFormatter.formatLength(primaryMeters ?: 0.0, unit, decimals)
        MeasurementMode.PERIMETER -> {
            val perim = UnitFormatter.formatLength(primaryMeters ?: 0.0, unit, decimals)
            if (isClosed) "Perim $perim" else "Total $perim"
        }
        MeasurementMode.BOX -> UnitFormatter.formatVolume(volumeCubicMeters ?: 0.0, unit)
        MeasurementMode.ANGLE -> UnitFormatter.formatAngle(angleDegrees ?: 0.0)
        MeasurementMode.HEIGHT -> "H ${UnitFormatter.formatLength(primaryMeters ?: 0.0, unit, decimals)}"
        MeasurementMode.LEVEL -> "Level"
    }

    fun formatSecondary(unit: UnitSystem, decimals: Int): String? = when (mode) {
        MeasurementMode.LINE -> ci95Meters?.let {
            "${UnitFormatter.formatUncertainty(it, unit, decimals)}${if (isMultiView) " (3D)" else ""}"
        }
        MeasurementMode.PERIMETER -> areaSqMeters?.let { "Area ${UnitFormatter.formatArea(it, unit)}" }
        MeasurementMode.BOX -> boxDimensions?.let { d ->
            val l = UnitFormatter.formatLength(d.lengthMeters, unit, decimals)
            val w = UnitFormatter.formatLength(d.widthMeters, unit, decimals)
            val h = UnitFormatter.formatLength(d.heightMeters, unit, decimals)
            "$l × $w × $h"
        }
        MeasurementMode.ANGLE -> angleDegrees?.let { deg ->
            "Exterior ${UnitFormatter.formatAngle(360.0 - deg)}"
        }
        MeasurementMode.HEIGHT -> "Vertical delta"
        MeasurementMode.LEVEL -> null
    }
}

data class FloatingLabelOverlay(
    val screenCenter: Vec2,
    val line1: String,
    val line2: String? = null,
    val quality: MeasurementQuality? = null,
    val isActive: Boolean = false,
    val isPreview: Boolean = false,
    val accentArgb: Int? = null,
)

data class ProjectedPointsOverlay(
    val surfaceLocked: Boolean = false,
    val snapTargetKind: SnapTargetKind = SnapTargetKind.NONE,
    val labels: List<FloatingLabelOverlay> = emptyList(),
    val loupeSnapOffsetNorm: Vec2? = null,
)

data class MeasurementUiState(
    val mode: MeasurementMode = MeasurementMode.LINE,
    val phase: CapturePhase = CapturePhase.ALIGN_A,
    val activeVertexCount: Int = 0,
    val isArTracking: Boolean = false,
    val torchEnabled: Boolean = false,
    val surfaceSource: SurfaceSource = SurfaceSource.NONE,
    val rawDepthEnabled: Boolean = false,
    val calibration: ScaleCalibration = ScaleCalibration.UNCALIBRATED_VIO,
    val settings: AppSettings = AppSettings(),
    // Active readout values (populated for current active measurement or in-progress shape)
    val primaryReadout: String? = null,
    val secondaryReadout: String? = null,
    val accuracyTierLabel: String? = null,
    val activeDistanceMeters: Double? = null,
    val activeCi95Meters: Double? = null,
    val isMultiViewRefined: Boolean = false,
    val refinedMeasurement: MeasurementResult? = null,
    val sceneItems: List<SceneItem> = emptyList(),
    val observationsACount: Int = 0,
    val observationsBCount: Int = 0,
    val baselineCm: Double = 0.0,
    val parallaxDeg: Double = 0.0,
    val statusMessage: String = "Move phone gently to detect surface",
    val toastMessage: String? = null,
    val errorMessage: String? = null,
    val overlay: ProjectedPointsOverlay = ProjectedPointsOverlay(),
    val loupeBitmap: Bitmap? = null,
    val devTelemetry: DevTelemetry = DevTelemetry(),
)
