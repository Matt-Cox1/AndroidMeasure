package dev.precisionmeasure.geometry

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RayObservation(
    val pose: CameraPose,
    val intrinsics: CameraIntrinsics,
    val pixel: Vec2,
    val sigmaPx: Double = 0.5,
) {
    init {
        require(sigmaPx > 0.0 && sigmaPx.isFinite()) { "sigmaPx must be positive: $sigmaPx" }
    }
}

/**
 * Optional metric depth prior (e.g., high-confidence ARCore Raw Depth) along a camera's +Z axis.
 */
data class DepthPrior(
    val pose: CameraPose,
    val depthMeters: Double,
    val sigmaMeters: Double,
) {
    init {
        require(depthMeters > 0.05 && depthMeters.isFinite()) { "Invalid depthMeters: $depthMeters" }
        require(sigmaMeters > 1e-4 && sigmaMeters.isFinite()) { "Invalid sigmaMeters: $sigmaMeters" }
    }
}

data class TriangulationConfig(
    val huberDeltaPx: Double = 1.5,
    val maxIterations: Int = 30,
    val convergenceStepMeters: Double = 1e-7,
    val minParallaxDeg: Double = 2.0,
    val maxReprojectionRmsPx: Double = 4.0,
    val minDepthMeters: Double = 0.05,
    val maxDepthMeters: Double = 20.0,
    val outlierThresholdPx: Double = 4.0,
)

data class Point3DResult(
    val position: Vec3,
    val covarianceMeters2: Mat3,
    val reprojectionRmsPx: Double,
    val maxParallaxDeg: Double,
    val maxBaselineMeters: Double,
    val inlierCount: Int,
    val totalObservations: Int,
) {
    /** Isotropic 1-sigma position uncertainty sqrt(trace(Cov) / 3) in meters. */
    val isotropicSigmaMeters: Double
        get() = sqrt(max(0.0, covarianceMeters2.trace() / 3.0))

    /** 1-sigma uncertainty projected along a unit direction [unitDir]. */
    fun sigmaAlong(unitDir: Vec3): Double {
        val u = unitDir.normalized()
        return sqrt(max(0.0, covarianceMeters2.quadraticForm(u)))
    }
}

sealed class TriangulationResult {
    data class Success(val point: Point3DResult) : TriangulationResult()
    data class InsufficientGeometry(
        val reason: String,
        val maxParallaxDeg: Double,
        val maxBaselineMeters: Double,
        val initialEstimate: Vec3? = null,
    ) : TriangulationResult()
}

/**
 * Multi-view 3D point triangulator using linear ray midpoint initialization followed by
 * Huber-robust Levenberg-Marquardt reprojection minimization and exact covariance propagation.
 */
object Triangulator {

