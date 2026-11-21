package dev.precisionmeasure.app.ar

import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.exceptions.NotYetAvailableException
import dev.precisionmeasure.app.measurement.MeasurementMode
import dev.precisionmeasure.app.measurement.SnapTargetKind
import dev.precisionmeasure.app.measurement.SurfaceSource
import dev.precisionmeasure.geometry.CameraIntrinsics
import dev.precisionmeasure.geometry.CameraPose
import dev.precisionmeasure.geometry.DepthFuser
import dev.precisionmeasure.geometry.DepthPrior
import dev.precisionmeasure.geometry.ScreenProjector
import dev.precisionmeasure.geometry.Vec2
import dev.precisionmeasure.geometry.Vec3

data class SurfaceHit(
    val worldPoint: Vec3,
    val worldNormal: Vec3,
    val source: SurfaceSource,
    val depthPrior: DepthPrior? = null,
    val centerDepthMm: Int = 0,
    val centerConfidence: Int = 0,
    val snapKind: SnapTargetKind = SnapTargetKind.NONE,
)

/**
 * Resolves real 3D surface hits from ARCore (Raw Depth -> Full Depth -> Plane -> 3D Feature Point)
 * and applies magnetic snapping to existing placed pins or loop-closing start vertices.
 */
class SurfaceHitResolver {

    @Volatile
    var lastDepthResolution: String = "-"
        private set

    fun resolve(
        frame: Frame,
        pose: CameraPose,
        intrinsics: CameraIntrinsics,
        reticleImgPixel: Vec2,
        viewportWidth: Int,
        viewportHeight: Int,
        depthApiSupported: Boolean,
    ): SurfaceHit? {
        if (depthApiSupported) {
            val rawInfo = tryExtractRawDepthPrior(frame, pose, reticleImgPixel)
            if (rawInfo != null) {
                val (rawPrior, centerConf) = rawInfo
                val worldPt = unprojectDepthToWorld(pose, intrinsics, reticleImgPixel, rawPrior.depthMeters)
                val normal = (pose.position - worldPt).normalized()
                return SurfaceHit(
                    worldPoint = worldPt,
                    worldNormal = normal,
                    source = SurfaceSource.RAW_DEPTH,
                    depthPrior = rawPrior,
                    centerDepthMm = (rawPrior.depthMeters * 1000.0).toInt(),
                    centerConfidence = centerConf,
                )
            }

            val fullDepthMeters = tryExtractFullDepthMedian(frame, reticleImgPixel)
            if (fullDepthMeters != null) {
                val worldPt = unprojectDepthToWorld(pose, intrinsics, reticleImgPixel, fullDepthMeters)
                val normal = (pose.position - worldPt).normalized()
                return SurfaceHit(
                    worldPoint = worldPt,
                    worldNormal = normal,
                    source = SurfaceSource.FULL_DEPTH,
                    depthPrior = DepthPrior(pose, fullDepthMeters, maxOf(0.025, fullDepthMeters * 0.04)),
                    centerDepthMm = (fullDepthMeters * 1000.0).toInt(),
                    centerConfidence = 0,
                )
            }
        }

        val cx = viewportWidth * 0.5f
        val cy = viewportHeight * 0.5f
        val hits = frame.hitTest(cx, cy)
        val bestHit = hits.firstOrNull { it.trackable is DepthPoint }
            ?: hits.firstOrNull { hit ->
                val plane = hit.trackable as? Plane
                plane != null && plane.isPoseInPolygon(hit.hitPose)
            }
            ?: hits.firstOrNull { hit ->
                val pt = hit.trackable as? Point
                pt != null && pt.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            ?: hits.firstOrNull { it.trackable is Point }

        if (bestHit != null) {
            val t = bestHit.hitPose.translation
            val yAxis = bestHit.hitPose.yAxis
            val worldPt = Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble())
            val normal = if (bestHit.trackable is Plane) {
                Vec3(yAxis[0].toDouble(), yAxis[1].toDouble(), yAxis[2].toDouble()).normalized()
            } else {
                (pose.position - worldPt).let { if (it.norm() > 1e-5) it.normalized() else Vec3.UNIT_Y }
            }
            val src = when (bestHit.trackable) {
                is DepthPoint -> SurfaceSource.FULL_DEPTH
                is Plane -> SurfaceSource.PLANE
                else -> SurfaceSource.FEATURE_POINT
            }
            return SurfaceHit(
                worldPoint = worldPt,
                worldNormal = normal,
                source = src,
                centerDepthMm = (bestHit.distance * 1000f).toInt(),
            )
        }
        return null
    }

