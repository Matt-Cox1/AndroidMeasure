package dev.precisionmeasure.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Extracts robust metric depth priors from ARCore 16-bit Raw Depth + 8-bit confidence buffers.
 * Rejects low-confidence pixels and depth-edge outliers using median + MAD filtering.
 */
object DepthFuser {

    data class Config(
        val patchRadiusPx: Int = 4,
        val minConfidence: Int = 90,
        val minValidSamples: Int = 3,
        val minDepthMm: Int = 100,
        val maxDepthMm: Int = 5000,
        val madOutlierFactor: Double = 2.5,
        val baseRelativeSigma: Double = 0.03,
    )

    /**
     * Reads ARCore Raw Depth (`uint16` little-endian millimeters) and confidence (`uint8` 0..255)
     * in a patch around ([centerX], [centerY]) and returns a [DepthPrior], or null when depth
     * confidence in that region is too sparse or noisy.
     */
    fun extractDepthPrior(
        pose: CameraPose,
        depthBufferLe: ByteArray,
        confidenceBuffer: ByteArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        config: Config = Config(),
    ): DepthPrior? {
        require(width > 0 && height > 0) { "Invalid depth dimensions: ${width}x$height" }
        require(depthBufferLe.size == width * height * 2) {
            "Expected ${width * height * 2} depth bytes, got ${depthBufferLe.size}"
        }
        require(confidenceBuffer.size == width * height) {
            "Expected ${width * height} confidence bytes, got ${confidenceBuffer.size}"
        }

        val minX = max(0, centerX - config.patchRadiusPx)
        val maxX = min(width - 1, centerX + config.patchRadiusPx)
        val minY = max(0, centerY - config.patchRadiusPx)
        val maxY = min(height - 1, centerY + config.patchRadiusPx)
        if (minX > maxX || minY > maxY) return null

        val samplesMm = ArrayList<Double>((2 * config.patchRadiusPx + 1) * (2 * config.patchRadiusPx + 1))
        for (y in minY..maxY) {
            val row = y * width
            for (x in minX..maxX) {
                val idx = row + x
                val conf = confidenceBuffer[idx].toInt() and 0xFF
                if (conf < config.minConfidence) continue

                val lo = depthBufferLe[idx * 2].toInt() and 0xFF
                val hi = depthBufferLe[idx * 2 + 1].toInt() and 0xFF
                val depthMm = lo or (hi shl 8)
                if (depthMm in config.minDepthMm..config.maxDepthMm) {
                    samplesMm.add(depthMm.toDouble())
                }
            }
        }

        if (samplesMm.size < config.minValidSamples) return null

        samplesMm.sort()
        val medianMm = medianSorted(samplesMm)

        val absDevs = samplesMm.map { abs(it - medianMm) }.sorted()
        val madMm = medianSorted(absDevs)
        val robustSigmaMm = 1.4826 * madMm

        // Reject foreground/background boundary mixtures (bimodal patches).
        val thresholdMm = max(15.0, config.madOutlierFactor * robustSigmaMm)
        val inliers = samplesMm.filter { abs(it - medianMm) <= thresholdMm }
        if (inliers.size < config.minValidSamples) return null

        val meanInlierMm = inliers.average()
        val depthMeters = meanInlierMm * 1e-3

        // Combine local patch spread with ARCore's systematic depth prior uncertainty.
        val spreadMeters = robustSigmaMm * 1e-3
        val sigmaMeters = max(0.015, max(spreadMeters * 2.0, depthMeters * config.baseRelativeSigma))
        return DepthPrior(
            pose = pose,
            depthMeters = depthMeters,
            sigmaMeters = sigmaMeters,
        )
    }

    private fun medianSorted(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
        }
    }
}
