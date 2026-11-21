package dev.precisionmeasure.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.precisionmeasure.app.measurement.CapturePhase
import dev.precisionmeasure.app.measurement.DepthMapOverlayMode
import dev.precisionmeasure.app.measurement.DevOverlayOptions
import dev.precisionmeasure.app.measurement.MeasurementMode
import dev.precisionmeasure.app.measurement.MeasurementUiState
import dev.precisionmeasure.app.measurement.SnapTargetKind
import dev.precisionmeasure.app.sensor.LevelReading
import dev.precisionmeasure.app.settings.AppSettings
import dev.precisionmeasure.app.storage.SavedCaptureRecord
import dev.precisionmeasure.geometry.MeasurementQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun MeasurementScreen(
    uiState: MeasurementUiState,
    levelReadingFlow: StateFlow<LevelReading>,
    savedCaptures: List<SavedCaptureRecord>,
    glSurfaceView: GLSurfaceView,
    onPlaceVertex: () -> Unit,
    onPinActiveLine: () -> Unit,
    onFinishOpenPolyline: () -> Unit,
    onUndo: () -> Unit,
    onSelectMode: (MeasurementMode) -> Unit,
    onToggleTorch: () -> Unit,
    onCycleUnit: () -> Unit,
    onSnapPhoto: () -> Unit,
    onTareLevel: () -> Unit,
    onClearTareLevel: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateDevOptions: ((DevOverlayOptions) -> DevOverlayOptions) -> Unit,
    onApplyCalibration: (trueLengthMeters: Double, label: String) -> Unit,
    onResetCalibration: () -> Unit,
    onRenameSceneItem: (Long, String) -> Unit,
    onDeleteSceneItem: (Long) -> Unit,
    onClearAll: () -> Unit,
    onCopySceneText: () -> Unit,
    onExportSceneCsv: () -> Unit,
    onExportScenePdf: () -> Unit,
    onShareCapturePhoto: (SavedCaptureRecord) -> Unit,
    onExportCapturePdf: (SavedCaptureRecord) -> Unit,
    onRenameCapture: (Long, String) -> Unit,
    onDeleteCapture: (Long) -> Unit,
    onSendFeedbackEmail: (FeedbackCategory, String) -> Unit,
    onCopyFeedbackText: (FeedbackCategory, String) -> Unit,
    onDismissToast: () -> Unit,
    onDismissError: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var showQuickDevDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shutterFlashAlpha = remember { Animatable(0f) }

    val settings = uiState.settings
    val devOpts = settings.devOptions
    val isLevelMode = uiState.mode == MeasurementMode.LEVEL
    val isLineLocked = uiState.mode == MeasurementMode.LINE && uiState.phase == CapturePhase.LOCKED_AND_REFINING
    val isAiming = !isLevelMode && !isLineLocked

    LaunchedEffect(uiState.toastMessage) {
        if (uiState.toastMessage != null) {
            delay(3200L)
            onDismissToast()
        }
    }

    var prevSnap by remember { mutableStateOf(SnapTargetKind.NONE) }
    LaunchedEffect(uiState.overlay.snapTargetKind) {
        val curr = uiState.overlay.snapTargetKind
        if (curr != SnapTargetKind.NONE && prevSnap == SnapTargetKind.NONE && settings.hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        prevSnap = curr
    }

    val labelTextPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val subLabelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#B2EBF2")
            textSize = 23f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
    }
    val pillBgPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val pillBorderPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())

        if (!isLevelMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (lbl in uiState.overlay.labels) {
                    val borderArgb = when {
                        lbl.isPreview -> android.graphics.Color.argb(220, 255, 255, 255)
                        lbl.quality == MeasurementQuality.LOCKED -> android.graphics.Color.argb(255, 0, 230, 118)
                        lbl.quality == MeasurementQuality.GOOD -> android.graphics.Color.argb(255, 255, 171, 0)
                        lbl.accentArgb != null -> lbl.accentArgb
                        else -> android.graphics.Color.argb(230, 0, 229, 255)
                    }
                    drawFloatingPill(
                        center = Offset(lbl.screenCenter.x.toFloat(), lbl.screenCenter.y.toFloat()),
                        line1 = lbl.line1,
                        line2 = lbl.line2,
                        borderColor = borderArgb,
                        bgColor = android.graphics.Color.argb(if (lbl.isActive) 215 else 185, 12, 16, 23),
                        labelPaint = labelTextPaint,
                        subPaint = subLabelPaint,
                        bgPaint = pillBgPaint,
                        borderPaint = pillBorderPaint,
                    )
                }
                if (isAiming) {
                    drawReticle(settings.reticleStyle, uiState.overlay.surfaceLocked, uiState.overlay.snapTargetKind)
                }
            }
        }

        if (isLevelMode) {
            val levelReading by levelReadingFlow.collectAsState()
            LevelOverlay(
                reading = levelReading,
                hapticsEnabled = settings.hapticsEnabled,
                onTare = onTareLevel,
                onClearTare = onClearTareLevel,
            )
        }

        if (isAiming && uiState.overlay.snapTargetKind != SnapTargetKind.NONE) {
            val isClose = uiState.overlay.snapTargetKind == SnapTargetKind.CLOSE_LOOP
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 86.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isClose) Color(0xDD00C853) else Color(0xDDFFB300))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (isClose) "Snap to Close Area" else "Snapped to Corner",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Top Camera Toolbar + Optional Labs Legend / Loupe
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 42.dp, start = 14.dp, end = 14.dp)
                .align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(
                        icon = Icons.Rounded.History,
                        contentDescription = "History",
                        badgeCount = uiState.sceneItems.size + savedCaptures.size,
                        onClick = { showHistory = true },
                    )
                    GlassIconButton(
                        icon = if (uiState.torchEnabled) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                        contentDescription = "Flashlight",
                        highlighted = uiState.torchEnabled,
                        onClick = onToggleTorch,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassTextPill(text = settings.unitSystem.shortLabel.uppercase(), onClick = onCycleUnit)
                    if (uiState.calibration.isCalibrated) {
                        GlassTextPill(text = "CAL", accent = Color(0xFF00E676), onClick = { showCalibrationDialog = true })
                    }
                    if (settings.showDevShortcutOnCamera) {
                        GlassTextPill(
                            text = "DEV",
                            accent = if (devOpts.anyActive) Color(0xFFCE93D8) else Color.White,
                            onClick = { showQuickDevDialog = true },
                        )
                    }
                    GlassIconButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        onClick = { showSettings = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (devOpts.depthMapMode != DepthMapOverlayMode.OFF && !isLevelMode) {
                        DepthLegendCard(devOpts.depthMapMode.label)
                    }
                    if (devOpts.showTelemetryHud) {
                        DevTelemetryCard(uiState.devTelemetry)
                    }
                }
                if (settings.loupeEnabled && isAiming && uiState.loupeBitmap != null) {
                    LoupePreviewBox(
                        bitmap = uiState.loupeBitmap,
                        snapOffsetNorm = if (settings.cornerSnapEnabled) uiState.overlay.loupeSnapOffsetNorm else null,
                        zoomLabel = settings.loupeZoom.displayName,
                    )
                }
            }
        }

        if (uiState.errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xEEB71C1C))
                    .clickable { onDismissError() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(uiState.errorMessage, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        } else if (uiState.toastMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 104.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE6111827))
                    .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(20.dp))
                    .clickable { onDismissToast() }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(uiState.toastMessage, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Bottom Camera-Style Control Dock
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC090C12), Color(0xEE090C12))))
                .padding(top = 14.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isLevelMode) {
                ReadoutGlassCard(uiState = uiState)
                if (uiState.mode == MeasurementMode.PERIMETER && uiState.activeVertexCount >= 2) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xDD1E293B))
                            .border(1.dp, Color(0x8800E5FF), RoundedCornerShape(18.dp))
                            .clickable {
                                if (settings.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onFinishOpenPolyline()
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "Finish Open Path (${uiState.activeVertexCount} pts)",
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                MeasurementMode.entries.forEach { mode ->
                    val selected = uiState.mode == mode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) Color(0xFF00E5FF) else Color(0x5518202C))
                            .clickable {
                                if (settings.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectMode(mode)
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = mode.label,
                            color = if (selected) Color(0xFF061018) else Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canUndo = uiState.activeVertexCount > 0 || uiState.sceneItems.isNotEmpty()
                RoundActionButton(
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    label = "Undo",
                    enabled = canUndo && !isLevelMode,
                    onClick = {
                        if (settings.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onUndo()
                    },
                )

                val isClosingLoop = uiState.overlay.snapTargetKind == SnapTargetKind.CLOSE_LOOP
                val canTapPrimary = when {
                    isLevelMode -> false
                    isLineLocked -> true
                    else -> uiState.isArTracking && uiState.overlay.surfaceLocked
                }
                val primaryColor = when {
                    !canTapPrimary -> Color(0xFF475569)
                    isClosingLoop -> Color(0xFF00E676)
                    isLineLocked -> Color(0xFF38BDF8)
                    else -> Color(settings.lineColor.argb)
                }

                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color(0x55000000))
                        .border(3.dp, if (canTapPrimary) Color.White else Color(0x55FFFFFF), CircleShape)
                        .clickable(enabled = canTapPrimary) {
                            if (settings.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isLineLocked) onPinActiveLine() else onPlaceVertex()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(62.dp).clip(CircleShape).background(primaryColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            isClosingLoop -> Icon(Icons.Rounded.Check, "Close Area", tint = Color.Black, modifier = Modifier.size(32.dp))
                            isLineLocked -> Text("NEW", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                            else -> Icon(Icons.Rounded.Add, "Place Point", tint = Color.Black, modifier = Modifier.size(34.dp))
                        }
                    }
                }

                RoundActionButton(
                    icon = Icons.Rounded.CameraAlt,
                    label = "Photo",
                    enabled = !isLevelMode && uiState.isArTracking,
                    onClick = {
                        if (settings.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            shutterFlashAlpha.snapTo(0.65f)
                            shutterFlashAlpha.animateTo(0f, animationSpec = tween(220))
                        }
                        onSnapPhoto()
                    },
                )
            }
        }

        if (shutterFlashAlpha.value > 0.01f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = shutterFlashAlpha.value)))
        }

        if (!settings.hasCompletedOnboarding) {
            OnboardingOverlay(onFinish = { onUpdateSettings { it.copy(hasCompletedOnboarding = true) } })
        }

        if (showSettings) {
            SettingsScreen(
                settings = settings,
                calibration = uiState.calibration,
                onUpdateSettings = onUpdateSettings,
                onOpenCalibration = {
                    showSettings = false
                    showCalibrationDialog = true
                },
                onOpenFeedback = {
                    showSettings = false
                    showFeedbackDialog = true
                },
                onReplayOnboarding = {
                    onUpdateSettings { it.copy(hasCompletedOnboarding = false) }
                },
                onClose = { showSettings = false },
            )
        }

        if (showHistory) {
            HistoryScreen(
                sceneItems = uiState.sceneItems,
                savedRecords = savedCaptures,
                unit = settings.unitSystem,
                decimals = settings.decimalPrecision,
                onRenameSceneItem = onRenameSceneItem,
                onDeleteSceneItem = onDeleteSceneItem,
                onClearScene = onClearAll,
                onCopySceneText = onCopySceneText,
                onExportSceneCsv = onExportSceneCsv,
                onExportScenePdf = onExportScenePdf,
                onShareCapturePhoto = onShareCapturePhoto,
                onExportCapturePdf = onExportCapturePdf,
                onRenameCapture = onRenameCapture,
                onDeleteCapture = onDeleteCapture,
                onClose = { showHistory = false },
            )
        }
    }

    if (showQuickDevDialog) {
        QuickDevDialog(
            options = devOpts,
            onUpdate = onUpdateDevOptions,
            onDismiss = { showQuickDevDialog = false },
        )
    }

    if (showFeedbackDialog) {
        FeedbackDialog(
            onSendEmail = onSendFeedbackEmail,
            onCopyText = onCopyFeedbackText,
            onDismiss = { showFeedbackDialog = false },
        )
    }

    if (showCalibrationDialog) {
        val hasLine = (uiState.mode == MeasurementMode.LINE && uiState.activeDistanceMeters != null) ||
            uiState.sceneItems.any { it.mode == MeasurementMode.LINE }
        CalibrationDialog(
            currentCalibrationLabel = uiState.calibration.label,
            isCalibrated = uiState.calibration.isCalibrated,
            hasValidMeasurement = hasLine,
            onApply = { meters, label ->
                onApplyCalibration(meters, label)
                showCalibrationDialog = false
            },
            onReset = {
                onResetCalibration()
                showCalibrationDialog = false
            },
            onDismiss = { showCalibrationDialog = false },
        )
    }
}
