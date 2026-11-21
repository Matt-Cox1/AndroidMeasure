package dev.precisionmeasure.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import dev.precisionmeasure.app.ar.ArMeasurementEngine
import dev.precisionmeasure.app.measurement.MeasurementCoordinator
import dev.precisionmeasure.app.measurement.MeasurementMode
import dev.precisionmeasure.app.sensor.LevelSensorManager
import dev.precisionmeasure.app.settings.SettingsStore
import dev.precisionmeasure.app.storage.HistoryStore
import dev.precisionmeasure.app.storage.PhotoAndExportManager
import dev.precisionmeasure.app.ui.FeedbackCategory
import dev.precisionmeasure.app.ui.MeasurementScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var historyStore: HistoryStore
    private lateinit var photoAndExportManager: PhotoAndExportManager
    private lateinit var levelSensorManager: LevelSensorManager
    private lateinit var coordinator: MeasurementCoordinator
    private lateinit var arEngine: ArMeasurementEngine
    private lateinit var glSurfaceView: GLSurfaceView

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hasCameraPermission = mutableStateOf(false)
    private val fatalStartupError = mutableStateOf<String?>(null)

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission.value = granted
        if (granted) {
            resumeArSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsStore = SettingsStore(this)
        historyStore = HistoryStore(this)
        photoAndExportManager = PhotoAndExportManager(this, historyStore)
        levelSensorManager = LevelSensorManager(this)
        coordinator = MeasurementCoordinator(settingsStore)
        arEngine = ArMeasurementEngine(this, coordinator)

        glSurfaceView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(arEngine)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        hasCameraPermission.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission.value) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    background = Color(0xFF0C1017),
                    surface = Color(0xFF141A24),
                ),
            ) {
                val startupErr = fatalStartupError.value
                val permitted = hasCameraPermission.value
                val uiState by coordinator.uiState.collectAsState()
                val savedCaptures by historyStore.records.collectAsState()

                // Only open the IMU gravity sensor while in Level mode so it never contends
                // with ARCore's high-rate direct-channel IMU pipeline during 3D measuring.
                LaunchedEffect(uiState.mode) {
                    if (uiState.mode == MeasurementMode.LEVEL) {
                        levelSensorManager.start()
                    } else {
                        levelSensorManager.stop()
                    }
                }

                LaunchedEffect(uiState.settings.keepScreenOn) {
                    if (uiState.settings.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                when {
                    startupErr != null -> ErrorFallbackView(startupErr)
                    !permitted -> PermissionRequestView {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    else -> MeasurementScreen(
                        uiState = uiState,
                        levelReadingFlow = levelSensorManager.reading,
                        savedCaptures = savedCaptures,
                        glSurfaceView = glSurfaceView,
                        onPlaceVertex = { arEngine.requestPlaceVertex() },
                        onPinActiveLine = { arEngine.requestPinActiveLine() },
                        onFinishOpenPolyline = { arEngine.requestFinishOpenPolyline() },
                        onUndo = { arEngine.requestUndo() },
                        onSelectMode = { mode -> arEngine.requestModeChange(mode) },
                        onToggleTorch = { arEngine.requestToggleTorch() },
                        onCycleUnit = { coordinator.cycleQuickUnit() },
                        onSnapPhoto = { captureAnnotatedPhoto() },
                        onTareLevel = { levelSensorManager.tareCurrent() },
                        onClearTareLevel = { levelSensorManager.clearTare() },
                        onUpdateSettings = { transform -> coordinator.updateSettings(transform) },
                        onUpdateDevOptions = { transform -> coordinator.updateDevOptions(transform) },
                        onApplyCalibration = { meters, label ->
                            coordinator.applyReferenceCalibration(meters, label)
                        },
                        onResetCalibration = { coordinator.resetCalibration() },
                        onRenameSceneItem = { id, label -> coordinator.renameSceneItem(id, label) },
                        onDeleteSceneItem = { id -> arEngine.requestDeleteItem(id) },
                        onClearAll = { arEngine.requestClearAll() },
                        onCopySceneText = {
                            val items = coordinator.allExportableItems()
                            val s = coordinator.uiState.value
                            photoAndExportManager.copyDimensionsToClipboard(
                                items = items,
                                unit = s.settings.unitSystem,
                                decimals = s.settings.decimalPrecision,
                                calibrationLabel = s.calibration.label,
                            )
                            coordinator.showToast("Copied ${items.size} measurements to clipboard")
                        },
                        onExportSceneCsv = {
                            val items = coordinator.allExportableItems()
                            val s = coordinator.uiState.value
                            photoAndExportManager.exportAndShareCsv(
                                items = items,
                                unit = s.settings.unitSystem,
                                decimals = s.settings.decimalPrecision,
                                calibrationLabel = s.calibration.label,
                            )
                        },
                        onExportScenePdf = {
                            val items = coordinator.allExportableItems()
                            val s = coordinator.uiState.value
                            val latestPhoto = historyStore.records.value.firstOrNull()?.photoPath
                            val rows = items.map { item ->
                                val v1 = item.formatPrimary(s.settings.unitSystem, s.settings.decimalPrecision)
                                val v2 = item.formatSecondary(s.settings.unitSystem, s.settings.decimalPrecision)
                                item.label to if (v2 != null) "$v1  ($v2)" else v1
                            }
                            photoAndExportManager.exportAndSharePdfSpecSheet(
                                title = "Current Session",
                                photoPath = latestPhoto,
                                rows = rows,
                                calibrationLabel = s.calibration.label,
                            )
                        },
                        onShareCapturePhoto = { record ->
                            record.photoPath?.let { photoAndExportManager.sharePhotoFile(it, record.title) }
                        },
                        onExportCapturePdf = { record ->
                            val rows = record.itemSummaries.mapIndexed { idx, s -> "Item ${idx + 1}" to s }
                            photoAndExportManager.exportAndSharePdfSpecSheet(
                                title = record.title,
                                photoPath = record.photoPath,
                                rows = rows,
                                calibrationLabel = record.calibrationLabel,
                            )
                        },
                        onRenameCapture = { id, title -> historyStore.renameRecord(id, title) },
                        onDeleteCapture = { id -> historyStore.deleteRecord(id) },
                        onSendFeedbackEmail = { category, description ->
                            openTextOnlyFeedbackEmail(category, description)
                        },
                        onCopyFeedbackText = { category, description ->
                            copyFeedbackText(category, description)
                        },
                        onDismissToast = { coordinator.dismissToast() },
                        onDismissError = { coordinator.dismissError() },
                    )
                }
            }
        }
    }

    private fun openTextOnlyFeedbackEmail(category: FeedbackCategory, description: String) {
        val toEmail = getString(R.string.feedback_email)
        val subject = "AndroidMeasure Feedback: ${category.label}"
        val body = description
        val mailtoUri = Uri.parse(
            "mailto:${Uri.encode(toEmail)}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}",
        )
        val intent = Intent(Intent.ACTION_SENDTO, mailtoUri)
        try {
            startActivity(Intent.createChooser(intent, "Send Feedback via Email"))
        } catch (_: ActivityNotFoundException) {
            copyFeedbackText(category, description)
            coordinator.showToast("No email app found — feedback copied to clipboard")
        }
    }

    private fun copyFeedbackText(category: FeedbackCategory, description: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = "[${category.label}]\n$description"
        cb.setPrimaryClip(ClipData.newPlainText("AndroidMeasure Feedback", text))
        coordinator.showToast("Feedback copied to clipboard")
    }

    private fun captureAnnotatedPhoto() {
        val stateSnapshot = coordinator.uiState.value
        val items = coordinator.allExportableItems()
        val unit = stateSnapshot.settings.unitSystem
        val dec = stateSnapshot.settings.decimalPrecision
        val summaries = items.map { item ->
            val p = item.formatPrimary(unit, dec)
            val sec = item.formatSecondary(unit, dec)
            if (sec != null) "${item.label}: $p ($sec)" else "${item.label}: $p"
        }
        val banner = stateSnapshot.primaryReadout?.let { p ->
            val sec = stateSnapshot.secondaryReadout
            if (sec != null) "$p  ·  $sec" else p
        } ?: summaries.firstOrNull()

        arEngine.requestPhotoSnapshot { rawGlBmp, labels ->
            ioScope.launch {
                try {
                    val outcome = photoAndExportManager.saveAnnotatedSnapshot(
                        rawGlBitmap = rawGlBmp,
                        labels = labels,
                        summaryBanner = banner,
                        sceneSummaries = summaries,
                        calibrationLabel = stateSnapshot.calibration.label,
                        saveToGallery = stateSnapshot.settings.autoSaveToGallery,
                        saveCleanCopy = stateSnapshot.settings.saveCleanPhotoCopy,
                    )
                    val msg = if (outcome.savedToGallery) {
                        "Photo saved to Pictures/AndroidMeasure"
                    } else {
                        "Photo saved to History"
                    }
                    coordinator.showToast(msg)
                } finally {
                    rawGlBmp.recycle()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (coordinator.uiState.value.mode == MeasurementMode.LEVEL) {
            levelSensorManager.start()
        }
        if (hasCameraPermission.value) {
            resumeArSession()
        }
    }

    override fun onPause() {
        super.onPause()
        levelSensorManager.stop()
        glSurfaceView.onPause()
        arEngine.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        arEngine.onDestroy()
        coordinator.close()
    }

    private fun resumeArSession() {
        try {
            arEngine.onResume()
            glSurfaceView.onResume()
            fatalStartupError.value = null
        } catch (e: UnavailableArcoreNotInstalledException) {
            fatalStartupError.value = "Google Play Services for AR (ARCore) is not installed on this device."
        } catch (e: UnavailableApkTooOldException) {
            fatalStartupError.value = "Update Google Play Services for AR (ARCore) to run AndroidMeasure."
        } catch (e: UnavailableSdkTooOldException) {
            fatalStartupError.value = "This app build requires a newer ARCore SDK."
        } catch (e: UnavailableDeviceNotCompatibleException) {
            fatalStartupError.value = "This Android device does not support ARCore."
        } catch (e: CameraNotAvailableException) {
            fatalStartupError.value = "Camera is in use by another app. Close it and reopen AndroidMeasure."
        } catch (e: Exception) {
            fatalStartupError.value = "ARCore session error: ${e.message ?: e::class.java.simpleName}"
        }
    }
}

@Composable
private fun PermissionRequestView(onGrantClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0C1017)).padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                text = "Camera Access Needed",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "AndroidMeasure uses your rear camera and on-device depth sensors to measure objects in 3D. Everything runs offline on your phone.",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Allow Camera", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorFallbackView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF180C0C)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ARCore Unavailable",
                color = Color(0xFFFF5252),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}
