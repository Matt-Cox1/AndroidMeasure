package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 8-bit grayscale image matching the luminance (Y) plane of Android YUV_420_888 camera frames.
 */
class GrayscaleImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Invalid image size: ${width}x$height" }
        require(pixels.size == width * height) {
            "Expected ${width * height} pixels, got ${pixels.size}"
        }
    }

    fun getUnsafe(x: Int, y: Int): Double = (pixels[y * width + x].toInt() and 0xFF).toDouble()

    fun sampleBilinear(x: Double, y: Double): Double {
        require(x >= 0.0 && x <= width - 1.0 && y >= 0.0 && y <= height - 1.0) {
            "Sample ($x, $y) outside image bounds ${width}x$height"
        }
        val x0 = floor(x).toInt().coerceIn(0, width - 2)
        val y0 = floor(y).toInt().coerceIn(0, height - 2)
        val fx = x - x0
        val fy = y - y0

        val row0 = y0 * width
        val row1 = row0 + width
        val v00 = (pixels[row0 + x0].toInt() and 0xFF).toDouble()
        val v10 = (pixels[row0 + x0 + 1].toInt() and 0xFF).toDouble()
        val v01 = (pixels[row1 + x0].toInt() and 0xFF).toDouble()
        val v11 = (pixels[row1 + x0 + 1].toInt() and 0xFF).toDouble()

        val top = v00 + fx * (v10 - v00)
        val bottom = v01 + fx * (v11 - v01)
        return top + fy * (bottom - top)
    }

    /** Box-filtered 2x downsample for multi-scale optical flow pyramids. */
    fun downsample2x(): GrayscaleImage {
        val nw = width / 2
        val nh = height / 2
        require(nw >= 16 && nh >= 16) { "Image too small to downsample: ${width}x$height" }
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) {
            val sy = y * 2
            val r0 = sy * width
            val r1 = (sy + 1) * width
            val outRow = y * nw
            for (x in 0 until nw) {
                val sx = x * 2
                val sum = (pixels[r0 + sx].toInt() and 0xFF) +
                    (pixels[r0 + sx + 1].toInt() and 0xFF) +
                    (pixels[r1 + sx].toInt() and 0xFF) +
                    (pixels[r1 + sx + 1].toInt() and 0xFF)
                out[outRow + x] = (sum ushr 2).toByte()
            }
        }
        return GrayscaleImage(nw, nh, out)
    }

    fun buildPyramid(levels: Int): List<GrayscaleImage> {
        require(levels >= 1) { "Pyramid levels must be >= 1" }
        val result = ArrayList<GrayscaleImage>(levels)
        var current = this
        result.add(current)
        for (i in 1 until levels) {
            if (current.width / 2 < 32 || current.height / 2 < 32) break
            current = current.downsample2x()
            result.add(current)
        }
        return result
    }
}

data class TrackerConfig(
    val patchRadiusPx: Int = 7,
    val pyramidLevels: Int = 3,
    val maxIterationsPerLevel: Int = 20,
    val convergenceEpsPx: Double = 1e-3,
    val minGradientEnergy: Double = 4.0,
    val minZncc: Double = 0.80,
    val maxForwardBackwardErrorPx: Double = 0.5,
)

sealed class TrackResult {
    data class Tracked(
        val point: Vec2,
        val zncc: Double,
        val forwardBackwardErrorPx: Double,
        val estimatedSigmaPx: Double,
    ) : TrackResult()

    data class Lost(val reason: String) : TrackResult()
}

data class CornerSnapResult(
    val point: Vec2,
    val snapped: Boolean,
    val cornerStrength: Double,
)

/**
 * Pyramidal inverse-compositional Lucas-Kanade tracker with ZNCC and forward-backward validation.
 */
object SubPixelTracker {