    fun triangulate(
        observations: List<RayObservation>,
        depthPrior: DepthPrior? = null,
        config: TriangulationConfig = TriangulationConfig(),
    ): TriangulationResult {
        if (observations.size < 2) {
            return TriangulationResult.InsufficientGeometry(
                reason = "Need at least 2 views (have ${observations.size})",
                maxParallaxDeg = 0.0,
                maxBaselineMeters = 0.0,
            )
        }

        val maxBaseline = computeMaxBaseline(observations)
        if (maxBaseline < 1e-3) {
            return TriangulationResult.InsufficientGeometry(
                reason = "Camera has not moved yet; slide sideways",
                maxParallaxDeg = 0.0,
                maxBaselineMeters = maxBaseline,
            )
        }

        val initial = triangulateLinearMidpoint(observations)
            ?: return TriangulationResult.InsufficientGeometry(
                reason = "Rays are nearly parallel; slide sideways to build parallax",
                maxParallaxDeg = 0.0,
                maxBaselineMeters = maxBaseline,
            )

        // Precompute undistorted normalized coordinates for every observation.
        val normalizedTargets = observations.map { it.intrinsics.unprojectToNormalized(it.pixel) }

        val refined = refineLevenbergMarquardt(
            initialPoint = initial,
            observations = observations,
            normalizedTargets = normalizedTargets,
            depthPrior = depthPrior,
            config = config,
        )

        // Validate positive depth and compute residuals.
        val residualsPx = DoubleArray(observations.size)
        var validViews = 0
        for (i in observations.indices) {
            val obs = observations[i]
            val pCam = obs.pose.worldToCamera(refined)
            if (pCam.z < config.minDepthMeters || pCam.z > config.maxDepthMeters) {
                residualsPx[i] = Double.POSITIVE_INFINITY
                continue
            }
            val ex = obs.intrinsics.fx * (pCam.x / pCam.z - normalizedTargets[i].x)
            val ey = obs.intrinsics.fy * (pCam.y / pCam.z - normalizedTargets[i].y)
            residualsPx[i] = sqrt(ex * ex + ey * ey)
            validViews++
        }

        if (validViews < 2) {
            return TriangulationResult.InsufficientGeometry(
                reason = "Triangulated point lies outside valid camera depth range",
                maxParallaxDeg = 0.0,
                maxBaselineMeters = maxBaseline,
            )
        }

        val inlierIndices = observations.indices.filter { residualsPx[it] <= config.outlierThresholdPx }
        if (inlierIndices.size < 2) {
            return TriangulationResult.InsufficientGeometry(
                reason = "High reprojection error across views; re-place point on a crisp feature",
                maxParallaxDeg = computeMaxParallaxDeg(refined, observations),
                maxBaselineMeters = maxBaseline,
                initialEstimate = refined,
            )
        }

        val inlierObs = inlierIndices.map { observations[it] }
        val maxParallaxDeg = computeMaxParallaxDeg(refined, inlierObs)
        if (maxParallaxDeg < config.minParallaxDeg) {
            return TriangulationResult.InsufficientGeometry(
                reason = "Parallax angle too small (${"%.1f".format(maxParallaxDeg)} deg); keep sliding sideways",
                maxParallaxDeg = maxParallaxDeg,
                maxBaselineMeters = maxBaseline,
                initialEstimate = refined,
            )
        }

        var sumSqPx = 0.0
        var chiSquared = 0.0
        var infoMatrix = Mat3.ZERO

        for (i in inlierIndices) {
            val obs = observations[i]
            val pCam = obs.pose.worldToCamera(refined)
            val ex = obs.intrinsics.fx * (pCam.x / pCam.z - normalizedTargets[i].x)
            val ey = obs.intrinsics.fy * (pCam.y / pCam.z - normalizedTargets[i].y)
            val errPx2 = ex * ex + ey * ey
            sumSqPx += errPx2

            val invSigma2 = 1.0 / (obs.sigmaPx * obs.sigmaPx)
            val huberW = huberWeight(sqrt(errPx2), config.huberDeltaPx)
            chiSquared += errPx2 * invSigma2

            val (jRowX, jRowY) = projectionJacobianRows(obs, pCam)
            val w = huberW * invSigma2
            infoMatrix = infoMatrix +
                Mat3.outer(jRowX, jRowX).scaled(w) +
                Mat3.outer(jRowY, jRowY).scaled(w)
        }

        if (depthPrior != null) {
            val wDepth = 1.0 / (depthPrior.sigmaMeters * depthPrior.sigmaMeters)
            val zAxisWorld = depthPrior.pose.cameraDirectionToWorld(Vec3.UNIT_Z)
            infoMatrix = infoMatrix + Mat3.outer(zAxisWorld, zAxisWorld).scaled(wDepth)
        }

        val rmsPx = sqrt(sumSqPx / inlierIndices.size)
        if (rmsPx > config.maxReprojectionRmsPx) {
            return TriangulationResult.InsufficientGeometry(
                reason = "Reprojection RMS too high (${"%.2f".format(rmsPx)} px)",
                maxParallaxDeg = maxParallaxDeg,
                maxBaselineMeters = maxBaseline,
                initialEstimate = refined,
            )
        }

        val rawCov = try {
            infoMatrix.inverse()
        } catch (_: IllegalArgumentException) {
            return TriangulationResult.InsufficientGeometry(
                reason = "Degenerate view geometry (singular information matrix)",
                maxParallaxDeg = maxParallaxDeg,
                maxBaselineMeters = maxBaseline,
                initialEstimate = refined,
            )
        }

        // Conservative empirical variance inflation when overdetermined (dof > 0).
        val dof = 2 * inlierIndices.size - 3
        val varianceScale = if (dof > 0) max(1.0, chiSquared / dof) else 1.0
        val cov = rawCov.scaled(varianceScale)

        return TriangulationResult.Success(
            Point3DResult(
                position = refined,
                covarianceMeters2 = cov,
                reprojectionRmsPx = rmsPx,
                maxParallaxDeg = maxParallaxDeg,
                maxBaselineMeters = computeMaxBaseline(inlierObs),
                inlierCount = inlierIndices.size,
                totalObservations = observations.size,
            ),
        )
    }

