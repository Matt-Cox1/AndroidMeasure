package dev.precisionmeasure.app.ar

import android.app.Activity
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import dev.precisionmeasure.app.measurement.DepthMapOverlayMode
import dev.precisionmeasure.app.measurement.DevTelemetry
import dev.precisionmeasure.app.measurement.FloatingLabelOverlay
import dev.precisionmeasure.app.measurement.Keyframe
import dev.precisionmeasure.app.measurement.MeasurementCoordinator
import dev.precisionmeasure.app.measurement.MeasurementMode
import dev.precisionmeasure.app.measurement.ProjectedPointsOverlay
import dev.precisionmeasure.app.measurement.SnapTargetKind
import dev.precisionmeasure.app.measurement.SurfaceSource
import dev.precisionmeasure.geometry.CameraIntrinsics
import dev.precisionmeasure.geometry.CameraPose
import dev.precisionmeasure.geometry.ScreenProjector
import dev.precisionmeasure.geometry.ShapeGeometry
import dev.precisionmeasure.geometry.Vec2
import dev.precisionmeasure.geometry.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Thin ARCore session and OpenGL ES render-loop orchestrator.
 * Delegates surface picking to [SurfaceHitResolver], 3D geometry drawing to [SceneOverlayPainter],
 * and camera/GL pixel extraction to [CameraImageExtractor].
 */
