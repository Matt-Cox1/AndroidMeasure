package dev.precisionmeasure.app.ar

import android.opengl.GLES20
import dev.precisionmeasure.geometry.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Synchronous OpenGL ES 2.0 GL_POINTS renderer for live 3D ARCore SLAM feature clouds
 * and unprojected 3D Raw Depth point clouds.
 */
class PointCloudRenderer {

    private var program = 0
    private var mvpUniform = -1
    private var pointSizeUniform = -1
    private var posAttrib = -1
    private var colorAttrib = -1

    private val data = FloatArray(MAX_POINTS * FLOATS_PER_POINT)
    private var pointCount = 0

    private val buffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_POINTS * FLOATS_PER_POINT * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun createOnGlThread() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "PointCloudRenderer link failed: ${GLES20.glGetProgramInfoLog(program)}" }

        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
        posAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")
    }

    fun beginFrame() {
        pointCount = 0
    }

    fun addPoint(p: Vec3, r: Float, g: Float, b: Float, a: Float) {
        if (pointCount >= MAX_POINTS) return
        val base = pointCount * FLOATS_PER_POINT
        data[base + 0] = p.x.toFloat()
        data[base + 1] = p.y.toFloat()
        data[base + 2] = p.z.toFloat()
        data[base + 3] = r
        data[base + 4] = g
        data[base + 5] = b
        data[base + 6] = a
        pointCount++
    }

    fun flush(viewProjColMajor: FloatArray, pointSizePx: Float = 7.5f) {
        if (pointCount == 0) return

        buffer.position(0)
        buffer.put(data, 0, pointCount * FLOATS_PER_POINT)
        buffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjColMajor, 0)
        GLES20.glUniform1f(pointSizeUniform, pointSizePx)

        val strideBytes = FLOATS_PER_POINT * 4
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)

        buffer.position(3)
        GLES20.glEnableVertexAttribArray(colorAttrib)
        GLES20.glVertexAttribPointer(colorAttrib, 4, GLES20.GL_FLOAT, false, strideBytes, buffer)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)

        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "PointCloudRenderer shader error: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val FLOATS_PER_POINT = 7
        private const val MAX_POINTS = 12000

        private const val VERTEX_SHADER = """
            uniform mat4 u_MvpMatrix;
            uniform float u_PointSize;
            attribute vec3 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
                gl_PointSize = u_PointSize;
                v_Color = a_Color;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                if (dot(c, c) > 0.25) discard;
                gl_FragColor = v_Color;
            }
        """
    }
}