    /**
     * Closed-form weighted ray midpoint triangulation:
     * minimizes sum_i w_i * ||(I - d_i d_i^T)(X - c_i)||^2.
     */
    internal fun triangulateLinearMidpoint(observations: List<RayObservation>): Vec3? {
        var a = Mat3.ZERO
        var b = Vec3.ZERO

        for (obs in observations) {
            val rayCam = obs.intrinsics.unprojectRay(obs.pixel)
            val d = obs.pose.cameraDirectionToWorld(rayCam).normalized()
            val c = obs.pose.position
            val w = 1.0 / (obs.sigmaPx * obs.sigmaPx)

            val proj = (Mat3.IDENTITY - Mat3.outer(d, d)).scaled(w)
            a = a + proj
            b = b + (proj * c)
        }

        return try {
            a.solve(b)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun refineLevenbergMarquardt(
        initialPoint: Vec3,
        observations: List<RayObservation>,
        normalizedTargets: List<Vec2>,
        depthPrior: DepthPrior?,
        config: TriangulationConfig,
    ): Vec3 {
        var x = initialPoint
        var lambda = 1e-3
        var currentCost = computeRobustCost(x, observations, normalizedTargets, depthPrior, config)

        for (iter in 0 until config.maxIterations) {
            var h = Mat3.ZERO
            var g = Vec3.ZERO

            for (i in observations.indices) {
                val obs = observations[i]
                val pCam = obs.pose.worldToCamera(x)
                if (pCam.z <= 1e-3) continue

                val ex = obs.intrinsics.fx * (pCam.x / pCam.z - normalizedTargets[i].x)
                val ey = obs.intrinsics.fy * (pCam.y / pCam.z - normalizedTargets[i].y)
                val errNorm = sqrt(ex * ex + ey * ey)

                val w = huberWeight(errNorm, config.huberDeltaPx) / (obs.sigmaPx * obs.sigmaPx)
                val (jRowX, jRowY) = projectionJacobianRows(obs, pCam)

                h = h + Mat3.outer(jRowX, jRowX).scaled(w) + Mat3.outer(jRowY, jRowY).scaled(w)
                g = g + jRowX * (w * ex) + jRowY * (w * ey)
            }

            if (depthPrior != null) {
                val pCam = depthPrior.pose.worldToCamera(x)
                val errZ = pCam.z - depthPrior.depthMeters
                val wZ = 1.0 / (depthPrior.sigmaMeters * depthPrior.sigmaMeters)
                val jZ = depthPrior.pose.cameraDirectionToWorld(Vec3.UNIT_Z)
                h = h + Mat3.outer(jZ, jZ).scaled(wZ)
                g = g + jZ * (wZ * errZ)
            }

            var accepted = false
            for (attempt in 0 until 8) {
                val damped = h + Mat3.diagonal(
                    lambda * max(1.0, h.m00),
                    lambda * max(1.0, h.m11),
                    lambda * max(1.0, h.m22),
                )
                val step = try {
                    damped.solve(-g)
                } catch (_: IllegalArgumentException) {
                    lambda *= 10.0
                    continue
                }

                val candidate = x + step
                val candidateCost = computeRobustCost(
                    candidate,
                    observations,
                    normalizedTargets,
                    depthPrior,
                    config,
                )

                if (candidateCost < currentCost) {
                    x = candidate
                    currentCost = candidateCost
                    lambda = max(1e-7, lambda * 0.3)
                    accepted = true
                    if (step.norm() < config.convergenceStepMeters) {
                        return x
                    }
                    break
                } else {
                    lambda = min(1e6, lambda * 5.0)
                }
            }
            if (!accepted) break
        }
        return x
    }

    private fun computeRobustCost(
        x: Vec3,
        observations: List<RayObservation>,
        normalizedTargets: List<Vec2>,
        depthPrior: DepthPrior?,
        config: TriangulationConfig,
    ): Double {
        var cost = 0.0
        for (i in observations.indices) {
            val obs = observations[i]
            val pCam = obs.pose.worldToCamera(x)
            if (pCam.z <= 1e-3) {
                cost += 1e6
                continue
            }
            val ex = obs.intrinsics.fx * (pCam.x / pCam.z - normalizedTargets[i].x)
            val ey = obs.intrinsics.fy * (pCam.y / pCam.z - normalizedTargets[i].y)
            val r = sqrt(ex * ex + ey * ey)
            cost += huberLoss(r, config.huberDeltaPx) / (obs.sigmaPx * obs.sigmaPx)
        }
        if (depthPrior != null) {
            val pCam = depthPrior.pose.worldToCamera(x)
            val dz = (pCam.z - depthPrior.depthMeters) / depthPrior.sigmaMeters
            cost += 0.5 * dz * dz
        }
        return cost
    }

    /**
     * Analytic Jacobian rows d(u_undist)/dX and d(v_undist)/dX for world point X.
     */
    private fun projectionJacobianRows(obs: RayObservation, pCam: Vec3): Pair<Vec3, Vec3> {
        val invZ = 1.0 / pCam.z
        val invZ2 = invZ * invZ
        val duDpc = Vec3(obs.intrinsics.fx * invZ, 0.0, -obs.intrinsics.fx * pCam.x * invZ2)
        val dvDpc = Vec3(0.0, obs.intrinsics.fy * invZ, -obs.intrinsics.fy * pCam.y * invZ2)

        // Since pCam = R^T * (X - t), row_world = (R^T)^T * row_cam = R * row_cam.
        val rowX = obs.pose.rotation * duDpc
        val rowY = obs.pose.rotation * dvDpc
        return rowX to rowY
    }

    private fun huberLoss(r: Double, delta: Double): Double =
        if (r <= delta) 0.5 * r * r else delta * (r - 0.5 * delta)

    private fun huberWeight(r: Double, delta: Double): Double =
        if (r <= delta) 1.0 else delta / r

    private fun computeMaxBaseline(observations: List<RayObservation>): Double {
        var maxDist = 0.0
        for (i in observations.indices) {
            val ci = observations[i].pose.position
            for (j in i + 1 until observations.size) {
                val d = ci.distanceTo(observations[j].pose.position)
                if (d > maxDist) maxDist = d
            }
        }
        return maxDist
    }

    private fun computeMaxParallaxDeg(point: Vec3, observations: List<RayObservation>): Double {
        val rays = observations.map { (point - it.pose.position).normalized() }
        var minDot = 1.0
        for (i in rays.indices) {
            for (j in i + 1 until rays.size) {
                val dot = rays[i].dot(rays[j]).coerceIn(-1.0, 1.0)
                if (dot < minDot) minDot = dot
            }
        }
        return Math.toDegrees(acos(minDot))
    }
}