    /**
     * Finds the strongest Shi-Tomasi corner within [searchRadiusPx] of [center] and refines it
     * to sub-pixel precision via 3x3 quadratic fitting. Returns the original point if texture is weak.
     */
    fun snapToBestCorner(
        image: GrayscaleImage,
        center: Vec2,
        searchRadiusPx: Int = 8,
        windowRadiusPx: Int = 2,
        minStrength: Double = 120.0,
    ): CornerSnapResult {
        val margin = windowRadiusPx + 2
        val cx = center.x.toInt()
        val cy = center.y.toInt()
        val minX = max(margin, cx - searchRadiusPx)
        val maxX = min(image.width - 1 - margin, cx + searchRadiusPx)
        val minY = max(margin, cy - searchRadiusPx)
        val maxY = min(image.height - 1 - margin, cy + searchRadiusPx)
        if (minX > maxX || minY > maxY) {
            return CornerSnapResult(center, snapped = false, cornerStrength = 0.0)
        }

        val gridW = maxX - minX + 1
        val gridH = maxY - minY + 1
        val scores = DoubleArray(gridW * gridH)
        var bestScore = -1.0
        var bestGx = -1
        var bestGy = -1

        for (gy in 0 until gridH) {
            val y = minY + gy
            for (gx in 0 until gridW) {
                val x = minX + gx
                val score = shiTomasiScore(image, x, y, windowRadiusPx)
                scores[gy * gridW + gx] = score
                if (score > bestScore) {
                    bestScore = score
                    bestGx = gx
                    bestGy = gy
                }
            }
        }

        if (bestScore < minStrength || bestGx < 0) {
            return CornerSnapResult(center, snapped = false, cornerStrength = max(0.0, bestScore))
        }

        var refinedX = (minX + bestGx).toDouble()
        var refinedY = (minY + bestGy).toDouble()

        if (bestGx in 1 until gridW - 1 && bestGy in 1 until gridH - 1) {
            val sC = scores[bestGy * gridW + bestGx]
            val sL = scores[bestGy * gridW + bestGx - 1]
            val sR = scores[bestGy * gridW + bestGx + 1]
            val sU = scores[(bestGy - 1) * gridW + bestGx]
            val sD = scores[(bestGy + 1) * gridW + bestGx]

            val denomX = sL - 2.0 * sC + sR
            val denomY = sU - 2.0 * sC + sD
            if (denomX < -1e-6) {
                refinedX += (0.5 * (sL - sR) / denomX).coerceIn(-0.5, 0.5)
            }
            if (denomY < -1e-6) {
                refinedY += (0.5 * (sU - sD) / denomY).coerceIn(-0.5, 0.5)
            }
        }

        return CornerSnapResult(Vec2(refinedX, refinedY), snapped = true, cornerStrength = bestScore)
    }

    private fun shiTomasiScore(image: GrayscaleImage, cx: Int, cy: Int, r: Int): Double {
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        val twoSigma2 = 2.0 * (r * 0.65) * (r * 0.65)
        for (dy in -r..r) {
            val y = cy + dy
            for (dx in -r..r) {
                val x = cx + dx
                val w = kotlin.math.exp(-(dx * dx + dy * dy) / twoSigma2)
                val gx = 0.5 * (image.getUnsafe(x + 1, y) - image.getUnsafe(x - 1, y))
                val gy = 0.5 * (image.getUnsafe(x, y + 1) - image.getUnsafe(x, y - 1))
                sxx += w * gx * gx
                syy += w * gy * gy
                sxy += w * gx * gy
            }
        }
        val trace = sxx + syy
        val disc = sqrt((sxx - syy) * (sxx - syy) + 4.0 * sxy * sxy)
        return 0.5 * (trace - disc)
    }

    fun trackPoint(
        reference: GrayscaleImage,
        referencePoint: Vec2,
        target: GrayscaleImage,
        initialGuess: Vec2 = referencePoint,
        config: TrackerConfig = TrackerConfig(),
    ): TrackResult {
        val refPyr = reference.buildPyramid(config.pyramidLevels)
        val tgtPyr = target.buildPyramid(config.pyramidLevels)
        val levels = min(refPyr.size, tgtPyr.size)

        val forward = trackPyramid(refPyr, tgtPyr, levels, referencePoint, initialGuess, config)
            ?: return TrackResult.Lost("Forward LK tracking failed (low texture or out of bounds)")

        val zncc = computeZncc(reference, referencePoint, target, forward, config.patchRadiusPx)
        if (!zncc.isFinite() || zncc < config.minZncc) {
            return TrackResult.Lost("Low patch correlation (ZNCC=${"%.3f".format(zncc)})")
        }

        // Forward-backward check catches drift, occlusion, and specular jumps.
        val backward = trackPyramid(tgtPyr, refPyr, levels, forward, referencePoint, config)
            ?: return TrackResult.Lost("Backward LK verification failed")

        val fbError = backward.distanceTo(referencePoint)
        if (fbError > config.maxForwardBackwardErrorPx) {
            return TrackResult.Lost("Forward-backward drift too large (${"%.2f".format(fbError)} px)")
        }

        // Convert track quality into an observation sigma in pixels (floor 0.15 px).
        val sigmaPx = max(0.15, 0.25 + 0.5 * fbError + (1.0 - zncc) * 2.0)
        return TrackResult.Tracked(
            point = forward,
            zncc = zncc,
            forwardBackwardErrorPx = fbError,
            estimatedSigmaPx = sigmaPx,
        )
    }

    private fun trackPyramid(
        refPyr: List<GrayscaleImage>,
        tgtPyr: List<GrayscaleImage>,
        levels: Int,
        refPointFull: Vec2,
        guessFull: Vec2,
        config: TrackerConfig,
    ): Vec2? {
        var scale = 1.0 / (1 shl (levels - 1))
        var est = Vec2(guessFull.x * scale, guessFull.y * scale)

        for (lvl in levels - 1 downTo 0) {
            val refImg = refPyr[lvl]
            val tgtImg = tgtPyr[lvl]
            val refPt = Vec2(refPointFull.x * scale, refPointFull.y * scale)
            val levelMinEnergy = if (lvl == 0) config.minGradientEnergy else config.minGradientEnergy * 0.25
            est = trackSingleLevel(refImg, refPt, tgtImg, est, config, levelMinEnergy) ?: return null
            if (lvl > 0) {
                est = Vec2(est.x * 2.0, est.y * 2.0)
                scale *= 2.0
            }
        }
        return est
    }

