package dev.precisionmeasure.app.settings

import dev.precisionmeasure.app.measurement.DepthMapOverlayMode
import dev.precisionmeasure.app.measurement.DevOverlayOptions
import dev.precisionmeasure.geometry.UnitSystem

enum class LineColorPreset(
    val displayName: String,
    val r: Float,
    val g: Float,
    val b: Float,
    val argb: Long,
) {
    CYAN("Cyan", 0.0f, 0.90f, 1.0f, 0xFF00E5FF),
    YELLOW("Amber", 1.0f, 0.84f, 0.0f, 0xFFFFD600),
    WHITE("Pure White", 0.98f, 0.98f, 0.98f, 0xFFFAFAFA),
    EMERALD("Emerald", 0.0f, 0.90f, 0.46f, 0xFF00E676),
    MAGENTA("Coral", 1.0f, 0.36f, 0.58f, 0xFFFF5C93),
}

enum class LineThickness(val displayName: String, val multiplier: Double) {
    THIN("Thin", 0.72),
    MEDIUM("Medium", 1.0),
    BOLD("Bold", 1.38),
}

enum class ReticleStyle(val displayName: String) {
    CROSSHAIR("Crosshair"),
    DOT("Minimal Dot"),
    CALIPER("Precision Ring"),
}

enum class LoupeZoom(val displayName: String, val patchSizePx: Int) {
    ZOOM_2X("2x", 96),
    ZOOM_4X("4x", 64),
    ZOOM_6X("6x", 42),
}

data class AppSettings(
    val unitSystem: UnitSystem = UnitSystem.AUTO_METRIC,
    val decimalPrecision: Int = 1,
    val showUncertainty: Boolean = false,
    val cornerSnapEnabled: Boolean = true,
    val pinSnapEnabled: Boolean = true,
    val loupeEnabled: Boolean = true,
    val loupeZoom: LoupeZoom = LoupeZoom.ZOOM_4X,
    val reticleStyle: ReticleStyle = ReticleStyle.CROSSHAIR,
    val lineColor: LineColorPreset = LineColorPreset.CYAN,
    val lineThickness: LineThickness = LineThickness.MEDIUM,
    val showRulerTicks: Boolean = true,
    val showHvLegs: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val fixedFocusEnabled: Boolean = false,
    val autoSaveToGallery: Boolean = true,
    val saveCleanPhotoCopy: Boolean = false,
    val showQuickDevPill: Boolean = false,
    val devOptions: DevOverlayOptions = DevOverlayOptions(),
    val hasCompletedOnboarding: Boolean = false,
) {
    val devOverlayActive: Boolean
        get() = devOptions.anyActive

    val showDevShortcutOnCamera: Boolean
        get() = showQuickDevPill || devOptions.anyActive
}
