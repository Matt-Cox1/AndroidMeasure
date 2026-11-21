package dev.precisionmeasure.app.measurement

import android.graphics.Bitmap
import dev.precisionmeasure.app.settings.AppSettings
import dev.precisionmeasure.app.settings.SettingsStore
import dev.precisionmeasure.geometry.DepthPrior
import dev.precisionmeasure.geometry.MeasurementQuality
import dev.precisionmeasure.geometry.MeasurementResult
import dev.precisionmeasure.geometry.MeasurementSolver
import dev.precisionmeasure.geometry.ScaleCalibration
import dev.precisionmeasure.geometry.ShapeGeometry
import dev.precisionmeasure.geometry.UnitFormatter
import dev.precisionmeasure.geometry.Vec2
import dev.precisionmeasure.geometry.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Coordinates measurement modes (Line, Perimeter/Area, Box/Volume, Angle, Height, Level),
 * delegates Line A/B optical tracking to [EndpointTracker], manages undo stack, scene items,
 * and metric scale calibration.
 */
class MeasurementCoordinator(
    private val settingsStore: SettingsStore,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workerDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val itemIdGen = AtomicLong(0L)
    private val trackingFrameBusy = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(MeasurementUiState(settings = settingsStore.settings.value))
    val uiState: StateFlow<MeasurementUiState> = _uiState.asStateFlow()

    @Volatile
    var activeVertices: List<Vec3> = emptyList()
        private set

    val pendingReanchorA = AtomicReference<Vec3?>(null)
    val pendingReanchorB = AtomicReference<Vec3?>(null)

    @Volatile
    var activeRefinedResult: MeasurementResult? = null
        private set

    @Volatile
    var completedItems: List<SceneItem> = emptyList()
        private set

    private val trackerA = EndpointTracker()
    private val trackerB = EndpointTracker()

    val rayOriginsA: List<Vec3> get() = trackerA.rayOrigins
    val rayOriginsB: List<Vec3> get() = trackerB.rayOrigins

    init {
        scope.launch {
            settingsStore.settings.collect { nextSettings ->
                _uiState.update { it.copy(settings = nextSettings) }
                refreshActiveReadout()
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    fun updateDevOptions(transform: (DevOverlayOptions) -> DevOverlayOptions) =
        settingsStore.update { it.copy(devOptions = transform(it.devOptions)) }

    fun cycleQuickUnit() = settingsStore.update { it.copy(unitSystem = it.unitSystem.nextQuick()) }

    fun setMode(mode: MeasurementMode) {
        scope.launch {
            if (_uiState.value.mode == mode) return@launch
            if (_uiState.value.mode == MeasurementMode.LINE && activeVertices.size == 2) {
                commitActiveLineInternal()
            }
            clearActiveStateInternal()
            _uiState.update {
                it.copy(
                    mode = mode,
                    phase = CapturePhase.ALIGN_A,
                    activeVertexCount = 0,
                    primaryReadout = null,
                    secondaryReadout = null,
                    accuracyTierLabel = null,
                    activeDistanceMeters = null,
                    activeCi95Meters = null,
                    isMultiViewRefined = false,
                    refinedMeasurement = null,
                    sceneItems = completedItems,
                    observationsACount = 0,
                    observationsBCount = 0,
                    baselineCm = 0.0,
                    parallaxDeg = 0.0,
                    statusMessage = defaultPromptFor(mode, 0, it.overlay.surfaceLocked),
                    errorMessage = null,
                )
            }
        }
    }

    fun syncActiveVerticesFromAnchors(anchorPositions: List<Vec3>) {
        if (anchorPositions.size == activeVertices.size && anchorPositions.isNotEmpty()) {
            activeVertices = anchorPositions
        }
    }

    fun syncCompletedItemVertices(itemIndex: Int, anchorPositions: List<Vec3>) {
        val current = completedItems
        if (itemIndex !in current.indices) return
        val item = current[itemIndex]
        if (item.vertices.size != anchorPositions.size) return
        val updated = current.toMutableList()
        updated[itemIndex] = item.copy(vertices = anchorPositions)
        completedItems = updated
    }

    fun isReadyForTrackingFrame(): Boolean {
        val state = _uiState.value
        if (state.mode != MeasurementMode.LINE || state.phase == CapturePhase.ALIGN_A) return false
        if (trackerA.isFull && trackerB.isFull) return false
        return !trackingFrameBusy.get()
    }

    fun updateArTelemetry(
        isTracking: Boolean,
        surfaceSource: SurfaceSource,
        torchEnabled: Boolean,
        rawDepthEnabled: Boolean,
        trackingHint: String? = null,
    ) {
        _uiState.update { state ->
            val msg = if (state.activeVertexCount == 0 && state.mode != MeasurementMode.LEVEL) {
                when {
                    !isTracking -> trackingHint ?: "Initializing AR tracking — move phone gently"
                    surfaceSource == SurfaceSource.NONE -> "Aim at a textured surface and move slightly"
                    else -> defaultPromptFor(state.mode, 0, surfaceLocked = true)
                }
            } else {
                state.statusMessage
            }
            state.copy(
                isArTracking = isTracking,
                surfaceSource = surfaceSource,
                torchEnabled = torchEnabled,
                rawDepthEnabled = rawDepthEnabled,
                statusMessage = msg,
            )
        }
    }

    fun updateOverlayAndLoupe(overlay: ProjectedPointsOverlay, loupeBitmap: Bitmap?) {
        _uiState.update { state ->
            state.copy(overlay = overlay, loupeBitmap = loupeBitmap ?: state.loupeBitmap)
        }
    }

    fun updateDevTelemetry(telemetry: DevTelemetry) {
        _uiState.update { it.copy(devTelemetry = telemetry) }
    }

    fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
    fun dismissToast() = _uiState.update { it.copy(toastMessage = null) }
    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }

    fun onVertexPlaced(
        keyframe: Keyframe?,
        rawImagePixel: Vec2,
        depthPrior: DepthPrior?,
        worldPoint: Vec3,
        closePerimeterLoop: Boolean = false,
    ) {
        scope.launch {
            val state = _uiState.value
            val cal = state.calibration
            val settings = state.settings

            when (state.mode) {
                MeasurementMode.LINE -> handleLinePlacement(keyframe, rawImagePixel, depthPrior, worldPoint, settings)
                MeasurementMode.PERIMETER -> {
                    if (closePerimeterLoop && activeVertices.size >= 3) {
                        val metrics = ShapeGeometry.computePolygonMetrics(activeVertices, cal.factor)
                        val item = SceneItem(
                            id = itemIdGen.incrementAndGet(),
                            label = "Area ${countModeItems(MeasurementMode.PERIMETER) + 1}",
                            mode = MeasurementMode.PERIMETER,
                            vertices = activeVertices.toList(),
                            isClosed = true,
                            color = settings.lineColor,
                            primaryMeters = metrics.perimeterMeters,
                            areaSqMeters = metrics.areaSquareMeters,
                        )
                        completedItems = completedItems + item
                        val cornerCount = activeVertices.size
                        clearActiveStateInternal()
                        publishCompletedItemState(item, "Closed loop ($cornerCount corners)")
                    } else {
                        activeVertices = activeVertices + worldPoint
                        publishInProgressMultiVertexState()
                    }
                }
                MeasurementMode.BOX -> {
                    val nextVerts = activeVertices + worldPoint
                    activeVertices = nextVerts
                    if (nextVerts.size < 4) {
                        publishInProgressMultiVertexState()
                    } else {
                        try {
                            val box = ShapeGeometry.computeBoxMetrics(
                                nextVerts[0], nextVerts[1], nextVerts[2], nextVerts[3], cal.factor,
                            )
                            val item = SceneItem(
                                id = itemIdGen.incrementAndGet(),
                                label = "Box ${countModeItems(MeasurementMode.BOX) + 1}",
                                mode = MeasurementMode.BOX,
                                vertices = nextVerts.toList(),
                                isClosed = true,
                                color = settings.lineColor,
                                primaryMeters = box.lengthMeters,
                                areaSqMeters = box.footprintAreaSqMeters,
                                volumeCubicMeters = box.volumeCubicMeters,
                                boxDimensions = BoxDimensions(box.lengthMeters, box.widthMeters, box.heightMeters),
                            )
                            completedItems = completedItems + item
                            clearActiveStateInternal()
                            publishCompletedItemState(item, "Box measured")
                        } catch (e: IllegalArgumentException) {
                            activeVertices = nextVerts.dropLast(1)
                            _uiState.update { it.copy(errorMessage = e.message ?: "Pick a distinct height point") }
                            publishInProgressMultiVertexState()
                        }
                    }
                }
                MeasurementMode.ANGLE -> {
                    val nextVerts = activeVertices + worldPoint
                    activeVertices = nextVerts
                    if (nextVerts.size < 3) {
                        publishInProgressMultiVertexState()
                    } else {
                        try {
                            val deg = ShapeGeometry.computeAngleDegrees(nextVerts[0], nextVerts[1], nextVerts[2])
                            val item = SceneItem(
                                id = itemIdGen.incrementAndGet(),
                                label = "Angle ${countModeItems(MeasurementMode.ANGLE) + 1}",
                                mode = MeasurementMode.ANGLE,
                                vertices = nextVerts.toList(),
                                isClosed = false,
                                color = settings.lineColor,
                                angleDegrees = deg,
                            )
                            completedItems = completedItems + item
                            clearActiveStateInternal()
                            publishCompletedItemState(item, "Angle locked")
                        } catch (e: IllegalArgumentException) {
                            activeVertices = nextVerts.dropLast(1)
                            _uiState.update { it.copy(errorMessage = e.message ?: "Pick distinct angle points") }
                            publishInProgressMultiVertexState()
                        }
                    }
                }
                MeasurementMode.HEIGHT -> {
                    val nextVerts = activeVertices + worldPoint
                    activeVertices = nextVerts
                    if (nextVerts.size < 2) {
                        publishInProgressMultiVertexState()
                    } else {
                        val h = ShapeGeometry.computeVerticalHeight(nextVerts[0], nextVerts[1], cal.factor)
                        val item = SceneItem(
                            id = itemIdGen.incrementAndGet(),
                            label = "Height ${countModeItems(MeasurementMode.HEIGHT) + 1}",
                            mode = MeasurementMode.HEIGHT,
                            vertices = nextVerts.toList(),
                            isClosed = false,
                            color = settings.lineColor,
                            primaryMeters = h,
                        )
                        completedItems = completedItems + item
                        clearActiveStateInternal()
                        publishCompletedItemState(item, "Height measured")
                    }
                }
                MeasurementMode.LEVEL -> Unit
            }
        }
    }

    fun finishOpenPolyline() {
        scope.launch {
            val state = _uiState.value
            if (state.mode != MeasurementMode.PERIMETER || activeVertices.size < 2) return@launch
            val total = ShapeGeometry.computePolylineLength(activeVertices, closed = false, scaleFactor = state.calibration.factor)
            val item = SceneItem(
                id = itemIdGen.incrementAndGet(),
                label = "Path ${countModeItems(MeasurementMode.PERIMETER) + 1}",
                mode = MeasurementMode.PERIMETER,
                vertices = activeVertices.toList(),
                isClosed = false,
                color = state.settings.lineColor,
                primaryMeters = total,
            )
            completedItems = completedItems + item
            clearActiveStateInternal()
            publishCompletedItemState(item, "Path saved")
        }
    }

    fun pinActiveLineAndStartNew() {
        scope.launch {
            commitActiveLineInternal()
            clearActiveStateInternal()
            _uiState.update {
                it.copy(
                    phase = CapturePhase.ALIGN_A,
                    activeVertexCount = 0,
                    primaryReadout = null,
                    secondaryReadout = null,
                    accuracyTierLabel = null,
                    activeDistanceMeters = null,
                    activeCi95Meters = null,
                    isMultiViewRefined = false,
                    refinedMeasurement = null,
                    sceneItems = completedItems,
                    observationsACount = 0,
                    observationsBCount = 0,
                    baselineCm = 0.0,
                    parallaxDeg = 0.0,
                    statusMessage = defaultPromptFor(it.mode, 0, it.overlay.surfaceLocked),
                    errorMessage = null,
                )
            }
        }
    }

    fun undoLast() {
        scope.launch {
            val state = _uiState.value
            when {
                activeVertices.isNotEmpty() -> {
                    if (state.mode == MeasurementMode.LINE) {
                        if (activeVertices.size == 2) {
                            activeVertices = activeVertices.take(1)
                            trackerB.reset()
                            pendingReanchorB.set(null)
                            activeRefinedResult = null
                            publishLineState(CapturePhase.ALIGN_B)
                        } else {
                            clearActiveStateInternal()
                            publishLineState(CapturePhase.ALIGN_A)
                        }
                    } else {
                        activeVertices = activeVertices.dropLast(1)
                        if (activeVertices.isEmpty()) {
                            clearActiveStateInternal()
                            _uiState.update {
                                it.copy(
                                    phase = CapturePhase.ALIGN_A,
                                    activeVertexCount = 0,
                                    primaryReadout = null,
                                    secondaryReadout = null,
                                    accuracyTierLabel = null,
                                    statusMessage = defaultPromptFor(it.mode, 0, it.overlay.surfaceLocked),
                                )
                            }
                        } else {
                            publishInProgressMultiVertexState()
                        }
                    }
                }
                completedItems.isNotEmpty() -> {
                    completedItems = completedItems.dropLast(1)
                    _uiState.update {
                        it.copy(
                            sceneItems = completedItems,
                            primaryReadout = null,
                            secondaryReadout = null,
                            accuracyTierLabel = null,
                            statusMessage = defaultPromptFor(it.mode, 0, it.overlay.surfaceLocked),
                        )
                    }
                }
            }
        }
    }

    fun renameSceneItem(id: Long, newLabel: String) {
        val clean = newLabel.trim().ifEmpty { return }
        scope.launch {
            completedItems = completedItems.map { if (it.id == id) it.copy(label = clean) else it }
            _uiState.update { it.copy(sceneItems = completedItems) }
        }
    }

    fun deleteSceneItem(id: Long) {
        scope.launch {
            completedItems = completedItems.filterNot { it.id == id }
            _uiState.update { it.copy(sceneItems = completedItems) }
        }
    }

    fun clearAll() {
        scope.launch {
            completedItems = emptyList()
            clearActiveStateInternal()
            _uiState.update {
                it.copy(
                    phase = CapturePhase.ALIGN_A,
                    activeVertexCount = 0,
                    primaryReadout = null,
                    secondaryReadout = null,
                    accuracyTierLabel = null,
                    activeDistanceMeters = null,
                    activeCi95Meters = null,
                    isMultiViewRefined = false,
                    refinedMeasurement = null,
                    sceneItems = emptyList(),
                    observationsACount = 0,
                    observationsBCount = 0,
                    baselineCm = 0.0,
                    parallaxDeg = 0.0,
                    statusMessage = defaultPromptFor(it.mode, 0, it.overlay.surfaceLocked),
                    errorMessage = null,
                )
            }
        }
    }

    fun onTrackingFrame(keyframe: Keyframe) {
        if (!trackingFrameBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val state = _uiState.value
                if (state.mode != MeasurementMode.LINE || state.phase == CapturePhase.ALIGN_A) return@launch

                var changed = false
                val (chA, newA) = trackerA.trackFrame(keyframe, activeVertices.getOrNull(0))
                if (newA != null) {
                    activeVertices = if (activeVertices.size >= 2) listOf(newA, activeVertices[1]) else listOf(newA)
                    pendingReanchorA.set(newA)
                }
                changed = chA || changed

                if (state.phase == CapturePhase.LOCKED_AND_REFINING) {
                    val (chB, newB) = trackerB.trackFrame(keyframe, activeVertices.getOrNull(1))
                    if (newB != null && activeVertices.size >= 2) {
                        activeVertices = listOf(activeVertices[0], newB)
                        pendingReanchorB.set(newB)
                    }
                    changed = chB || changed
                }
                if (changed) publishLineState(state.phase)
            } finally {
                trackingFrameBusy.set(false)
            }
        }
    }

    fun applyReferenceCalibration(trueLengthMeters: Double, label: String) {
        scope.launch {
            val activePts = activeVertices
            val refPair: Pair<Vec3, Vec3>? = when {
                _uiState.value.mode == MeasurementMode.LINE && activePts.size == 2 -> activePts[0] to activePts[1]
                else -> completedItems.lastOrNull { it.mode == MeasurementMode.LINE && it.vertices.size == 2 }
                    ?.let { it.vertices[0] to it.vertices[1] }
            }
            if (refPair == null) {
                _uiState.update {
                    it.copy(errorMessage = "Measure a reference line (e.g. a credit card) first, then apply calibration.")
                }
                return@launch
            }
            val rawDelta = refPair.second - refPair.first
            val rawDist = rawDelta.norm()
            if (rawDist < 0.005) {
                _uiState.update { it.copy(errorMessage = "Measured length is too short to calibrate.") }
                return@launch
            }
            val refined = if (activePts.size == 2) activeRefinedResult else null
            val geomSigma = if (refined != null) {
                val dir = rawDelta.normalized()
                sqrt((refined.pointA.covarianceMeters2 + refined.pointB.covarianceMeters2).quadraticForm(dir).coerceAtLeast(0.0))
            } else {
                max(0.003, rawDist * 0.015)
            }

            val newCalibration = try {
                ScaleCalibration.fromTwoPointReference(trueLengthMeters, rawDist, geomSigma, label)
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Calibration failed") }
                return@launch
            }

            completedItems = completedItems.map { rescaleItem(it, newCalibration) }
            _uiState.update {
                it.copy(
                    calibration = newCalibration,
                    sceneItems = completedItems,
                    toastMessage = "Calibrated (${"%.3f".format(newCalibration.factor)}x) using $label",
                    errorMessage = null,
                )
            }
            refreshActiveReadout()
        }
    }

    fun resetCalibration() {
        scope.launch {
            val defaultCal = ScaleCalibration.UNCALIBRATED_VIO
            completedItems = completedItems.map { rescaleItem(it, defaultCal) }
            _uiState.update {
                it.copy(
                    calibration = defaultCal,
                    sceneItems = completedItems,
                    toastMessage = "Scale reset to default ARCore VIO",
                    errorMessage = null,
                )
            }
            refreshActiveReadout()
        }
    }

    fun allExportableItems(): List<SceneItem> {
        val list = completedItems.toMutableList()
        val state = _uiState.value
        if (state.mode == MeasurementMode.LINE && activeVertices.size == 2 && state.activeDistanceMeters != null) {
            list.add(
                SceneItem(
                    id = -1L,
                    label = "Active Line",
                    mode = MeasurementMode.LINE,
                    vertices = activeVertices.toList(),
                    color = state.settings.lineColor,
                    primaryMeters = state.activeDistanceMeters,
                    ci95Meters = state.activeCi95Meters,
                    isMultiView = state.isMultiViewRefined,
                    refinedResult = activeRefinedResult,
                ),
            )
        }
        return list
    }

    fun close() = scope.cancel()

    private fun handleLinePlacement(
        keyframe: Keyframe?,
        rawImagePixel: Vec2,
        depthPrior: DepthPrior?,
        worldPoint: Vec3,
        settings: AppSettings,
    ) {
        if (activeVertices.isEmpty() || activeVertices.size >= 2) {
            if (activeVertices.size == 2) commitActiveLineInternal()
            clearActiveStateInternal()
            activeVertices = listOf(worldPoint)
            trackerA.initialize(worldPoint, keyframe, rawImagePixel, depthPrior, settings.cornerSnapEnabled)
            publishLineState(CapturePhase.ALIGN_B)
        } else {
            activeVertices = listOf(activeVertices[0], worldPoint)
            trackerB.initialize(worldPoint, keyframe, rawImagePixel, depthPrior, settings.cornerSnapEnabled)
            if (keyframe != null) {
                val (_, newA) = trackerA.trackFrame(keyframe, activeVertices[0])
                if (newA != null) {
                    activeVertices = listOf(newA, activeVertices[1])
                    pendingReanchorA.set(newA)
                }
            }
            publishLineState(CapturePhase.LOCKED_AND_REFINING)
        }
    }

    private fun commitActiveLineInternal() {
        val verts = activeVertices
        val state = _uiState.value
        val dist = state.activeDistanceMeters
        if (verts.size != 2 || dist == null) return
        completedItems = completedItems + SceneItem(
            id = itemIdGen.incrementAndGet(),
            label = "Line ${countModeItems(MeasurementMode.LINE) + 1}",
            mode = MeasurementMode.LINE,
            vertices = verts.toList(),
            isClosed = false,
            color = state.settings.lineColor,
            primaryMeters = dist,
            ci95Meters = state.activeCi95Meters,
            isMultiView = state.isMultiViewRefined,
            refinedResult = activeRefinedResult,
        )
    }

    private fun rescaleItem(item: SceneItem, cal: ScaleCalibration): SceneItem {
        val v = item.vertices
        return when (item.mode) {
            MeasurementMode.LINE -> {
                if (v.size != 2) return item
                val updatedRefined = item.refinedResult?.let { MeasurementSolver.solve(it.pointA, it.pointB, cal) }
                val rawDist = v[0].distanceTo(v[1])
                item.copy(
                    primaryMeters = updatedRefined?.distanceMeters ?: (rawDist * cal.factor),
                    ci95Meters = updatedRefined?.ci95Meters ?: (1.96 * max(0.004, rawDist * cal.sigmaFactor)),
                    refinedResult = updatedRefined,
                )
            }
            MeasurementMode.PERIMETER -> if (item.isClosed && v.size >= 3) {
                val m = ShapeGeometry.computePolygonMetrics(v, cal.factor)
                item.copy(primaryMeters = m.perimeterMeters, areaSqMeters = m.areaSquareMeters)
            } else {
                item.copy(primaryMeters = ShapeGeometry.computePolylineLength(v, closed = false, scaleFactor = cal.factor))
            }
            MeasurementMode.BOX -> if (v.size == 4) {
                val b = ShapeGeometry.computeBoxMetrics(v[0], v[1], v[2], v[3], cal.factor)
                item.copy(
                    primaryMeters = b.lengthMeters,
                    areaSqMeters = b.footprintAreaSqMeters,
                    volumeCubicMeters = b.volumeCubicMeters,
                    boxDimensions = BoxDimensions(b.lengthMeters, b.widthMeters, b.heightMeters),
                )
            } else item
            MeasurementMode.HEIGHT -> if (v.size == 2) {
                item.copy(primaryMeters = ShapeGeometry.computeVerticalHeight(v[0], v[1], cal.factor))
            } else item
            MeasurementMode.ANGLE, MeasurementMode.LEVEL -> item
        }
    }

    private fun refreshActiveReadout() {
        val state = _uiState.value
        if (state.mode == MeasurementMode.LINE) {
            if (activeVertices.size == 2) publishLineState(CapturePhase.LOCKED_AND_REFINING)
        } else if (activeVertices.isNotEmpty()) {
            publishInProgressMultiVertexState()
        }
    }

    private fun publishInProgressMultiVertexState() {
        val state = _uiState.value
        val mode = state.mode
        val verts = activeVertices
        val unit = state.settings.unitSystem
        val dec = state.settings.decimalPrecision
        val cal = state.calibration

        val (primary, secondary) = when (mode) {
            MeasurementMode.PERIMETER -> {
                val p = if (verts.size >= 2) {
                    UnitFormatter.formatLength(ShapeGeometry.computePolylineLength(verts, false, cal.factor), unit, dec)
                } else null
                val s = when {
                    verts.size >= 3 -> "${verts.size} points · Aim at start pin to close area"
                    verts.size == 2 -> "2 points · Add next corner"
                    else -> null
                }
                p to s
            }
            MeasurementMode.BOX -> {
                val p = when (verts.size) {
                    2 -> "L ${UnitFormatter.formatLength(verts[0].distanceTo(verts[1]) * cal.factor, unit, dec)}"
                    3 -> "${UnitFormatter.formatLength(verts[0].distanceTo(verts[1]) * cal.factor, unit, dec)} × " +
                        UnitFormatter.formatLength(verts[1].distanceTo(verts[2]) * cal.factor, unit, dec)
                    else -> null
                }
                val s = when (verts.size) {
                    1 -> "Step 2/4: Tap length corner"
                    2 -> "Step 3/4: Tap width corner"
                    3 -> "Step 4/4: Tap top edge for height"
                    else -> null
                }
                p to s
            }
            MeasurementMode.ANGLE -> null to when (verts.size) {
                1 -> "Step 2/3: Tap corner vertex"
                2 -> "Step 3/3: Tap second arm endpoint"
                else -> null
            }
            MeasurementMode.HEIGHT -> null to "Step 2/2: Aim at top edge and tap +"
            else -> null to null
        }

        _uiState.update {
            it.copy(
                phase = if (verts.isEmpty()) CapturePhase.ALIGN_A else CapturePhase.ALIGN_B,
                activeVertexCount = verts.size,
                primaryReadout = primary,
                secondaryReadout = secondary,
                accuracyTierLabel = null,
                statusMessage = defaultPromptFor(mode, verts.size, state.overlay.surfaceLocked),
                sceneItems = completedItems,
                errorMessage = null,
            )
        }
    }

    private fun publishCompletedItemState(item: SceneItem, status: String) {
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                phase = CapturePhase.ALIGN_A,
                activeVertexCount = 0,
                primaryReadout = item.formatPrimary(settings.unitSystem, settings.decimalPrecision),
                secondaryReadout = item.formatSecondary(settings.unitSystem, settings.decimalPrecision),
                accuracyTierLabel = "Saved",
                sceneItems = completedItems,
                statusMessage = status,
                errorMessage = null,
            )
        }
    }

    private fun publishLineState(phase: CapturePhase) {
        val verts = activeVertices
        val ptA = verts.getOrNull(0)
        val ptB = verts.getOrNull(1)
        val state = _uiState.value
        val cal = state.calibration
        val unit = state.settings.unitSystem
        val dec = state.settings.decimalPrecision

        val triA = trackerA.triangulatedPoint
        val triB = trackerB.triangulatedPoint
        val refined = if (triA != null && triB != null && triA.position.distanceTo(triB.position) > 1e-4) {
            MeasurementSolver.solve(triA, triB, cal)
        } else null
        activeRefinedResult = refined

        val distMeters: Double?
        val ci95Meters: Double?
        val isMultiView: Boolean
        val parallaxDeg: Double
        val baselineCm: Double
        val tierLabel: String?
        val secondary: String?
        val status: String

        if (refined != null) {
            distMeters = refined.distanceMeters
            ci95Meters = refined.ci95Meters
            isMultiView = true
            parallaxDeg = refined.minParallaxDeg
            baselineCm = refined.minBaselineMeters * 100.0
            tierLabel = when (refined.quality) {
                MeasurementQuality.LOCKED -> "High Accuracy"
                MeasurementQuality.GOOD -> "Good"
                MeasurementQuality.NEEDS_MORE_PARALLAX -> "Refining"
            }
            secondary = if (state.settings.showUncertainty) {
                "${UnitFormatter.formatUncertainty(refined.ci95Meters, unit, dec)} (3D)"
            } else null
            status = refined.guidanceMessage
        } else if (ptA != null && ptB != null && phase == CapturePhase.LOCKED_AND_REFINING) {
            val rawDist = ptB.distanceTo(ptA)
            distMeters = rawDist * cal.factor
            val depthSigma = sqrt(0.0045 * 0.0045 + (rawDist * cal.sigmaFactor) * (rawDist * cal.sigmaFactor))
            ci95Meters = 1.96 * depthSigma
            isMultiView = false
            parallaxDeg = max(triA?.maxParallaxDeg ?: 0.0, triB?.maxParallaxDeg ?: 0.0)
            baselineCm = max(triA?.maxBaselineMeters ?: 0.0, triB?.maxBaselineMeters ?: 0.0) * 100.0
            tierLabel = "Good"
            secondary = if (state.settings.showUncertainty) {
                "${UnitFormatter.formatUncertainty(ci95Meters, unit, dec)} (depth)"
            } else "Slide gently sideways to sharpen"
            status = "Slide gently sideways to sharpen, or tap + for next line"
        } else {
            distMeters = null
            ci95Meters = null
            isMultiView = false
            parallaxDeg = triA?.maxParallaxDeg ?: 0.0
            baselineCm = (triA?.maxBaselineMeters ?: 0.0) * 100.0
            tierLabel = null
            secondary = null
            status = defaultPromptFor(MeasurementMode.LINE, verts.size, state.overlay.surfaceLocked)
        }

        _uiState.update {
            it.copy(
                phase = phase,
                activeVertexCount = verts.size,
                primaryReadout = distMeters?.let { m -> UnitFormatter.formatLength(m, unit, dec) },
                secondaryReadout = secondary,
                accuracyTierLabel = tierLabel,
                activeDistanceMeters = distMeters,
                activeCi95Meters = ci95Meters,
                isMultiViewRefined = isMultiView,
                refinedMeasurement = refined,
                sceneItems = completedItems,
                observationsACount = trackerA.observationCount,
                observationsBCount = trackerB.observationCount,
                baselineCm = baselineCm,
                parallaxDeg = parallaxDeg,
                statusMessage = status,
            )
        }
    }

    private fun countModeItems(mode: MeasurementMode): Int = completedItems.count { it.mode == mode }

    private fun defaultPromptFor(mode: MeasurementMode, vertexCount: Int, surfaceLocked: Boolean): String {
        if (mode == MeasurementMode.LEVEL) return "Place phone flat or along an edge"
        if (!surfaceLocked && vertexCount == 0) return "Move phone gently to detect surface"
        return when (mode) {
            MeasurementMode.LINE -> if (vertexCount == 0) "Aim at start point and tap +" else "Aim at end point and tap +"
            MeasurementMode.PERIMETER -> when (vertexCount) {
                0 -> "Tap + on first corner"
                1 -> "Tap + on second corner"
                2 -> "Tap + on third corner"
                else -> "Tap next corner, or snap to start to close area"
            }
            MeasurementMode.BOX -> when (vertexCount) {
                0 -> "1/4: Tap base corner"
                1 -> "2/4: Tap length corner"
                2 -> "3/4: Tap width corner"
                else -> "4/4: Tap top edge for height"
            }
            MeasurementMode.ANGLE -> when (vertexCount) {
                0 -> "1/3: Tap first arm endpoint"
                1 -> "2/3: Tap corner vertex"
                else -> "3/3: Tap second arm endpoint"
            }
            MeasurementMode.HEIGHT -> if (vertexCount == 0) "1/2: Tap base surface" else "2/2: Tap top edge"
            MeasurementMode.LEVEL -> "Place phone flat or along an edge"
        }
    }

    private fun clearActiveStateInternal() {
        trackerA.reset()
        trackerB.reset()
        pendingReanchorA.set(null)
        pendingReanchorB.set(null)
        activeRefinedResult = null
        activeVertices = emptyList()
    }
}