    private fun trackSingleLevel(
        refImg: GrayscaleImage,
        refPt: Vec2,
        tgtImg: GrayscaleImage,
        initialTgt: Vec2,
        config: TrackerConfig,
        minEnergy: Double,
    ): Vec2? {
        val r = config.patchRadiusPx
        val margin = r + 2.0
        if (refPt.x < margin || refPt.x > refImg.width - 1.0 - margin ||
            refPt.y < margin || refPt.y > refImg.height - 1.0 - margin
        ) {
            return null
        }

        val patchSize = (2 * r + 1) * (2 * r + 1)
        val tpl = DoubleArray(patchSize)
        val gx = DoubleArray(patchSize)
        val gy = DoubleArray(patchSize)

        var tplMean = 0.0
        var idx = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                val x = refPt.x + dx
                val y = refPt.y + dy
                val v = refImg.sampleBilinear(x, y)
                tpl[idx] = v
                tplMean += v
                gx[idx] = 0.5 * (refImg.sampleBilinear(x + 1.0, y) - refImg.sampleBilinear(x - 1.0, y))
                gy[idx] = 0.5 * (refImg.sampleBilinear(x, y + 1.0) - refImg.sampleBilinear(x, y - 1.0))
                idx++
            }
        }
        tplMean /= patchSize

        // Inverse-compositional Hessian on the reference patch.
        var h00 = 0.0
        var h01 = 0.0
        var h11 = 0.0
        for (i in 0 until patchSize) {
            h00 += gx[i] * gx[i]
            h01 += gx[i] * gy[i]
            h11 += gy[i] * gy[i]
        }

        // Minimum eigenvalue check rejects flat/edge-only patches (aperture problem).
        val trace = h00 + h11
        val disc = sqrt((h00 - h11) * (h00 - h11) + 4.0 * h01 * h01)
        val minEig = 0.5 * (trace - disc) / patchSize
        if (minEig < minEnergy) {
            return null
        }

        val det = h00 * h11 - h01 * h01
        if (abs(det) < 1e-9) return null
        val invH00 = h11 / det
        val invH01 = -h01 / det
        val invH11 = h00 / det

        var tx = initialTgt.x
        var ty = initialTgt.y
        val tgtValues = DoubleArray(patchSize)

        for (iter in 0 until config.maxIterationsPerLevel) {
            if (tx < margin || tx > tgtImg.width - 1.0 - margin ||
                ty < margin || ty > tgtImg.height - 1.0 - margin
            ) {
                return null
            }

            // Subtract per-patch mean to stay invariant to exposure drift across frames.
            var tgtMean = 0.0
            idx = 0
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val v = tgtImg.sampleBilinear(tx + dx, ty + dy)
                    tgtValues[idx++] = v
                    tgtMean += v
                }
            }
            tgtMean /= patchSize

            var b0 = 0.0
            var b1 = 0.0
            for (i in 0 until patchSize) {
                val err = (tgtValues[i] - tgtMean) - (tpl[i] - tplMean)
                b0 += gx[i] * err
                b1 += gy[i] * err
            }

            val deltaX = -(invH00 * b0 + invH01 * b1)
            val deltaY = -(invH01 * b0 + invH11 * b1)
            tx += deltaX
            ty += deltaY

            if (deltaX * deltaX + deltaY * deltaY < config.convergenceEpsPx * config.convergenceEpsPx) {
                break
            }
        }

        if (tx < margin || tx > tgtImg.width - 1.0 - margin ||
            ty < margin || ty > tgtImg.height - 1.0 - margin
        ) {
            return null
        }
        return Vec2(tx, ty)
    }

    fun computeZncc(
        imgA: GrayscaleImage,
        ptA: Vec2,
        imgB: GrayscaleImage,
        ptB: Vec2,
        radius: Int,
    ): Double {
        val margin = radius + 1.0
        if (ptA.x < margin || ptA.x > imgA.width - 1.0 - margin ||
            ptA.y < margin || ptA.y > imgA.height - 1.0 - margin ||
            ptB.x < margin || ptB.x > imgB.width - 1.0 - margin ||
            ptB.y < margin || ptB.y > imgB.height - 1.0 - margin
        ) {
            return -1.0
        }

        val count = (2 * radius + 1) * (2 * radius + 1)
        val aVals = DoubleArray(count)
        val bVals = DoubleArray(count)
        var meanA = 0.0
        var meanB = 0.0
        var idx = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val va = imgA.sampleBilinear(ptA.x + dx, ptA.y + dy)
                val vb = imgB.sampleBilinear(ptB.x + dx, ptB.y + dy)
                aVals[idx] = va
                bVals[idx] = vb
                meanA += va
                meanB += vb
                idx++
            }
        }
        meanA /= count
        meanB /= count

        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in 0 until count) {
            val da = aVals[i] - meanA
            val db = bVals[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }
        val denom = sqrt(denA * denB)
        return if (denom < 1e-6) -1.0 else (num / denom)
    }
}
