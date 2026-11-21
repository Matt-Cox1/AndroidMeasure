package dev.precisionmeasure.app.ar

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.opengl.GLES20
import com.google.ar.core.Pose
import dev.precisionmeasure.geometry.CameraIntrinsics
import dev.precisionmeasure.geometry.CameraPose
import dev.precisionmeasure.geometry.GrayscaleImage
import dev.precisionmeasure.geometry.SubPixelTracker
import dev.precisionmeasure.geometry.Vec2
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LoupeOutput(
    val bitmap: Bitmap,
    val snapOffsetNorm: Vec2?,
)

/**
 * Pure conversion and pixel-extraction helpers bridging ARCore camera images, OpenGL framebuffers,
 * and :core-geometry types.
 */
object CameraImageExtractor {

    fun arPoseToCameraPose(pose: Pose): CameraPose {
        val q = pose.rotationQuaternion
        val t = pose.translation
        return CameraPose.fromArCorePose(
            qx = q[0].toDouble(),
            qy = q[1].toDouble(),
            qz = q[2].toDouble(),
            qw = q[3].toDouble(),
            tx = t[0].toDouble(),
            ty = t[1].toDouble(),
            tz = t[2].toDouble(),
        )
    }

    fun arIntrinsicsToCameraIntrinsics(ci: com.google.ar.core.CameraIntrinsics): CameraIntrinsics {
        val focal = ci.focalLength
        val principal = ci.principalPoint
        val dims = ci.imageDimensions
        return CameraIntrinsics(
            fx = focal[0].toDouble(),
            fy = focal[1].toDouble(),
            cx = principal[0].toDouble(),
            cy = principal[1].toDouble(),
            width = dims[0],
            height = dims[1],
        )
    }

    fun extractYPlane(image: Image): GrayscaleImage {
        val yPlane = image.planes[0]
        val packed = packYPlane(yPlane, image.width, image.height)
        return GrayscaleImage(image.width, image.height, packed)
    }

    fun packPlaneBytes(plane: Image.Plane, rowWidthBytes: Int, height: Int): ByteArray {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val out = ByteArray(rowWidthBytes * height)
        if (rowStride == rowWidthBytes) {
            buffer.position(0)
            buffer.get(out)
            return out
        }
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(out, y * rowWidthBytes, rowWidthBytes)
        }
        return out
    }

    fun extractLoupe(
        image: Image,
        center: Vec2,
        patchSize: Int,
        cornerSnapEnabled: Boolean,
    ): LoupeOutput {
        val size = patchSize
        val half = size / 2
        val yPlane = image.planes[0]
        val buf = yPlane.buffer.duplicate()
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val cx = center.x.toInt()
        val cy = center.y.toInt()

        val rawBytes = ByteArray(size * size)
        var minLum = 255
        var maxLum = 0

        for (dy in -half until half) {
            for (dx in -half until half) {
                val sx = (cx + dx).coerceIn(0, image.width - 1)
                val sy = (cy + dy).coerceIn(0, image.height - 1)
                val lum = buf.get(sy * rowStride + sx * pixelStride).toInt() and 0xFF
                val outX = (half - 1 - dy).coerceIn(0, size - 1)
                val outY = (dx + half).coerceIn(0, size - 1)
                rawBytes[outY * size + outX] = lum.toByte()
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }

        val span = maxOf(48, maxLum - minLum)
        val pixels = IntArray(size * size)
        for (i in rawBytes.indices) {
            val lum = rawBytes[i].toInt() and 0xFF
            val stretched = (((lum - minLum) * 255) / span).coerceIn(0, 255)
            pixels[i] = Color.rgb(stretched, stretched, stretched)
        }

        val snapNorm = if (cornerSnapEnabled) {
            val patch = GrayscaleImage(size, size, rawBytes)
            val snap = SubPixelTracker.snapToBestCorner(
                image = patch,
                center = Vec2(half.toDouble(), half.toDouble()),
                searchRadiusPx = (size / 6).coerceIn(6, 12),
            )
            if (snap.snapped) {
                Vec2((snap.point.x - half) / size.toDouble(), (snap.point.y - half) / size.toDouble())
            } else {
                null
            }
        } else {
            null
        }

        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return LoupeOutput(bitmap, snapNorm)
    }

    fun captureGlFramebuffer(width: Int, height: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val rgba = ByteArray(width * height * 4)
        buf.get(rgba)

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width * 4
            val dstRow = y * width
            for (x in 0 until width) {
                val i = srcRow + x * 4
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                pixels[dstRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun packYPlane(plane: Image.Plane, width: Int, height: Int): ByteArray {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = ByteArray(width * height)

        if (pixelStride == 1 && rowStride == width) {
            buffer.position(0)
            buffer.get(out)
            return out
        }

        val rowBytes = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            val remaining = minOf(rowStride, buffer.remaining())
            buffer.get(rowBytes, 0, remaining)
            val outOffset = y * width
            if (pixelStride == 1) {
                System.arraycopy(rowBytes, 0, out, outOffset, width)
            } else {
                for (x in 0 until width) {
                    out[outOffset + x] = rowBytes[x * pixelStride]
                }
            }
        }
        return out
    }
}
