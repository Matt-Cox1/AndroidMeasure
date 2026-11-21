package dev.precisionmeasure.app.measurement

import dev.precisionmeasure.geometry.DepthPrior
import dev.precisionmeasure.geometry.Point3DResult
import dev.precisionmeasure.geometry.RayObservation
import dev.precisionmeasure.geometry.SubPixelTracker
import dev.precisionmeasure.geometry.TrackResult
import dev.precisionmeasure.geometry.TrackerConfig
import dev.precisionmeasure.geometry.TriangulationConfig
import dev.precisionmeasure.geometry.TriangulationResult
import dev.precisionmeasure.geometry.Triangulator
import dev.precisionmeasure.geometry.Vec2
import dev.precisionmeasure.geometry.Vec3

/**
 * Reusable single-endpoint optical tracker and strictly gated Huber multi-view triangulator.
 * Eliminates duplicated A/B tracking logic in [MeasurementCoordinator].
 */
class EndpointTracker {

    private var anchorSeed: Vec3? = null
    private var refKeyframe: Keyframe? = null
    private var refPixel: Vec2? = null
    private var prevKeyframe: Keyframe? = null
    private var prevPixel: Vec2? = null
    private var lastObsPos: Vec3? = null
    private var depthPrior: DepthPrior? = null
    private val observations = mutableListOf<RayObservation>()

    var triangulatedPoint: Point3DResult? = null
        private set

    @Volatile
    var rayOrigins: List<Vec3> = emptyList()
        private set

    val observationCount: Int
        get() = observations.size

    val isFull: Boolean
        get() = observations.size >= MAX_OBSERVATIONS

    fun reset() {
        anchorSeed = null
        refKeyframe = null
        refPixel = null
        prevKeyframe = null
        prevPixel = null
        lastObsPos = null
        depthPrior = null
        observations.clear()
        rayOrigins = emptyList()
        triangulatedPoint = null
    }

    fun initialize(
        anchoredWorldPoint: Vec3,
        keyframe: Keyframe?,
        rawImagePixel: Vec2,
        depthPrior: DepthPrior?,
        cornerSnapEnabled: Boolean,
    ) {
        reset()
        anchorSeed = anchoredWorldPoint
        this.depthPrior = depthPrior
        if (keyframe == null) return

        val chosenPixel = if (cornerSnapEnabled) {
            SubPixelTracker.snapToBestCorner(keyframe.grayscale, rawImagePixel).point
        } else {
            rawImagePixel
        }
        refKeyframe = keyframe
        refPixel = chosenPixel
        prevKeyframe = keyframe
        prevPixel = chosenPixel
        lastObsPos = keyframe.pose.position
        observations.add(RayObservation(keyframe.pose, keyframe.intrinsics, chosenPixel, sigmaPx = 0.30))
        rayOrigins = observations.map { it.pose.position }
    }

    /**
     * Tracks the endpoint into [curr] using the stable [currentWorldPoint] as initial guess.
     * Returns the strictly gated refined 3D world position if triangulation upgraded, or null.
     */
    fun trackFrame(curr: Keyframe, currentWorldPoint: Vec3?): Pair<Boolean, Vec3?> {
        if (isFull || currentWorldPoint == null) return false to null
        val prevKf = prevKeyframe ?: return false to null
        val prevPx = prevPixel ?: return false to null
        val refKf = refKeyframe ?: return false to null
        val refPx = refPixel ?: return false to null
        val seed = anchorSeed ?: return false to null

        val guess = curr.pose.projectWorldPoint(currentWorldPoint, curr.intrinsics)
            ?.takeIf { curr.intrinsics.isInsideImage(it, marginPx = 14.0) }
            ?: return false to null

        val tracked = SubPixelTracker.trackPoint(prevKf.grayscale, prevPx, curr.grayscale, guess, FRAME_TRACK_CONFIG)
        if (tracked !is TrackResult.Tracked) return false to null

        val verified = SubPixelTracker.trackPoint(refKf.grayscale, refPx, curr.grayscale, tracked.point, REF_VERIFY_CONFIG)
        val finalPt = if (verified is TrackResult.Tracked) verified.point else tracked.point
        val finalSigma = if (verified is TrackResult.Tracked) verified.estimatedSigmaPx else tracked.estimatedSigmaPx * 1.4

        prevKeyframe = curr
        prevPixel = finalPt

        val lastPos = lastObsPos
        if (lastPos != null && curr.pose.position.distanceTo(lastPos) < MIN_OBS_BASELINE_STEP_METERS) {
            return false to null
        }

        lastObsPos = curr.pose.position
        observations.add(RayObservation(curr.pose, curr.intrinsics, finalPt, sigmaPx = finalSigma))
        rayOrigins = observations.map { it.pose.position }

        var upgradedPos: Vec3? = null
        if (observations.size >= MIN_UPGRADE_VIEWS) {
            val tri = Triangulator.triangulate(observations, depthPrior, STRICT_TRIANGULATION_CONFIG)
            if (tri is TriangulationResult.Success &&
                tri.point.inlierCount >= MIN_UPGRADE_VIEWS &&
                tri.point.position.distanceTo(seed) <= MAX_REFINEMENT_SHIFT_METERS
            ) {
                triangulatedPoint = tri.point
                upgradedPos = tri.point.position
            }
        }
        return true to upgradedPos
    }

    companion object {
        private const val MIN_OBS_BASELINE_STEP_METERS = 0.015
        private const val MAX_OBSERVATIONS = 20
        private const val MIN_UPGRADE_VIEWS = 4
        private const val MIN_UPGRADE_PARALLAX_DEG = 8.0
        private const val MAX_UPGRADE_RMS_PX = 1.5
        private const val MAX_REFINEMENT_SHIFT_METERS = 0.12

        private val FRAME_TRACK_CONFIG = TrackerConfig(
            patchRadiusPx = 7,
            pyramidLevels = 3,
            maxIterationsPerLevel = 16,
            minGradientEnergy = 4.0,
            minZncc = 0.78,
            maxForwardBackwardErrorPx = 0.65,
        )

        private val REF_VERIFY_CONFIG = TrackerConfig(
            patchRadiusPx = 7,
            pyramidLevels = 2,
            maxIterationsPerLevel = 10,
            minGradientEnergy = 4.0,
            minZncc = 0.75,
            maxForwardBackwardErrorPx = 0.60,
        )

        private val STRICT_TRIANGULATION_CONFIG = TriangulationConfig(
            huberDeltaPx = 1.5,
            minParallaxDeg = MIN_UPGRADE_PARALLAX_DEG,
            maxReprojectionRmsPx = MAX_UPGRADE_RMS_PX,
            outlierThresholdPx = 3.0,
        )
    }
}
