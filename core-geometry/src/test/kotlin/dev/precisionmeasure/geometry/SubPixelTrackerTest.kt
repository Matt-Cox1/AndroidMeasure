package dev.precisionmeasure.geometry

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubPixelTrackerTest {

    private fun renderSyntheticScene(
        width: Int,
        height: Int,
        shiftX: Double,
        shiftY: Double,
        brightnessOffset: Double = 0.0,
    ): GrayscaleImage {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = x - shiftX
                val v = y - shiftY
                // Smoothly anti-aliased multi-frequency texture plus a sharp corner at (120, 90).
                val texture = 110.0 +
                    35.0 * sin(u * 0.23) * cos(v * 0.19) +
                    25.0 * cos((u + v) * 0.14)
                val corner = 70.0 / ((1.0 + exp(-(u - 120.0) * 1.4)) * (1.0 + exp(-(v - 90.0) * 1.4)))
                val intensity = (texture + corner + brightnessOffset).coerceIn(0.0, 255.0)
                pixels[y * width + x] = intensity.toInt().toByte()
            }
        }
        return GrayscaleImage(width, height, pixels)
    }

    @Test
    fun `tracks sub-pixel shift under illumination change within 0_15 pixels`() {
        val ref = renderSyntheticScene(256, 256, shiftX = 0.0, shiftY = 0.0, brightnessOffset = 0.0)
        val dx = 6.35
        val dy = -4.72
        val tgt = renderSyntheticScene(256, 256, shiftX = dx, shiftY = dy, brightnessOffset = 14.0)

        val refPt = Vec2(120.0, 90.0)
        val expectedTgt = Vec2(refPt.x + dx, refPt.y + dy)
        // Start from a rough initial guess off by ~3 pixels.
        val guess = Vec2(expectedTgt.x - 2.5, expectedTgt.y + 1.8)

        val result = SubPixelTracker.trackPoint(ref, refPt, tgt, guess)
        assertTrue(result is TrackResult.Tracked, "Expected Tracked, got $result")
        val tracked = result as TrackResult.Tracked
        val errPx = tracked.point.distanceTo(expectedTgt)
        assertTrue(errPx < 0.15, "Expected sub-pixel error < 0.15 px, got $errPx px")
        assertTrue(tracked.zncc > 0.95)
        assertTrue(tracked.forwardBackwardErrorPx < 0.1)
    }

    @Test
    fun `rejects flat textureless wall instead of returning silent garbage`() {
        val flatPixels = ByteArray(160 * 160) { 128.toByte() }
        val imgA = GrayscaleImage(160, 160, flatPixels)
        val imgB = GrayscaleImage(160, 160, flatPixels.copyOf())

        val result = SubPixelTracker.trackPoint(imgA, Vec2(80.0, 80.0), imgB, Vec2(80.0, 80.0))
        assertTrue(result is TrackResult.Lost)
    }

    @Test
    fun `snaps rough click to sharp synthetic corner`() {
        val pixels = ByteArray(128 * 128)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val isBright = x >= 64 && y >= 64
                pixels[y * 128 + x] = (if (isBright) 220 else 30).toByte()
            }
        }
        val image = GrayscaleImage(128, 128, pixels)
        val roughClick = Vec2(61.0, 67.0)
        val snapped = SubPixelTracker.snapToBestCorner(image, roughClick, searchRadiusPx = 8)
        assertTrue(snapped.snapped)
        assertTrue(snapped.point.distanceTo(Vec2(63.5, 63.5)) < 1.5, "Snapped to ${snapped.point}")
    }
}
