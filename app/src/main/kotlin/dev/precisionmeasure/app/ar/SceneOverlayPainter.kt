package dev.precisionmeasure.app.ar

import com.google.ar.core.Plane
import dev.precisionmeasure.app.measurement.FloatingLabelOverlay
import dev.precisionmeasure.app.measurement.MeasurementMode
import dev.precisionmeasure.app.measurement.MeasurementUiState
import dev.precisionmeasure.app.measurement.SceneItem
import dev.precisionmeasure.app.measurement.SnapTargetKind
import dev.precisionmeasure.app.measurement.SurfaceSource
import dev.precisionmeasure.geometry.MeasurementQuality
import dev.precisionmeasure.geometry.MeasurementResult
import dev.precisionmeasure.geometry.ScreenProjector
import dev.precisionmeasure.geometry.ShapeGeometry
import dev.precisionmeasure.geometry.UnitFormatter
import dev.precisionmeasure.geometry.UnitSystem
import dev.precisionmeasure.geometry.Vec2
import dev.precisionmeasure.geometry.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Translates active and completed measurement geometry + optional Dev wireframes into
 * synchronous OpenGL ES primitives on [Overlay3DRenderer] and collision-free screen labels.
 */
class SceneOverlayPainter(
    private val overlay3D: Overlay3DRenderer,
) {

    fun drawPlaneWireframe(plane: Plane, cameraPos: Vec3) {
        val poly = plane.polygon
        val count = poly.remaining() / 2
        if (count < 3) return

        val centerPose = plane.centerPose
        val isVertical = plane.type == Plane.Type.VERTICAL
        val r = if (isVertical) 0.70f else 0.0f
        val g = if (isVertical) 0.40f else 0.85f
        val b = if (isVertical) 1.0f else 0.55f

        val worldVerts = ArrayList<Vec3>(count)
        val local = FloatArray(3)
        val world = FloatArray(3)
        for (i in 0 until count) {
            local[0] = poly.get(i * 2)
            local[1] = 0f
            local[2] = poly.get(i * 2 + 1)
            centerPose.transformPoint(local, 0, world, 0)
            worldVerts.add(Vec3(world[0].toDouble(), world[1].toDouble(), world[2].toDouble()))
        }

        for (i in 0 until count) {
            overlay3D.addRibbon3D(worldVerts[i], worldVerts[(i + 1) % count], cameraPos, 0.0022, r, g, b, 0.72f)
        }
    }

    fun drawTriangulationRays(
        pointA: Vec3?,
        pointB: Vec3?,
        originsA: List<Vec3>,
        originsB: List<Vec3>,
        cameraPos: Vec3,
    ) {
        if (pointA != null) {
            for (camOrigin in originsA) {
                overlay3D.addRibbon3D(camOrigin, pointA, cameraPos, 0.0009, 0.0f, 0.90f, 0.46f, 0.55f)
                val n = (cameraPos - camOrigin).let { if (it.norm() > 1e-4) it.normalized() else Vec3.UNIT_Y }
                overlay3D.addRing3D(camOrigin, n, 0.0, 0.004, 10, 0.0f, 0.90f, 0.46f, 0.85f)
            }
        }
        if (pointB != null) {
            for (camOrigin in originsB) {
                overlay3D.addRibbon3D(camOrigin, pointB, cameraPos, 0.0009, 1.0f, 0.84f, 0.0f, 0.55f)
                val n = (cameraPos - camOrigin).let { if (it.norm() > 1e-4) it.normalized() else Vec3.UNIT_Y }
                overlay3D.addRing3D(camOrigin, n, 0.0, 0.004, 10, 1.0f, 0.84f, 0.0f, 0.85f)
            }
        }
    }

    fun drawSurfaceCursor(surfaceHit: SurfaceHit, cameraPos: Vec3) {
        val camDist = cameraPos.distanceTo(surfaceHit.worldPoint).coerceIn(0.15, 5.0)
        val isMagnetic = surfaceHit.snapKind != SnapTargetKind.NONE
        val outerR = (camDist * (if (isMagnetic) 0.024 else 0.017)).coerceIn(0.005, 0.030)
        val innerR = outerR * 0.72
        overlay3D.addRing3D(surfaceHit.worldPoint, surfaceHit.worldNormal, innerR * 0.86, outerR * 1.14, 24, 0f, 0f, 0f, 0.55f)
        val (cr, cg, cb) = when {
            surfaceHit.snapKind == SnapTargetKind.CLOSE_LOOP -> Triple(0.0f, 0.95f, 0.50f)
            surfaceHit.snapKind == SnapTargetKind.EXISTING_PIN -> Triple(1.0f, 0.84f, 0.0f)
            surfaceHit.source == SurfaceSource.RAW_DEPTH -> Triple(0.0f, 0.90f, 0.46f)
            else -> Triple(0.16f, 0.71f, 0.96f)
        }
        overlay3D.addRing3D(surfaceHit.worldPoint, surfaceHit.worldNormal, innerR, outerR, 24, cr, cg, cb, 0.96f)
    }

    fun drawCompletedItem(
        item: SceneItem,
        pts: List<Vec3>,
        cameraPos: Vec3,
        viewProj: FloatArray,
        viewportW: Int,
        viewportH: Int,
        scaleFactor: Double,
        unit: UnitSystem,
        decimals: Int,
        thickness: Double,
        showTicks: Boolean,
        labels: MutableList<FloatingLabelOverlay>,
    ) {
        val c = item.color
        when (item.mode) {
            MeasurementMode.LINE -> {
                if (pts.size != 2) return
                overlay3D.addCaliperSegment3D(
                    worldA = pts[0], worldB = pts[1], cameraPos = cameraPos, scaleFactor = scaleFactor,
                    r = c.r, g = c.g, b = c.b, alpha = 0.90f, isPreview = false, isActive = false,
                    thicknessScale = thickness, showTicks = showTicks, showHvLegs = false,
                )
                val dist = item.primaryMeters ?: (pts[0].distanceTo(pts[1]) * scaleFactor)
                buildMidpointLabel(
                    viewProj, viewportW, viewportH, pts[0], pts[1],
                    line1 = UnitFormatter.formatLength(dist, unit, decimals),
                    line2 = null, quality = null, isActive = false, isPreview = false, accentArgb = c.argb.toInt(),
                )?.let(labels::add)
            }

            MeasurementMode.PERIMETER -> {
                if (pts.size < 2) return
                if (item.isClosed && pts.size >= 3) {
                    overlay3D.addFilledPolygon3D(pts, c.r, c.g, c.b, 0.22f)
                }
                val edgeCount = if (item.isClosed) pts.size else pts.size - 1
                for (i in 0 until edgeCount) {
                    val p0 = pts[i]
                    val p1 = pts[(i + 1) % pts.size]
                    overlay3D.addCaliperSegment3D(
                        p0, p1, cameraPos, scaleFactor, c.r, c.g, c.b, 0.92f,
                        isPreview = false, isActive = false, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                    buildMidpointLabel(
                        viewProj, viewportW, viewportH, p0, p1,
                        line1 = UnitFormatter.formatLength(p0.distanceTo(p1) * scaleFactor, unit, decimals),
                        line2 = null, quality = null, isActive = false, isPreview = false, accentArgb = c.argb.toInt(),
                    )?.let(labels::add)
                }
                for (p in pts) overlay3D.addAnchorPin3D(p, cameraPos, c.r, c.g, c.b, scale = 0.78)
                if (item.isClosed && pts.size >= 3) {
                    val m = ShapeGeometry.computePolygonMetrics(pts, scaleFactor)
                    buildPointLabel(
                        viewProj, viewportW, viewportH, m.centroid,
                        line1 = UnitFormatter.formatArea(m.areaSquareMeters, unit),
                        line2 = "Perim ${UnitFormatter.formatLength(m.perimeterMeters, unit, decimals)}",
                        isActive = true, accentArgb = c.argb.toInt(),
                    )?.let(labels::add)
                }
            }

            MeasurementMode.BOX -> {
                if (pts.size != 4) return
                val box = try {
                    ShapeGeometry.computeBoxMetrics(pts[0], pts[1], pts[2], pts[3], scaleFactor)
                } catch (_: IllegalArgumentException) {
                    return
                }
                overlay3D.addBoxWireframe3D(box.corners, cameraPos, thickness, c.r, c.g, c.b, 0.92f)
                buildPointLabel(
                    viewProj, viewportW, viewportH, (box.corners[0] + box.corners[6]) * 0.5,
                    line1 = UnitFormatter.formatVolume(box.volumeCubicMeters, unit),
                    line2 = "${UnitFormatter.formatLength(box.lengthMeters, unit, decimals)} × " +
                        "${UnitFormatter.formatLength(box.widthMeters, unit, decimals)} × " +
                        UnitFormatter.formatLength(box.heightMeters, unit, decimals),
                    isActive = true, accentArgb = c.argb.toInt(),
                )?.let(labels::add)
            }

            MeasurementMode.ANGLE -> {
                if (pts.size != 3) return
                overlay3D.addCaliperSegment3D(
                    pts[1], pts[0], cameraPos, scaleFactor, c.r, c.g, c.b, 0.92f,
                    isPreview = false, isActive = false, thicknessScale = thickness,
                    showTicks = false, showHvLegs = false, drawPins = false,
                )
                overlay3D.addCaliperSegment3D(
                    pts[1], pts[2], cameraPos, scaleFactor, c.r, c.g, c.b, 0.92f,
                    isPreview = false, isActive = false, thicknessScale = thickness,
                    showTicks = false, showHvLegs = false, drawPins = false,
                )
                overlay3D.addAngleArc3D(pts[1], pts[0], pts[2], cameraPos, c.r, c.g, c.b, 0.95f, thickness)
                for (p in pts) overlay3D.addAnchorPin3D(p, cameraPos, c.r, c.g, c.b, scale = 0.80)
                val deg = try {
                    ShapeGeometry.computeAngleDegrees(pts[0], pts[1], pts[2])
                } catch (_: IllegalArgumentException) {
                    return
                }
                buildPointLabel(
                    viewProj, viewportW, viewportH, pts[1],
                    line1 = UnitFormatter.formatAngle(deg), line2 = null,
                    isActive = true, accentArgb = c.argb.toInt(),
                )?.let(labels::add)
            }

            MeasurementMode.HEIGHT -> {
                if (pts.size != 2) return
                overlay3D.addPlumbLine3D(pts[0], pts[1], cameraPos, thickness, c.r, c.g, c.b, 0.94f, isPreview = false)
                val h = ShapeGeometry.computeVerticalHeight(pts[0], pts[1], scaleFactor)
                val mid = Vec3(pts[0].x, (pts[0].y + pts[1].y) * 0.5, pts[0].z)
                buildPointLabel(
                    viewProj, viewportW, viewportH, mid,
                    line1 = "H ${UnitFormatter.formatLength(h, unit, decimals)}", line2 = null,
                    isActive = true, accentArgb = c.argb.toInt(),
                )?.let(labels::add)
            }

            MeasurementMode.LEVEL -> Unit
        }
    }

    fun drawActiveMeasurement(
        mode: MeasurementMode,
        activeVerts: List<Vec3>,
        surfaceHit: SurfaceHit?,
        activeRefined: MeasurementResult?,
        cameraPos: Vec3,
        viewProj: FloatArray,
        viewportW: Int,
        viewportH: Int,
        scaleFactor: Double,
        uiSnapshot: MeasurementUiState,
        labels: MutableList<FloatingLabelOverlay>,
    ) {
        val settings = uiSnapshot.settings
        val unit = settings.unitSystem
        val dec = settings.decimalPrecision
        val c = settings.lineColor
        val thickness = settings.lineThickness.multiplier

        when (mode) {
            MeasurementMode.LINE -> drawActiveLine(
                activeVerts, surfaceHit, activeRefined, cameraPos, viewProj, viewportW, viewportH,
                scaleFactor, uiSnapshot, labels,
            )

            MeasurementMode.PERIMETER -> {
                for (i in 0 until (activeVerts.size - 1)) {
                    val p0 = activeVerts[i]
                    val p1 = activeVerts[i + 1]
                    overlay3D.addCaliperSegment3D(
                        p0, p1, cameraPos, scaleFactor, c.r, c.g, c.b, 0.98f,
                        isPreview = false, isActive = true, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                    buildMidpointLabel(
                        viewProj, viewportW, viewportH, p0, p1,
                        line1 = UnitFormatter.formatLength(p0.distanceTo(p1) * scaleFactor, unit, dec),
                        line2 = null, quality = null, isActive = false, isPreview = false, accentArgb = c.argb.toInt(),
                    )?.let(labels::add)
                }
                for (i in activeVerts.indices) {
                    val isStart = i == 0
                    overlay3D.addAnchorPin3D(
                        activeVerts[i], cameraPos,
                        if (isStart) 0.0f else c.r,
                        if (isStart) 0.92f else c.g,
                        if (isStart) 0.48f else c.b,
                        scale = if (isStart) 1.15 else 0.9,
                    )
                }
                if (activeVerts.isNotEmpty() && surfaceHit != null) {
                    val last = activeVerts.last()
                    val closing = surfaceHit.snapKind == SnapTargetKind.CLOSE_LOOP
                    val (pr, pg, pb) = if (closing) Triple(0.0f, 0.95f, 0.50f) else Triple(1.0f, 1.0f, 1.0f)
                    overlay3D.addCaliperSegment3D(
                        last, surfaceHit.worldPoint, cameraPos, scaleFactor, pr, pg, pb, 0.92f,
                        isPreview = true, isActive = true, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                    if (closing && activeVerts.size >= 3) {
                        overlay3D.addFilledPolygon3D(activeVerts, 0.0f, 0.92f, 0.48f, 0.24f)
                    }
                    buildMidpointLabel(
                        viewProj, viewportW, viewportH, last, surfaceHit.worldPoint,
                        line1 = UnitFormatter.formatLength(last.distanceTo(surfaceHit.worldPoint) * scaleFactor, unit, dec),
                        line2 = if (closing) "Close Area" else null,
                        quality = null, isActive = true, isPreview = true,
                    )?.let(labels::add)
                }
            }

            MeasurementMode.BOX -> {
                for (i in 0 until (activeVerts.size - 1)) {
                    overlay3D.addCaliperSegment3D(
                        activeVerts[i], activeVerts[i + 1], cameraPos, scaleFactor, c.r, c.g, c.b, 0.96f,
                        isPreview = false, isActive = true, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                }
                for (p in activeVerts) overlay3D.addAnchorPin3D(p, cameraPos, c.r, c.g, c.b, scale = 0.95)
                if (activeVerts.size == 3 && surfaceHit != null) {
                    try {
                        val box = ShapeGeometry.computeBoxMetrics(
                            activeVerts[0], activeVerts[1], activeVerts[2], surfaceHit.worldPoint, scaleFactor,
                        )
                        overlay3D.addBoxWireframe3D(box.corners, cameraPos, thickness, 1f, 1f, 1f, 0.85f)
                        buildPointLabel(
                            viewProj, viewportW, viewportH, (box.corners[0] + box.corners[6]) * 0.5,
                            line1 = UnitFormatter.formatVolume(box.volumeCubicMeters, unit),
                            line2 = "H ${UnitFormatter.formatLength(box.heightMeters, unit, dec)}",
                            isActive = true,
                        )?.let(labels::add)
                    } catch (_: IllegalArgumentException) {
                        // Height cursor still on base plane.
                    }
                } else if (activeVerts.isNotEmpty() && surfaceHit != null) {
                    val last = activeVerts.last()
                    overlay3D.addCaliperSegment3D(
                        last, surfaceHit.worldPoint, cameraPos, scaleFactor, 1f, 1f, 1f, 0.9f,
                        isPreview = true, isActive = true, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                    buildMidpointLabel(
                        viewProj, viewportW, viewportH, last, surfaceHit.worldPoint,
                        line1 = UnitFormatter.formatLength(last.distanceTo(surfaceHit.worldPoint) * scaleFactor, unit, dec),
                        line2 = null, quality = null, isActive = true, isPreview = true,
                    )?.let(labels::add)
                }
            }

            MeasurementMode.ANGLE -> {
                for (p in activeVerts) overlay3D.addAnchorPin3D(p, cameraPos, c.r, c.g, c.b, scale = 0.95)
                if (activeVerts.size == 1 && surfaceHit != null) {
                    overlay3D.addCaliperSegment3D(
                        activeVerts[0], surfaceHit.worldPoint, cameraPos, scaleFactor, 1f, 1f, 1f, 0.9f,
                        isPreview = true, isActive = true, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                } else if (activeVerts.size == 2) {
                    overlay3D.addCaliperSegment3D(
                        activeVerts[1], activeVerts[0], cameraPos, scaleFactor, c.r, c.g, c.b, 0.98f,
                        isPreview = false, isActive = true, thicknessScale = thickness,
                        showTicks = false, showHvLegs = false, drawPins = false,
                    )
                    if (surfaceHit != null && surfaceHit.worldPoint.distanceTo(activeVerts[1]) > 0.01) {
                        overlay3D.addCaliperSegment3D(
                            activeVerts[1], surfaceHit.worldPoint, cameraPos, scaleFactor, 1f, 1f, 1f, 0.92f,
                            isPreview = true, isActive = true, thicknessScale = thickness,
                            showTicks = false, showHvLegs = false, drawPins = false,
                        )
                        overlay3D.addAngleArc3D(
                            activeVerts[1], activeVerts[0], surfaceHit.worldPoint,
                            cameraPos, c.r, c.g, c.b, 0.92f, thickness,
                        )
                        try {
                            val deg = ShapeGeometry.computeAngleDegrees(activeVerts[0], activeVerts[1], surfaceHit.worldPoint)
                            buildPointLabel(
                                viewProj, viewportW, viewportH, activeVerts[1],
                                line1 = UnitFormatter.formatAngle(deg), line2 = null, isActive = true,
                            )?.let(labels::add)
                        } catch (_: IllegalArgumentException) {
                            // Degenerate angle preview.
                        }
                    }
                }
            }

            MeasurementMode.HEIGHT -> {
                if (activeVerts.size == 1 && surfaceHit != null) {
                    overlay3D.addPlumbLine3D(
                        activeVerts[0], surfaceHit.worldPoint, cameraPos, thickness, c.r, c.g, c.b, 0.95f, isPreview = true,
                    )
                    val h = ShapeGeometry.computeVerticalHeight(activeVerts[0], surfaceHit.worldPoint, scaleFactor)
                    val mid = Vec3(activeVerts[0].x, (activeVerts[0].y + surfaceHit.worldPoint.y) * 0.5, activeVerts[0].z)
                    buildPointLabel(
                        viewProj, viewportW, viewportH, mid,
                        line1 = "H ${UnitFormatter.formatLength(h, unit, dec)}", line2 = null, isActive = true,
                    )?.let(labels::add)
                } else if (activeVerts.size == 1) {
                    overlay3D.addAnchorPin3D(activeVerts[0], cameraPos, 0.0f, 0.90f, 0.46f, scale = 1.0)
                }
            }

            MeasurementMode.LEVEL -> Unit
        }
    }

    fun resolveLabelCollisions(labels: List<FloatingLabelOverlay>, viewportH: Int): List<FloatingLabelOverlay> {
        if (labels.size <= 1) return labels
        val result = labels.toMutableList()
        for (i in 1 until result.size) {
            var c = result[i].screenCenter
            for (j in 0 until i) {
                val other = result[j].screenCenter
                val dx = abs(c.x - other.x)
                val dy = abs(c.y - other.y)
                if (dx < 150.0 && dy < 72.0) {
                    val shift = if (c.y >= other.y) 74.0 - dy else -(74.0 - dy)
                    c = Vec2(c.x, (c.y + shift).coerceIn(170.0, (viewportH - 280).toDouble()))
                }
            }
            result[i] = result[i].copy(screenCenter = c)
        }
        return result
    }

    private fun drawActiveLine(
        activeVerts: List<Vec3>,
        surfaceHit: SurfaceHit?,
        activeRefined: MeasurementResult?,
        cameraPos: Vec3,
        viewProj: FloatArray,
        viewportW: Int,
        viewportH: Int,
        scaleFactor: Double,
        uiSnapshot: MeasurementUiState,
        labels: MutableList<FloatingLabelOverlay>,
    ) {
        val settings = uiSnapshot.settings
        val unit = settings.unitSystem
        val dec = settings.decimalPrecision
        val c = settings.lineColor
        val thickness = settings.lineThickness.multiplier

        if (activeVerts.size >= 2) {
            val (r, g, b) = if (activeRefined != null) qualityRgb(activeRefined.quality) else Triple(c.r, c.g, c.b)
            val haloA = activeRefined?.let { 1.96 * it.pointA.isotropicSigmaMeters } ?: 0.0
            val haloB = activeRefined?.let { 1.96 * it.pointB.isotropicSigmaMeters } ?: 0.0
            overlay3D.addCaliperSegment3D(
                worldA = activeVerts[0], worldB = activeVerts[1], cameraPos = cameraPos, scaleFactor = scaleFactor,
                r = r, g = g, b = b, alpha = 1.0f, isPreview = false, isActive = true,
                thicknessScale = thickness, showTicks = settings.showRulerTicks, showHvLegs = settings.showHvLegs,
                haloRadiusA = haloA, haloRadiusB = haloB,
            )
            val distMeters = uiSnapshot.activeDistanceMeters ?: (activeVerts[0].distanceTo(activeVerts[1]) * scaleFactor)
            val sub = if (settings.showUncertainty) {
                val ci = activeRefined?.ci95Meters ?: uiSnapshot.activeCi95Meters
                ci?.let { "${UnitFormatter.formatUncertainty(it, unit, dec)}${if (activeRefined != null) " (3D)" else ""}" }
            } else {
                null
            }
            buildMidpointLabel(
                viewProj, viewportW, viewportH, activeVerts[0], activeVerts[1],
                line1 = UnitFormatter.formatLength(distMeters, unit, dec), line2 = sub,
                quality = activeRefined?.quality, isActive = true, isPreview = false, accentArgb = c.argb.toInt(),
            )?.let(labels::add)
        } else if (activeVerts.size == 1 && surfaceHit != null) {
            overlay3D.addCaliperSegment3D(
                worldA = activeVerts[0], worldB = surfaceHit.worldPoint, cameraPos = cameraPos, scaleFactor = scaleFactor,
                r = 1.0f, g = 1.0f, b = 1.0f, alpha = 0.92f, isPreview = true, isActive = true,
                thicknessScale = thickness, showTicks = settings.showRulerTicks, showHvLegs = false,
            )
            buildMidpointLabel(
                viewProj, viewportW, viewportH, activeVerts[0], surfaceHit.worldPoint,
                line1 = UnitFormatter.formatLength(activeVerts[0].distanceTo(surfaceHit.worldPoint) * scaleFactor, unit, dec),
                line2 = null, quality = null, isActive = true, isPreview = true,
            )?.let(labels::add)
        } else if (activeVerts.size == 1) {
            overlay3D.addAnchorPin3D(activeVerts[0], cameraPos, 0.0f, 0.90f, 0.46f, scale = 1.1)
        }
    }

    private fun buildPointLabel(
        viewProj: FloatArray,
        viewportW: Int,
        viewportH: Int,
        worldPoint: Vec3,
        line1: String,
        line2: String?,
        isActive: Boolean,
        accentArgb: Int? = null,
    ): FloatingLabelOverlay? {
        val p = ScreenProjector.projectPoint(viewProj, worldPoint, viewportW, viewportH) ?: return null
        val mx = p.x.coerceIn(130.0, (viewportW - 130).toDouble())
        val my = p.y.coerceIn(180.0, (viewportH - 310).toDouble())
        return FloatingLabelOverlay(
            screenCenter = Vec2(mx, my),
            line1 = line1,
            line2 = line2,
            isActive = isActive,
            accentArgb = accentArgb,
        )
    }

    private fun buildMidpointLabel(
        viewProj: FloatArray,
        viewportW: Int,
        viewportH: Int,
        worldA: Vec3,
        worldB: Vec3,
        line1: String,
        line2: String?,
        quality: MeasurementQuality?,
        isActive: Boolean,
        isPreview: Boolean,
        accentArgb: Int? = null,
    ): FloatingLabelOverlay? {
        val seg = ScreenProjector.projectSegmentClipped(viewProj, worldA, worldB, viewportW, viewportH) ?: return null
        val a = seg.first
        val b = seg.second
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).coerceAtLeast(1.0)
        var nx = -dy / len
        var ny = dx / len
        if (ny > 0.0) {
            nx = -nx
            ny = -ny
        }
        val offsetDist = if (isActive) 54.0 else 38.0
        val mx = ((a.x + b.x) * 0.5 + nx * offsetDist).coerceIn(120.0, (viewportW - 120).toDouble())
        val my = ((a.y + b.y) * 0.5 + ny * offsetDist).coerceIn(180.0, (viewportH - 310).toDouble())
        return FloatingLabelOverlay(
            screenCenter = Vec2(mx, my),
            line1 = line1,
            line2 = line2,
            quality = quality,
            isActive = isActive,
            isPreview = isPreview,
            accentArgb = accentArgb,
        )
    }

    private fun qualityRgb(quality: MeasurementQuality): Triple<Float, Float, Float> = when (quality) {
        MeasurementQuality.LOCKED -> Triple(0.0f, 0.90f, 0.46f)
        MeasurementQuality.GOOD -> Triple(1.0f, 0.67f, 0.0f)
        MeasurementQuality.NEEDS_MORE_PARALLAX -> Triple(0.16f, 0.71f, 0.96f)
    }
}