    fun applyMagneticPinSnap(
        rawHit: SurfaceHit,
        mode: MeasurementMode,
        activeVerts: List<Vec3>,
        completedGroups: List<List<Vec3>>,
        viewProj: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        pinSnapEnabled: Boolean,
    ): SurfaceHit {
        val screenCenter = Vec2(viewportWidth * 0.5, viewportHeight * 0.5)

        if (mode == MeasurementMode.PERIMETER && activeVerts.size >= 3) {
            val startPt = activeVerts[0]
            val dist3D = rawHit.worldPoint.distanceTo(startPt)
            val proj = ScreenProjector.projectPoint(viewProj, startPt, viewportWidth, viewportHeight)
            val screenDist = proj?.distanceTo(screenCenter) ?: Double.MAX_VALUE
            if (dist3D <= 0.045 || screenDist <= 42.0) {
                return rawHit.copy(worldPoint = startPt, snapKind = SnapTargetKind.CLOSE_LOOP)
            }
        }

        if (!pinSnapEnabled) return rawHit

        var bestPt: Vec3? = null
        var bestScore = Double.MAX_VALUE
        val candidates = ArrayList<Vec3>()
        for (group in completedGroups) candidates.addAll(group)
        if (activeVerts.size > 1) {
            for (i in 0 until activeVerts.size - 1) candidates.add(activeVerts[i])
        }

        for (pt in candidates) {
            val dist3D = rawHit.worldPoint.distanceTo(pt)
            val proj = ScreenProjector.projectPoint(viewProj, pt, viewportWidth, viewportHeight)
            val screenDist = proj?.distanceTo(screenCenter) ?: Double.MAX_VALUE
            if (dist3D <= 0.028 && screenDist <= 34.0 && screenDist < bestScore) {
                bestScore = screenDist
                bestPt = pt
            }
        }
        return if (bestPt != null) {
            rawHit.copy(worldPoint = bestPt, snapKind = SnapTargetKind.EXISTING_PIN)
        } else {
            rawHit
        }
    }

    fun unprojectDepthToWorld(
        pose: CameraPose,
        intrinsics: CameraIntrinsics,
        pixel: Vec2,
        depthMeters: Double,
    ): Vec3 {
        val rayCam = intrinsics.unprojectRay(pixel)
        val pCam = rayCam * (depthMeters / rayCam.z)
        return pose.cameraToWorld(pCam)
    }

    private fun tryExtractRawDepthPrior(
        frame: Frame,
        pose: CameraPose,
        imagePixel: Vec2,
    ): Pair<DepthPrior, Int>? {
        return try {
            frame.acquireRawDepthImage16Bits().use { depthImg ->
                frame.acquireRawDepthConfidenceImage().use { confImg ->
                    lastDepthResolution = "${depthImg.width}x${depthImg.height}"
                    val imgCoord = floatArrayOf(imagePixel.x.toFloat(), imagePixel.y.toFloat())
                    val texNorm = FloatArray(2)
                    frame.transformCoordinates2d(
                        Coordinates2d.IMAGE_PIXELS,
                        imgCoord,
                        Coordinates2d.TEXTURE_NORMALIZED,
                        texNorm,
                    )

                    val dx = (texNorm[0] * depthImg.width).toInt()
                    val dy = (texNorm[1] * depthImg.height).toInt()
                    if (dx !in 0 until depthImg.width || dy !in 0 until depthImg.height) return null

                    val depthPacked = CameraImageExtractor.packPlaneBytes(depthImg.planes[0], depthImg.width * 2, depthImg.height)
                    val confPacked = CameraImageExtractor.packPlaneBytes(confImg.planes[0], confImg.width, confImg.height)
                    val centerConf = confPacked[dy * depthImg.width + dx].toInt() and 0xFF

                    val prior = DepthFuser.extractDepthPrior(
                        pose = pose,
                        depthBufferLe = depthPacked,
                        confidenceBuffer = confPacked,
                        width = depthImg.width,
                        height = depthImg.height,
                        centerX = dx,
                        centerY = dy,
                    ) ?: return null
                    prior to centerConf
                }
            }
        } catch (_: NotYetAvailableException) {
            null
        }
    }

    private fun tryExtractFullDepthMedian(frame: Frame, imagePixel: Vec2): Double? {
        return try {
            frame.acquireDepthImage16Bits().use { depthImg ->
                lastDepthResolution = "${depthImg.width}x${depthImg.height}"
                val imgCoord = floatArrayOf(imagePixel.x.toFloat(), imagePixel.y.toFloat())
                val texNorm = FloatArray(2)
                frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_PIXELS,
                    imgCoord,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    texNorm,
                )
                val cx = (texNorm[0] * depthImg.width).toInt()
                val cy = (texNorm[1] * depthImg.height).toInt()
                if (cx !in 0 until depthImg.width || cy !in 0 until depthImg.height) return null

                val plane = depthImg.planes[0]
                val buf = plane.buffer.duplicate()
                val rowStride = plane.rowStride
                val samples = ArrayList<Int>(25)
                for (dy in -2..2) {
                    val y = (cy + dy).coerceIn(0, depthImg.height - 1)
                    for (dx in -2..2) {
                        val x = (cx + dx).coerceIn(0, depthImg.width - 1)
                        val offset = y * rowStride + x * 2
                        val lo = buf.get(offset).toInt() and 0xFF
                        val hi = buf.get(offset + 1).toInt() and 0xFF
                        val mm = lo or (hi shl 8)
                        if (mm in 80..6000) samples.add(mm)
                    }
                }
                if (samples.size < 3) return null
                samples.sort()
                samples[samples.size / 2] * 1e-3
            }
        } catch (_: NotYetAvailableException) {
            null
        }
    }
}
