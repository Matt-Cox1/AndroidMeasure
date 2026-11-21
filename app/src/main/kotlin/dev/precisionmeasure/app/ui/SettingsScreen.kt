package dev.precisionmeasure.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.precisionmeasure.app.measurement.DepthMapOverlayMode
import dev.precisionmeasure.app.measurement.DevOverlayOptions
import dev.precisionmeasure.app.settings.AppSettings
import dev.precisionmeasure.app.settings.LineColorPreset
import dev.precisionmeasure.app.settings.LineThickness
import dev.precisionmeasure.app.settings.LoupeZoom
import dev.precisionmeasure.app.settings.ReticleStyle
import dev.precisionmeasure.geometry.ScaleCalibration
import dev.precisionmeasure.geometry.UnitSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    calibration: ScaleCalibration,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenFeedback: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        containerColor = Color(0xFF0C1017),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C1017)),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 1. Units & Formatting
            SettingsSection("Units & Precision") {
                Text(
                    text = "Unit System",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                UnitSystem.entries.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { unit ->
                            ChoiceChipPill(
                                text = unit.shortLabel,
                                selected = settings.unitSystem == unit,
                                onClick = { onUpdateSettings { it.copy(unitSystem = unit) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Decimal Precision", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Digits shown for decimal units", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 1, 2).forEach { d ->
                            ChoiceChipPill(
                                text = "$d",
                                selected = settings.decimalPrecision == d,
                                onClick = { onUpdateSettings { it.copy(decimalPrecision = d) } },
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0x1FFFFFFF), modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchRow(
                    title = "Show ± Uncertainty Margin",
                    subtitle = "Display 95% confidence interval next to dimensions",
                    checked = settings.showUncertainty,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showUncertainty = v) } },
                )
            }

            // 2. Aiming & Snapping
            SettingsSection("Aiming & Loupe") {
                SettingsSwitchRow(
                    title = "Smart Corner Snap",
                    subtitle = "Refine tapped pixel onto the sharpest nearby edge corner",
                    checked = settings.cornerSnapEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(cornerSnapEnabled = v) } },
                )
                SettingsSwitchRow(
                    title = "Magnetic Pin Snap",
                    subtitle = "Snap onto existing corners to close loops and join lines cleanly",
                    checked = settings.pinSnapEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(pinSnapEnabled = v) } },
                )
                SettingsSwitchRow(
                    title = "Magnifier Loupe",
                    subtitle = "Show zoomed precision preview while aiming",
                    checked = settings.loupeEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(loupeEnabled = v) } },
                )

                if (settings.loupeEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Loupe Zoom", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LoupeZoom.entries.forEach { z ->
                                ChoiceChipPill(
                                    text = z.displayName,
                                    selected = settings.loupeZoom == z,
                                    onClick = { onUpdateSettings { it.copy(loupeZoom = z) } },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Reticle Style", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReticleStyle.entries.forEach { style ->
                            ChoiceChipPill(
                                text = style.displayName.substringBefore(" "),
                                selected = settings.reticleStyle == style,
                                onClick = { onUpdateSettings { it.copy(reticleStyle = style) } },
                            )
                        }
                    }
                }
            }

            // 3. Appearance & Feedback
            SettingsSection("Appearance & Feedback") {
                Text("Line Color", color = Color(0xFFCBD5E1), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LineColorPreset.entries.forEach { preset ->
                        val selected = settings.lineColor == preset
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(preset.argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color.White else Color(0x55FFFFFF),
                                    shape = CircleShape,
                                )
                                .clickable { onUpdateSettings { it.copy(lineColor = preset) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = preset.displayName,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Line Thickness", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LineThickness.entries.forEach { th ->
                            ChoiceChipPill(
                                text = th.displayName,
                                selected = settings.lineThickness == th,
                                onClick = { onUpdateSettings { it.copy(lineThickness = th) } },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                SettingsSwitchRow(
                    title = "3D Ruler Tick Marks",
                    subtitle = "Draw 1 cm / 5 cm perspective ticks along measured lines",
                    checked = settings.showRulerTicks,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showRulerTicks = v) } },
                )
                SettingsSwitchRow(
                    title = "Horizontal & Vertical Guides",
                    subtitle = "Show dashed right-angle legs on angled lines",
                    checked = settings.showHvLegs,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showHvLegs = v) } },
                )
                SettingsSwitchRow(
                    title = "Haptic Feedback",
                    subtitle = "Tactile ticks when placing points, snapping, or leveling",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(hapticsEnabled = v) } },
                )
                SettingsSwitchRow(
                    title = "Keep Screen Awake",
                    subtitle = "Prevent display sleep during active measurements",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(keepScreenOn = v) } },
                )
            }

            // 4. Camera & Calibration
            SettingsSection("Camera, Capture & Scale") {
                SettingsSwitchRow(
                    title = "Lock Camera Focus (Fixed)",
                    subtitle = "Off = Auto Focus (sharp close-up). On = Fixed Focus for stable intrinsics.",
                    checked = settings.fixedFocusEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(fixedFocusEnabled = v) } },
                )
                SettingsSwitchRow(
                    title = "Save Photos to Gallery",
                    subtitle = "Store annotated captures in Pictures/AndroidMeasure",
                    checked = settings.autoSaveToGallery,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoSaveToGallery = v) } },
                )
                SettingsSwitchRow(
                    title = "Also Save Clean Original",
                    subtitle = "Save a second unannotated photo alongside measurements",
                    checked = settings.saveCleanPhotoCopy,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(saveCleanPhotoCopy = v) } },
                )

                HorizontalDivider(color = Color(0x1FFFFFFF), modifier = Modifier.padding(vertical = 6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF182232))
                        .clickable { onOpenCalibration() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Metric Scale Calibration", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (calibration.isCalibrated) {
                                "Active: ${"%.4f".format(calibration.factor)}x (${calibration.label})"
                            } else {
                                "Default ARCore VIO (tap to calibrate with card or coin)"
                            },
                            color = if (calibration.isCalibrated) Color(0xFF00E676) else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = Color(0xFF00E5FF))
                }
            }

            // 5. Labs / Developer Overlays
            SettingsSection("Labs & Developer Overlays") {
                val dev = settings.devOptions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live Depth Heatmap", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Turbo false-color depth map over camera", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    ChoiceChipPill(
                        text = dev.depthMapMode.label,
                        selected = dev.depthMapMode != DepthMapOverlayMode.OFF,
                        onClick = {
                            onUpdateSettings {
                                it.copy(devOptions = it.devOptions.copy(depthMapMode = it.devOptions.depthMapMode.next()))
                            }
                        },
                    )
                }

                SettingsSwitchRow(
                    title = "Live 3D Point Cloud",
                    subtitle = "ARCore SLAM features + unprojected 3D Raw Depth points",
                    checked = dev.showPointCloud,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(devOptions = it.devOptions.copy(showPointCloud = v)) } },
                )
                SettingsSwitchRow(
                    title = "Detected 3D Planes",
                    subtitle = "Horizontal (green) and vertical (violet) surface wireframes",
                    checked = dev.showPlanes,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(devOptions = it.devOptions.copy(showPlanes = v)) } },
                )
                SettingsSwitchRow(
                    title = "Multi-View 3D Rays",
                    subtitle = "Visualize camera keyframe rays converging onto Line A & B",
                    checked = dev.showTriangulationRays,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(devOptions = it.devOptions.copy(showTriangulationRays = v)) } },
                )
                SettingsSwitchRow(
                    title = "Sensor Telemetry HUD",
                    subtitle = "FPS, focal length, depth mm & confidence, point/plane counts",
                    checked = dev.showTelemetryHud,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(devOptions = it.devOptions.copy(showTelemetryHud = v)) } },
                )
                SettingsSwitchRow(
                    title = "Show Quick DEV Button on Camera",
                    subtitle = "Pin a shortcut pill to the top toolbar for instant toggling",
                    checked = settings.showQuickDevPill,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showQuickDevPill = v) } },
                )

                if (dev.anyActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x33FF5252))
                            .clickable { onUpdateSettings { it.copy(devOptions = DevOverlayOptions()) } }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Turn Off All Developer Overlays", color = Color(0xFFFF8A80), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 6. Feedback & Zero-Data Privacy
            SettingsSection("Feedback & Privacy") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF182232))
                        .clickable { onOpenFeedback() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Send Feedback / Report an Issue", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Write a detailed description. Text only — zero screenshots or telemetry.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Rounded.Feedback, contentDescription = null, tint = Color(0xFF00E5FF))
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text("AndroidMeasure v1.0.0 — Free & Private", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "No internet permission, no ads, no accounts, no analytics, and no telemetry. All camera and depth geometry runs 100% offline on your device.",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF182232))
                        .clickable {
                            onReplayOnboarding()
                            onClose()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("Replay Quick Guide", color = Color(0xFF00E5FF), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF141A24))
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title.uppercase(),
            color = Color(0xFF00E5FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF061018),
                checkedTrackColor = Color(0xFF00E5FF),
            ),
        )
    }
}

@Composable
private fun ChoiceChipPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF00E5FF) else Color(0xFF1E2634))
            .border(1.dp, if (selected) Color(0xFF00E5FF) else Color(0x28FFFFFF), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF061018) else Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
