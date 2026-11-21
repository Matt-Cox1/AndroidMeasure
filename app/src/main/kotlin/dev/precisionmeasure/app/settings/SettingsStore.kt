package dev.precisionmeasure.app.settings

import android.content.Context
import dev.precisionmeasure.app.measurement.DepthMapOverlayMode
import dev.precisionmeasure.app.measurement.DevOverlayOptions
import dev.precisionmeasure.geometry.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val defaultUnit = if (Locale.getDefault().country.equals("US", ignoreCase = true)) {
            UnitSystem.IN_FRACTION
        } else {
            UnitSystem.AUTO_METRIC
        }
        val unitName = prefs.getString(KEY_UNIT, defaultUnit.name) ?: defaultUnit.name
        val unit = UnitSystem.entries.firstOrNull { it.name == unitName } ?: defaultUnit

        val zoomName = prefs.getString(KEY_LOUPE_ZOOM, LoupeZoom.ZOOM_4X.name) ?: LoupeZoom.ZOOM_4X.name
        val zoom = LoupeZoom.entries.firstOrNull { it.name == zoomName } ?: LoupeZoom.ZOOM_4X

        val reticleName = prefs.getString(KEY_RETICLE, ReticleStyle.CROSSHAIR.name) ?: ReticleStyle.CROSSHAIR.name
        val reticle = ReticleStyle.entries.firstOrNull { it.name == reticleName } ?: ReticleStyle.CROSSHAIR

        val colorName = prefs.getString(KEY_LINE_COLOR, LineColorPreset.CYAN.name) ?: LineColorPreset.CYAN.name
        val color = LineColorPreset.entries.firstOrNull { it.name == colorName } ?: LineColorPreset.CYAN

        val thickName = prefs.getString(KEY_LINE_THICK, LineThickness.MEDIUM.name) ?: LineThickness.MEDIUM.name
        val thick = LineThickness.entries.firstOrNull { it.name == thickName } ?: LineThickness.MEDIUM

        val depthName = prefs.getString(KEY_DEV_DEPTH, DepthMapOverlayMode.OFF.name) ?: DepthMapOverlayMode.OFF.name
        val depthMode = DepthMapOverlayMode.entries.firstOrNull { it.name == depthName } ?: DepthMapOverlayMode.OFF

        return AppSettings(
            unitSystem = unit,
            decimalPrecision = prefs.getInt(KEY_DECIMALS, 1).coerceIn(0, 2),
            showUncertainty = prefs.getBoolean(KEY_SHOW_UNCERTAINTY, false),
            cornerSnapEnabled = prefs.getBoolean(KEY_CORNER_SNAP, true),
            pinSnapEnabled = prefs.getBoolean(KEY_PIN_SNAP, true),
            loupeEnabled = prefs.getBoolean(KEY_LOUPE_ENABLED, true),
            loupeZoom = zoom,
            reticleStyle = reticle,
            lineColor = color,
            lineThickness = thick,
            showRulerTicks = prefs.getBoolean(KEY_RULER_TICKS, true),
            showHvLegs = prefs.getBoolean(KEY_HV_LEGS, true),
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_AWAKE, true),
            fixedFocusEnabled = prefs.getBoolean(KEY_FIXED_FOCUS, false),
            autoSaveToGallery = prefs.getBoolean(KEY_AUTO_GALLERY, true),
            saveCleanPhotoCopy = prefs.getBoolean(KEY_CLEAN_COPY, false),
            showQuickDevPill = prefs.getBoolean(KEY_DEV_PILL, false),
            devOptions = DevOverlayOptions(
                depthMapMode = depthMode,
                showPointCloud = prefs.getBoolean(KEY_DEV_CLOUD, false),
                showPlanes = prefs.getBoolean(KEY_DEV_PLANES, false),
                showTriangulationRays = prefs.getBoolean(KEY_DEV_RAYS, false),
                showTelemetryHud = prefs.getBoolean(KEY_DEV_HUD, false),
            ),
            hasCompletedOnboarding = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString(KEY_UNIT, s.unitSystem.name)
            .putInt(KEY_DECIMALS, s.decimalPrecision)
            .putBoolean(KEY_SHOW_UNCERTAINTY, s.showUncertainty)
            .putBoolean(KEY_CORNER_SNAP, s.cornerSnapEnabled)
            .putBoolean(KEY_PIN_SNAP, s.pinSnapEnabled)
            .putBoolean(KEY_LOUPE_ENABLED, s.loupeEnabled)
            .putString(KEY_LOUPE_ZOOM, s.loupeZoom.name)
            .putString(KEY_RETICLE, s.reticleStyle.name)
            .putString(KEY_LINE_COLOR, s.lineColor.name)
            .putString(KEY_LINE_THICK, s.lineThickness.name)
            .putBoolean(KEY_RULER_TICKS, s.showRulerTicks)
            .putBoolean(KEY_HV_LEGS, s.showHvLegs)
            .putBoolean(KEY_HAPTICS, s.hapticsEnabled)
            .putBoolean(KEY_KEEP_AWAKE, s.keepScreenOn)
            .putBoolean(KEY_FIXED_FOCUS, s.fixedFocusEnabled)
            .putBoolean(KEY_AUTO_GALLERY, s.autoSaveToGallery)
            .putBoolean(KEY_CLEAN_COPY, s.saveCleanPhotoCopy)
            .putBoolean(KEY_DEV_PILL, s.showQuickDevPill)
            .putString(KEY_DEV_DEPTH, s.devOptions.depthMapMode.name)
            .putBoolean(KEY_DEV_CLOUD, s.devOptions.showPointCloud)
            .putBoolean(KEY_DEV_PLANES, s.devOptions.showPlanes)
            .putBoolean(KEY_DEV_RAYS, s.devOptions.showTriangulationRays)
            .putBoolean(KEY_DEV_HUD, s.devOptions.showTelemetryHud)
            .putBoolean(KEY_ONBOARDING_DONE, s.hasCompletedOnboarding)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "android_measure_settings"
        private const val KEY_UNIT = "unit_system"
        private const val KEY_DECIMALS = "decimals"
        private const val KEY_SHOW_UNCERTAINTY = "show_uncertainty"
        private const val KEY_CORNER_SNAP = "corner_snap"
        private const val KEY_PIN_SNAP = "pin_snap"
        private const val KEY_LOUPE_ENABLED = "loupe_enabled"
        private const val KEY_LOUPE_ZOOM = "loupe_zoom"
        private const val KEY_RETICLE = "reticle_style"
        private const val KEY_LINE_COLOR = "line_color"
        private const val KEY_LINE_THICK = "line_thickness"
        private const val KEY_RULER_TICKS = "ruler_ticks"
        private const val KEY_HV_LEGS = "hv_legs"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_FIXED_FOCUS = "fixed_focus"
        private const val KEY_AUTO_GALLERY = "auto_gallery"
        private const val KEY_CLEAN_COPY = "clean_copy"
        private const val KEY_DEV_PILL = "dev_pill"
        private const val KEY_DEV_DEPTH = "dev_depth"
        private const val KEY_DEV_CLOUD = "dev_cloud"
        private const val KEY_DEV_PLANES = "dev_planes"
        private const val KEY_DEV_RAYS = "dev_rays"
        private const val KEY_DEV_HUD = "dev_hud"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
