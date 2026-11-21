package dev.precisionmeasure.app.ar

import android.media.Image
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Renders ARCore 16-bit depth (Full Depth or confidence-filtered Raw Depth) as a pixel-aligned
 * translucent Turbo colormap heatmap directly over the live OpenGL camera feed.
 */
class DepthOverlayRenderer {

    private var program = 0
    private var textureId = -1
    private var posAttrib = -1
    private var texCoordAttrib = -1
    private var textureUniform = -1
    private var alphaUniform = -1

    private var texWidth = 0
    private var texHeight = 0
    private var rgbaBuffer: ByteBuffer? = null
    private var hasValidTexture = false

    private val quadCoords: FloatBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    private val transformedTexCoords: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) {
            "Failed to link DepthOverlayRenderer: ${GLES20.glGetProgramInfoLog(program)}"
        }

        posAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_DepthTex")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")
    }

    /**
     * Uploads a 16-bit depth image (and optional 8-bit confidence image for Raw Depth mode)
     * converted to an RGBA Turbo heatmap texture.
     */
    fun updateFromDepthImage(
        depthImage: Image,
        confidenceImage: Image? = null,
        minConfidence: Int = 80,
        maxDepthMeters: Float = 2.5f,
    ) {
        val w = depthImage.width
        val h = depthImage.height
        if (w <= 0 || h <= 0) return

        val requiredBytes = w * h * 4
        var buf = rgbaBuffer
        if (buf == null || texWidth != w || texHeight != h) {
            buf = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
            rgbaBuffer = buf
            texWidth = w
            texHeight = h
        }
        buf.position(0)

        val dPlane = depthImage.planes[0]
        val dBuf = dPlane.buffer.duplicate()
        val dStride = dPlane.rowStride

        val cPlane = confidenceImage?.planes?.get(0)
        val cBuf = cPlane?.buffer?.duplicate()
        val cStride = cPlane?.rowStride ?: 0

        val invRange = 1.0f / (maxDepthMeters - 0.15f)
        for (y in 0 until h) {
            val dRow = y * dStride
            val cRow = y * cStride
            for (x in 0 until w) {
                val lo = dBuf.get(dRow + x * 2).toInt() and 0xFF
                val hi = dBuf.get(dRow + x * 2 + 1).toInt() and 0xFF
                val mm = lo or (hi shl 8)
                val conf = if (cBuf != null) (cBuf.get(cRow + x).toInt() and 0xFF) else 255

                if (mm <= 0 || conf < minConfidence) {
                    buf.put(0)
                    buf.put(0)
                    buf.put(0)
                    buf.put(0)
                } else {
                    val meters = mm * 1e-3f
                    // Near = 1.0 (warm yellow/red), Far = 0.0 (deep blue/purple).
                    val t = (1.0f - (meters - 0.15f) * invRange).coerceIn(0f, 1f)
                    val rgb = turboColor(t)
                    buf.put(((rgb ushr 16) and 0xFF).toByte())
                    buf.put(((rgb ushr 8) and 0xFF).toByte())
                    buf.put((rgb and 0xFF).toByte())
                    val alphaByte = if (cBuf != null) ((conf * 220) / 255).coerceIn(80, 235) else 210
                    buf.put(alphaByte.toByte())
                }
            }
        }
        buf.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            w,
            h,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buf,
        )
        hasValidTexture = true
    }

    fun draw(frame: Frame, opacity: Float = 0.52f) {
        if (!hasValidTexture) return

        // Always update UV transform from current frame so orientation matches CameraBackgroundRenderer exactly.
        quadCoords.position(0)
        transformedTexCoords.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadCoords,
            Coordinates2d.TEXTURE_NORMALIZED,
            transformedTexCoords,
        )

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniform1f(alphaUniform, opacity)

        quadCoords.position(0)
        transformedTexCoords.position(0)
        GLES20.glVertexAttribPointer(posAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords)
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "DepthOverlayRenderer shader error: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            +1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, +1.0f,
        )

        /** Compact Turbo-like perceptual colormap (t=0 deep blue, t=0.5 green/cyan, t=1.0 bright yellow/red). */
        fun turboColor(t: Float): Int {
            val x = t.coerceIn(0f, 1f)
            val r = (1.0f - abs(x - 0.78f) * 2.4f).coerceIn(0f, 1f) + if (x > 0.85f) (x - 0.85f) * 2.5f else 0f
            val g = (1.0f - abs(x - 0.50f) * 2.2f).coerceIn(0f, 1f)
            val b = (1.0f - abs(x - 0.18f) * 2.5f).coerceIn(0f, 1f)
            val ri = (r.coerceIn(0f, 1f) * 255f).toInt()
            val gi = (g.coerceIn(0f, 1f) * 255f).toInt()
            val bi = (b.coerceIn(0f, 1f) * 255f).toInt()
            return (ri shl 16) or (gi shl 8) or bi
        }

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D u_DepthTex;
            uniform float u_Alpha;
            varying vec2 v_TexCoord;
            void main() {
                vec4 c = texture2D(u_DepthTex, v_TexCoord);
                gl_FragColor = vec4(c.rgb, c.a * u_Alpha);
            }
        """
    }
}