class ArMeasurementEngine(
    private val activity: Activity,
    private val coordinator: MeasurementCoordinator,
) : GLSurfaceView.Renderer {

    private sealed interface EngineCommand {
        data object PlaceVertex : EngineCommand
        data object FinishOpenPolyline : EngineCommand
        data object PinActiveLine : EngineCommand
        data object UndoLast : EngineCommand
        data class SetMode(val mode: MeasurementMode) : EngineCommand
        data class DeleteItem(val id: Long) : EngineCommand
        data object ClearAll : EngineCommand
    }

    private data class SnapshotRequest(
        val onCaptured: (Bitmap, List<FloatingLabelOverlay>) -> Unit,
    )

    private val backgroundRenderer = CameraBackgroundRenderer()
    private val depthOverlayRenderer = DepthOverlayRenderer()
    private val pointCloudRenderer = PointCloudRenderer()
    private val overlay3D = Overlay3DRenderer()
    private val surfaceResolver = SurfaceHitResolver()
    private val scenePainter = SceneOverlayPainter(overlay3D)

    private val commandQueue = ConcurrentLinkedQueue<EngineCommand>()
    private val pendingSnapshot = AtomicReference<SnapshotRequest?>(null)
    private val keyframeIdGen = AtomicLong(0L)

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    @Volatile
    private var session: Session? = null
    private var installRequested = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var displayGeometryDirty = true

    private var appliedFixedFocus = false
    private var torchEnabled = false
    private val torchToggleRequested = AtomicBoolean(false)
    private var depthApiSupported = false
    private var wasTrackingLogged = false
    private var lastLoggedSource = SurfaceSource.NONE

    private var lastLoupeTimeMs = 0L
    private var lastTrackFrameTimeMs = 0L
    private var lastTelemetryTimeMs = 0L
    private var lastFrameNs = 0L
    private var smoothedFps = 30.0
    private var lastLoupeSnapNorm: Vec2? = null

    private val activeAnchors = mutableListOf<Anchor>()
    private val completedAnchorGroups = mutableListOf<MutableList<Anchor>>()

    fun requestPlaceVertex() { commandQueue.add(EngineCommand.PlaceVertex) }
    fun requestFinishOpenPolyline() { commandQueue.add(EngineCommand.FinishOpenPolyline) }
    fun requestPinActiveLine() { commandQueue.add(EngineCommand.PinActiveLine) }
    fun requestUndo() { commandQueue.add(EngineCommand.UndoLast) }
    fun requestModeChange(mode: MeasurementMode) { commandQueue.add(EngineCommand.SetMode(mode)) }
    fun requestDeleteItem(id: Long) { commandQueue.add(EngineCommand.DeleteItem(id)) }
    fun requestClearAll() { commandQueue.add(EngineCommand.ClearAll) }
    fun requestToggleTorch() { torchToggleRequested.set(true) }

    fun requestPhotoSnapshot(onCaptured: (Bitmap, List<FloatingLabelOverlay>) -> Unit) {
        pendingSnapshot.set(SnapshotRequest(onCaptured))
    }

    fun onResume() {
        if (session == null) {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val newSession = Session(activity)
            configureSession(newSession)
            session = newSession
            displayGeometryDirty = true
        }
        session?.resume()
    }

    fun onPause() {
        if (torchEnabled) {
            torchEnabled = false
            session?.let { s ->
                val cfg = s.config.apply { flashMode = Config.FlashMode.OFF }
                s.configure(cfg)
            }
        }
        session?.pause()
    }

    fun onDestroy() {
        detachAllAnchors()
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        depthOverlayRenderer.createOnGlThread()
        pointCloudRenderer.createOnGlThread()
        overlay3D.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        displayGeometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        updateFps()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val arSession = session ?: return

        if (displayGeometryDirty) {
            arSession.setDisplayGeometry(currentDisplayRotation(), viewportWidth, viewportHeight)
            arSession.setCameraTextureName(backgroundRenderer.textureId)
            displayGeometryDirty = false
        }

        val uiSnapshot = coordinator.uiState.value
        val settings = uiSnapshot.settings
        val devOpts = settings.devOptions
        syncSessionConfigIfNeeded(arSession, settings.fixedFocusEnabled)

        val frame = arSession.update()
        backgroundRenderer.draw(frame)

        val placeRequested = drainCommands(uiSnapshot.mode)
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            wasTrackingLogged = false
            coordinator.updateArTelemetry(
                isTracking = false,
                surfaceSource = SurfaceSource.NONE,
                torchEnabled = torchEnabled,
                rawDepthEnabled = depthApiSupported,
                trackingHint = trackingFailureHint(camera.trackingFailureReason),
            )
            return
        }
        if (!wasTrackingLogged) {
            wasTrackingLogged = true
            Log.i("AndroidMeasure", "ARCore entered TRACKING (depthApi=$depthApiSupported)")
        }

        applyPendingReanchors(arSession)
        val (activeWorldVerts, completedWorldGroups) = syncAnchorsToWorld()

        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.05f, 50f)
        val viewProj = ScreenProjector.multiply4x4(projMatrix, viewMatrix)

        val pose = CameraImageExtractor.arPoseToCameraPose(camera.pose)
        val intrinsics = CameraImageExtractor.arIntrinsicsToCameraIntrinsics(camera.imageIntrinsics)
        val mode = uiSnapshot.mode
        val calFactor = uiSnapshot.calibration.factor

        val centerImage = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW,
            floatArrayOf(viewportWidth * 0.5f, viewportHeight * 0.5f),
            Coordinates2d.IMAGE_PIXELS,
            centerImage,
        )
        val reticleImgPixel = Vec2(centerImage[0].toDouble(), centerImage[1].toDouble())

        val rawHit = if (mode == MeasurementMode.LEVEL) {
            null
        } else {
            surfaceResolver.resolve(frame, pose, intrinsics, reticleImgPixel, viewportWidth, viewportHeight, depthApiSupported)
        }
        val surfaceHit = rawHit?.let {
            surfaceResolver.applyMagneticPinSnap(
                it, mode, activeWorldVerts, completedWorldGroups, viewProj,
                viewportWidth, viewportHeight, settings.pinSnapEnabled,
            )
        }

        val hitSource = surfaceHit?.source ?: SurfaceSource.NONE
        if (hitSource != lastLoggedSource) {
            lastLoggedSource = hitSource
            Log.i("AndroidMeasure", "Surface source -> $hitSource")
        }
        coordinator.updateArTelemetry(
            isTracking = true,
            surfaceSource = hitSource,
            torchEnabled = torchEnabled,
            rawDepthEnabled = depthApiSupported,
            centerDepthMm = surfaceHit?.centerDepthMm ?: 0,
        )

        if (depthApiSupported && devOpts.depthMapMode != DepthMapOverlayMode.OFF) {
            renderDevDepthHeatmap(frame, devOpts.depthMapMode)
        }
        val (slamCount, depthCloudCount) = if (devOpts.showPointCloud) {
            renderDevPointCloud(frame, pose, intrinsics, viewProj)
        } else {
            0 to 0
        }

        // Synchronous OpenGL ES 3D geometry pass
        overlay3D.beginFrame()
        val labels = ArrayList<FloatingLabelOverlay>()

        var trackedPlaneCount = 0
        if (devOpts.showPlanes || devOpts.showTelemetryHud) {
            for (plane in arSession.getAllTrackables(Plane::class.java)) {
                if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
                trackedPlaneCount++
                if (devOpts.showPlanes) scenePainter.drawPlaneWireframe(plane, pose.position)
            }
        }
        if (devOpts.showTriangulationRays && mode == MeasurementMode.LINE) {
            scenePainter.drawTriangulationRays(
                activeWorldVerts.getOrNull(0), activeWorldVerts.getOrNull(1),
                coordinator.rayOriginsA, coordinator.rayOriginsB, pose.position,
            )
        }

        val completedItems = coordinator.completedItems
        for (i in completedItems.indices) {
            val item = completedItems[i]
            val pts = completedWorldGroups.getOrNull(i)
            if (!pts.isNullOrEmpty() && pts.size == item.vertices.size) {
                scenePainter.drawCompletedItem(
                    item, pts, pose.position, viewProj, viewportWidth, viewportHeight,
                    calFactor, settings.unitSystem, settings.decimalPrecision,
                    settings.lineThickness.multiplier, settings.showRulerTicks, labels,
                )
            }
        }

        if (mode != MeasurementMode.LEVEL) {
            scenePainter.drawActiveMeasurement(
                mode, activeWorldVerts, surfaceHit, coordinator.activeRefinedResult,
                pose.position, viewProj, viewportWidth, viewportHeight, calFactor, uiSnapshot, labels,
            )
        }

        val isAiming = mode != MeasurementMode.LEVEL && !(mode == MeasurementMode.LINE && activeWorldVerts.size >= 2)
        if (surfaceHit != null && isAiming) {
            scenePainter.drawSurfaceCursor(surfaceHit, pose.position)
        }

        overlay3D.flush(viewProj)

        val resolvedLabels = scenePainter.resolveLabelCollisions(labels, viewportHeight)
        pendingSnapshot.getAndSet(null)?.let { req ->
            req.onCaptured(CameraImageExtractor.captureGlFramebuffer(viewportWidth, viewportHeight), resolvedLabels)
        }

        val nowMs = System.currentTimeMillis()
        if (devOpts.showTelemetryHud && (nowMs - lastTelemetryTimeMs) >= 200L) {
            lastTelemetryTimeMs = nowMs
            coordinator.updateDevTelemetry(
                DevTelemetry(
                    fps = smoothedFps,
                    focalFx = intrinsics.fx,
                    focalFy = intrinsics.fy,
                    cpuImageSize = "${intrinsics.width}x${intrinsics.height}",
                    depthImageSize = surfaceResolver.lastDepthResolution,
                    centerDepthMm = surfaceHit?.centerDepthMm ?: 0,
                    centerRawConfidence = surfaceHit?.centerConfidence ?: 0,
                    slamFeatureCount = slamCount,
                    depthCloudCount = depthCloudCount,
                    planeCount = trackedPlaneCount,
                    cameraPosMeters = pose.position,
                ),
            )
        }

        val loupeBitmap = processCpuImageTasks(
            arSession, frame, pose, intrinsics, reticleImgPixel, mode, surfaceHit,
            placeRequested, isAiming, settings.loupeEnabled, settings.loupeZoom.patchSizePx,
            settings.cornerSnapEnabled, nowMs,
        )

        coordinator.updateOverlayAndLoupe(
            overlay = ProjectedPointsOverlay(
                surfaceLocked = surfaceHit != null,
                snapTargetKind = surfaceHit?.snapKind ?: SnapTargetKind.NONE,
                labels = resolvedLabels,
                loupeSnapOffsetNorm = lastLoupeSnapNorm,
            ),
            loupeBitmap = loupeBitmap,
        )
    }

    private fun configureSession(arSession: Session) {
        val filter = CameraConfigFilter(arSession).apply {
            facingDirection = CameraConfig.FacingDirection.BACK
        }
        arSession.getSupportedCameraConfigs(filter)
            .maxByOrNull { it.imageSize.width * it.imageSize.height }
            ?.let { arSession.cameraConfig = it }

        val initialFixed = coordinator.uiState.value.settings.fixedFocusEnabled
        appliedFixedFocus = initialFixed
        torchEnabled = false
        depthApiSupported = arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config = Config(arSession).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            instantPlacementMode = Config.InstantPlacementMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            focusMode = if (initialFixed) Config.FocusMode.FIXED else Config.FocusMode.AUTO
            depthMode = if (depthApiSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        arSession.configure(config)
    }

    private fun syncSessionConfigIfNeeded(arSession: Session, wantFixedFocus: Boolean) {
        var needsReconfig = false
        if (wantFixedFocus != appliedFixedFocus) {
            appliedFixedFocus = wantFixedFocus
            needsReconfig = true
        }
        if (torchToggleRequested.getAndSet(false)) {
            torchEnabled = !torchEnabled
            needsReconfig = true
        }
        if (needsReconfig) {
            val updated = arSession.config.apply {
                focusMode = if (appliedFixedFocus) Config.FocusMode.FIXED else Config.FocusMode.AUTO
                flashMode = if (torchEnabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
            }
            arSession.configure(updated)
        }
    }

    private fun drainCommands(currentMode: MeasurementMode): Boolean {
        var placeRequested = false
        while (true) {
            val cmd = commandQueue.poll() ?: break
            when (cmd) {
                EngineCommand.PlaceVertex -> placeRequested = true
                EngineCommand.FinishOpenPolyline -> {
                    if (currentMode == MeasurementMode.PERIMETER && activeAnchors.size >= 2) {
                        completedAnchorGroups.add(activeAnchors.toMutableList())
                        activeAnchors.clear()
                        coordinator.finishOpenPolyline()
                    }
                }
                EngineCommand.PinActiveLine -> {
                    if (activeAnchors.size == 2) {
                        completedAnchorGroups.add(activeAnchors.toMutableList())
                        activeAnchors.clear()
                    } else {
                        detachActiveAnchors()
                    }
                    coordinator.pinActiveLineAndStartNew()
                }
                EngineCommand.UndoLast -> {
                    when {
                        activeAnchors.isNotEmpty() -> {
                            activeAnchors.removeAt(activeAnchors.lastIndex).detach()
                            coordinator.undoLast()
                        }
                        completedAnchorGroups.isNotEmpty() -> {
                            completedAnchorGroups.removeAt(completedAnchorGroups.lastIndex).forEach { it.detach() }
                            coordinator.undoLast()
                        }
                    }
                }
                is EngineCommand.SetMode -> {
                    if (currentMode == MeasurementMode.LINE && activeAnchors.size == 2) {
                        completedAnchorGroups.add(activeAnchors.toMutableList())
                        activeAnchors.clear()
                    } else {
                        detachActiveAnchors()
                    }
                    coordinator.setMode(cmd.mode)
                }
                is EngineCommand.DeleteItem -> {
                    val idx = coordinator.completedItems.indexOfFirst { it.id == cmd.id }
                    if (idx in completedAnchorGroups.indices) {
                        completedAnchorGroups.removeAt(idx).forEach { it.detach() }
                    }
                    coordinator.deleteSceneItem(cmd.id)
                }
                EngineCommand.ClearAll -> {
                    detachAllAnchors()
                    coordinator.clearAll()
                }
            }
        }
        return placeRequested
    }

    private fun applyPendingReanchors(arSession: Session) {
        coordinator.pendingReanchorA.getAndSet(null)?.let { refinedA ->
            if (activeAnchors.isNotEmpty()) {
                activeAnchors[0].detach()
                activeAnchors[0] = createAnchorAt(arSession, refinedA)
            }
        }
        coordinator.pendingReanchorB.getAndSet(null)?.let { refinedB ->
            if (activeAnchors.size >= 2) {
                activeAnchors[1].detach()
                activeAnchors[1] = createAnchorAt(arSession, refinedB)
            }
        }
    }

    private fun syncAnchorsToWorld(): Pair<List<Vec3>, List<List<Vec3>>> {
        val activeWorldVerts = activeAnchors.mapNotNull { a ->
            if (a.trackingState == TrackingState.TRACKING) anchorToVec3(a) else null
        }
        if (activeWorldVerts.size == activeAnchors.size && activeWorldVerts.isNotEmpty()) {
            coordinator.syncActiveVerticesFromAnchors(activeWorldVerts)
        }
        val completedWorldGroups = ArrayList<List<Vec3>>(completedAnchorGroups.size)
        for (i in completedAnchorGroups.indices) {
            val group = completedAnchorGroups[i]
            val pts = group.mapNotNull { a ->
                if (a.trackingState == TrackingState.TRACKING) anchorToVec3(a) else null
            }
            if (pts.size == group.size) {
                completedWorldGroups.add(pts)
                coordinator.syncCompletedItemVertices(i, pts)
            } else {
                completedWorldGroups.add(emptyList())
            }
        }
        return activeWorldVerts to completedWorldGroups
    }

    private fun processCpuImageTasks(
        arSession: Session,
        frame: Frame,
        pose: CameraPose,
        intrinsics: CameraIntrinsics,
        reticleImgPixel: Vec2,
        mode: MeasurementMode,
        surfaceHit: SurfaceHit?,
        placeRequested: Boolean,
        isAiming: Boolean,
        loupeEnabled: Boolean,
        loupePatchSize: Int,
        cornerSnapEnabled: Boolean,
        nowMs: Long,
    ): Bitmap? {
        val needLoupe = loupeEnabled && isAiming && (nowMs - lastLoupeTimeMs) >= LOUPE_REFRESH_MS
        val needTracking = coordinator.isReadyForTrackingFrame() && (nowMs - lastTrackFrameTimeMs) >= TRACKING_FRAME_INTERVAL_MS
        if (!placeRequested && !needLoupe && !needTracking) return null

        var loupeBitmap: Bitmap? = null
        var handledPlacement = false
        try {
            frame.acquireCameraImage().use { cpuImage ->
                if (needLoupe) {
                    val loupe = CameraImageExtractor.extractLoupe(cpuImage, reticleImgPixel, loupePatchSize, cornerSnapEnabled)
                    loupeBitmap = loupe.bitmap
                    lastLoupeSnapNorm = loupe.snapOffsetNorm
                    lastLoupeTimeMs = nowMs
                }
                if (placeRequested && surfaceHit != null) {
                    val kf = if (mode == MeasurementMode.LINE) {
                        Keyframe(
                            id = keyframeIdGen.incrementAndGet(),
                            timestampNs = frame.timestamp,
                            grayscale = CameraImageExtractor.extractYPlane(cpuImage),
                            pose = pose,
                            intrinsics = intrinsics,
                        )
                    } else {
                        null
                    }
                    executeVertexPlacement(arSession, mode, surfaceHit, kf, reticleImgPixel)
                    lastTrackFrameTimeMs = nowMs
                    handledPlacement = true
                } else if (needTracking) {
                    coordinator.onTrackingFrame(
                        Keyframe(
                            id = keyframeIdGen.incrementAndGet(),
                            timestampNs = frame.timestamp,
                            grayscale = CameraImageExtractor.extractYPlane(cpuImage),
                            pose = pose,
                            intrinsics = intrinsics,
                        ),
                    )
                    lastTrackFrameTimeMs = nowMs
                }
            }
        } catch (_: NotYetAvailableException) {
            // CPU image may not be ready on the current frame.
        }

        if (placeRequested && !handledPlacement && surfaceHit != null) {
            executeVertexPlacement(arSession, mode, surfaceHit, keyframe = null, reticleImgPixel = reticleImgPixel)
        }
        return loupeBitmap
    }

    private fun executeVertexPlacement(
        arSession: Session,
        mode: MeasurementMode,
        hit: SurfaceHit,
        keyframe: Keyframe?,
        reticleImgPixel: Vec2,
    ) {
        when (mode) {
            MeasurementMode.LINE -> {
                if (activeAnchors.size >= 2) {
                    completedAnchorGroups.add(activeAnchors.toMutableList())
                    activeAnchors.clear()
                }
                activeAnchors.add(createAnchorForHit(arSession, hit))
                coordinator.onVertexPlaced(keyframe, reticleImgPixel, hit.depthPrior, hit.worldPoint)
            }
            MeasurementMode.PERIMETER -> {
                val closeLoop = hit.snapKind == SnapTargetKind.CLOSE_LOOP && activeAnchors.size >= 3
                if (closeLoop) {
                    completedAnchorGroups.add(activeAnchors.toMutableList())
                    activeAnchors.clear()
                } else {
                    activeAnchors.add(createAnchorForHit(arSession, hit))
                }
                coordinator.onVertexPlaced(null, reticleImgPixel, hit.depthPrior, hit.worldPoint, closeLoop)
            }
            MeasurementMode.BOX -> {
                activeAnchors.add(createAnchorForHit(arSession, hit))
                if (activeAnchors.size == 4) {
                    val pts = activeAnchors.map { anchorToVec3(it) }
                    val valid = try {
                        ShapeGeometry.computeBoxMetrics(pts[0], pts[1], pts[2], pts[3])
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                    if (valid) {
                        completedAnchorGroups.add(activeAnchors.toMutableList())
                        activeAnchors.clear()
                    } else {
                        activeAnchors.removeAt(activeAnchors.lastIndex).detach()
                    }
                }
                coordinator.onVertexPlaced(null, reticleImgPixel, hit.depthPrior, hit.worldPoint)
            }
            MeasurementMode.ANGLE -> {
                activeAnchors.add(createAnchorForHit(arSession, hit))
                if (activeAnchors.size == 3) {
                    val pts = activeAnchors.map { anchorToVec3(it) }
                    if (pts[0].distanceTo(pts[1]) > 1e-4 && pts[2].distanceTo(pts[1]) > 1e-4) {
                        completedAnchorGroups.add(activeAnchors.toMutableList())
                        activeAnchors.clear()
                    } else {
                        activeAnchors.removeAt(activeAnchors.lastIndex).detach()
                    }
                }
                coordinator.onVertexPlaced(null, reticleImgPixel, hit.depthPrior, hit.worldPoint)
            }
            MeasurementMode.HEIGHT -> {
                activeAnchors.add(createAnchorForHit(arSession, hit))
                if (activeAnchors.size == 2) {
                    completedAnchorGroups.add(activeAnchors.toMutableList())
                    activeAnchors.clear()
                }
                coordinator.onVertexPlaced(null, reticleImgPixel, hit.depthPrior, hit.worldPoint)
            }
            MeasurementMode.LEVEL -> Unit
        }
    }

    private fun createAnchorForHit(arSession: Session, hit: SurfaceHit): Anchor {
        // Attach directly to the ARCore Plane/Point trackable whenever available so pins stay
        // physically tethered as ARCore refines surface elevation.
        val hitResult = hit.hitResult
        return if (hitResult != null && hit.snapKind == SnapTargetKind.NONE) {
            hitResult.createAnchor()
        } else {
            createAnchorAt(arSession, hit.worldPoint)
        }
    }

    private fun renderDevDepthHeatmap(frame: Frame, mode: DepthMapOverlayMode) {
        try {
            when (mode) {
                DepthMapOverlayMode.FULL_DEPTH -> frame.acquireDepthImage16Bits().use {
                    depthOverlayRenderer.updateFromDepthImage(it, confidenceImage = null)
                }
                DepthMapOverlayMode.RAW_DEPTH -> frame.acquireRawDepthImage16Bits().use { rawImg ->
                    frame.acquireRawDepthConfidenceImage().use { confImg ->
                        depthOverlayRenderer.updateFromDepthImage(rawImg, confImg, minConfidence = 80)
                    }
                }
                DepthMapOverlayMode.OFF -> return
            }
            depthOverlayRenderer.draw(frame, opacity = 0.55f)
        } catch (_: NotYetAvailableException) {
            // Depth frame not ready yet.
        }
    }

    private fun renderDevPointCloud(
        frame: Frame,
        pose: CameraPose,
        intrinsics: CameraIntrinsics,
        viewProj: FloatArray,
    ): Pair<Int, Int> {
        pointCloudRenderer.beginFrame()
        var slamCount = 0
        var depthCloudCount = 0

        frame.acquirePointCloud().use { cloud ->
            val pts = cloud.points
            val count = pts.remaining() / 4
            for (i in 0 until count) {
                val conf = pts.get(i * 4 + 3)
                if (conf > 0.1f) {
                    pointCloudRenderer.addPoint(
                        Vec3(pts.get(i * 4).toDouble(), pts.get(i * 4 + 1).toDouble(), pts.get(i * 4 + 2).toDouble()),
                        0.75f, 0.96f, 1.0f, (0.45f + 0.55f * conf).coerceAtMost(1f),
                    )
                    slamCount++
                }
            }
        }

        if (depthApiSupported) {
            try {
                frame.acquireRawDepthImage16Bits().use { rawImg ->
                    frame.acquireRawDepthConfidenceImage().use { confImg ->
                        val dw = rawImg.width
                        val dh = rawImg.height
                        val dBuf = rawImg.planes[0].buffer.duplicate()
                        val dStride = rawImg.planes[0].rowStride
                        val cBuf = confImg.planes[0].buffer.duplicate()
                        val cStride = confImg.planes[0].rowStride
                        val scaleX = intrinsics.width.toDouble() / dw.toDouble()
                        val scaleY = intrinsics.height.toDouble() / dh.toDouble()
                        val step = maxOf(2, dw / 56)

                        var y = 0
                        while (y < dh) {
                            val dRow = y * dStride
                            val cRow = y * cStride
                            var x = 0
                            while (x < dw) {
                                if ((cBuf.get(cRow + x).toInt() and 0xFF) >= 90) {
                                    val mm = (dBuf.get(dRow + x * 2).toInt() and 0xFF) or
                                        ((dBuf.get(dRow + x * 2 + 1).toInt() and 0xFF) shl 8)
                                    if (mm in 150..3500) {
                                        val meters = mm * 1e-3
                                        val worldPt = surfaceResolver.unprojectDepthToWorld(
                                            pose, intrinsics, Vec2((x + 0.5) * scaleX, (y + 0.5) * scaleY), meters,
                                        )
                                        val t = (1.0f - ((meters.toFloat() - 0.15f) / 2.35f)).coerceIn(0f, 1f)
                                        val rgb = DepthOverlayRenderer.turboColor(t)
                                        pointCloudRenderer.addPoint(
                                            worldPt,
                                            ((rgb ushr 16) and 0xFF) / 255f,
                                            ((rgb ushr 8) and 0xFF) / 255f,
                                            (rgb and 0xFF) / 255f,
                                            0.85f,
                                        )
                                        depthCloudCount++
                                    }
                                }
                                x += step
                            }
                            y += step
                        }
                    }
                }
            } catch (_: NotYetAvailableException) {
                // Raw depth not ready yet.
            }
        }

        pointCloudRenderer.flush(viewProj, pointSizePx = 7.5f)
        return slamCount to depthCloudCount
    }

    private fun trackingFailureHint(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — tap flashlight icon"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast — slow down slightly"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at a textured surface and move gently"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> "Initializing tracking — sweep phone gently"
    }

    private fun updateFps() {
        val nowNs = System.nanoTime()
        if (lastFrameNs != 0L) {
            val dtSec = (nowNs - lastFrameNs) * 1e-9
            if (dtSec > 1e-4) smoothedFps = smoothedFps * 0.88 + (1.0 / dtSec) * 0.12
        }
        lastFrameNs = nowNs
    }

    private fun createAnchorAt(arSession: Session, p: Vec3): Anchor =
        arSession.createAnchor(Pose.makeTranslation(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))

    private fun anchorToVec3(anchor: Anchor): Vec3 {
        val t = anchor.pose.translation
        return Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble())
    }

    private fun detachActiveAnchors() {
        activeAnchors.forEach { it.detach() }
        activeAnchors.clear()
    }

    private fun detachAllAnchors() {
        detachActiveAnchors()
        completedAnchorGroups.forEach { group -> group.forEach { it.detach() } }
        completedAnchorGroups.clear()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

    companion object {
        private const val LOUPE_REFRESH_MS = 85L
        private const val TRACKING_FRAME_INTERVAL_MS = 70L
    }
}
