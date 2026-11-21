package dev.precisionmeasure.app.ar

import android.opengl.GLES20
import dev.precisionmeasure.geometry.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Synchronous OpenGL ES 2.0 3D overlay renderer drawn inside the exact same onDrawFrame()
 * pass as the ARCore camera background. Renders camera-facing 3D caliper lines, ruler ticks,
 * shaded 3D polygons, 3D wireframe boxes, 3D angle arcs, vertical plumb lines, anchor pins,
 * and surface cursor rings with 0 ms frame lag.
 */
class Overlay3DRenderer {

    private var program = 0
    private var mvpUniform = -1
    private var posAttrib = -1
    private var colorAttrib = -1

    private val vertices = FloatArray(MAX_VERTICES * FLOATS_PER_VERTEX)
    private var vertexCount = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_VERTICES * FLOATS_PER_VERTEX * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun createOnGlThread() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) {
            "Failed to link Overlay3DRenderer program: ${GLES20.glGetProgramInfoLog(program)}"
        }

        mvpUniform = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        posAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")
    }

    fun beginFrame() {
        vertexCount = 0
    }

    fun addRibbon3D(
        p0: Vec3,
        p1: Vec3,
        cameraPos: Vec3,
        halfWidthMeters: Double,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val seg = p1 - p0
        val len = seg.norm()
        if (len < 1e-5) return
        val dir = seg / len
        val mid = (p0 + p1) * 0.5
        val toCam = cameraPos - mid
        val side = if (toCam.norm() > 1e-5) {
            val c = dir.cross(toCam.normalized())
            if (c.norm() > 1e-5) c.normalized() else Vec3.UNIT_Y
        } else {
            Vec3.UNIT_Y
        }

        val offset = side * halfWidthMeters
        val v0 = p0 - offset
        val v1 = p0 + offset
        val v2 = p1 + offset
        val v3 = p1 - offset

        addTriangle(v0, v1, v2, r, g, b, a)
        addTriangle(v0, v2, v3, r, g, b, a)
    }

    fun addDashedRibbon3D(
        p0: Vec3,
        p1: Vec3,
        cameraPos: Vec3,
        halfWidthMeters: Double,
        dashLengthMeters: Double,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val delta = p1 - p0
        val totalLen = delta.norm()
        if (totalLen < 1e-4) return
        val dir = delta / totalLen
        val step = dashLengthMeters * 2.0
        var d = 0.0
        while (d < totalLen) {
            val end = minOf(totalLen, d + dashLengthMeters)
            addRibbon3D(p0 + dir * d, p0 + dir * end, cameraPos, halfWidthMeters, r, g, b, a)
            d += step
        }
    }

    fun addRing3D(
        center: Vec3,
        normal: Vec3,
        innerRadiusMeters: Double,
        outerRadiusMeters: Double,
        segments: Int,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val n = if (normal.norm() > 1e-6) normal.normalized() else Vec3.UNIT_Y
        val ref = if (abs(n.y) < 0.9) Vec3.UNIT_Y else Vec3.UNIT_X
        val u = n.cross(ref).normalized()
        val v = n.cross(u).normalized()

        for (i in 0 until segments) {
            val a0 = (2.0 * PI * i) / segments
            val a1 = (2.0 * PI * (i + 1)) / segments
            val dir0 = u * cos(a0) + v * sin(a0)
            val dir1 = u * cos(a1) + v * sin(a1)

            val out0 = center + dir0 * outerRadiusMeters
            val out1 = center + dir1 * outerRadiusMeters
            if (innerRadiusMeters <= 1e-6) {
                addTriangle(center, out0, out1, r, g, b, a)
            } else {
                val in0 = center + dir0 * innerRadiusMeters
                val in1 = center + dir1 * innerRadiusMeters
                addTriangle(in0, out0, out1, r, g, b, a)
                addTriangle(in0, out1, in1, r, g, b, a)
            }
        }
    }

    fun addAnchorPin3D(
        point: Vec3,
        cameraPos: Vec3,
        r: Float,
        g: Float,
        b: Float,
        scale: Double = 1.0,
        haloRadius: Double = 0.0,
    ) {
        val camDist = cameraPos.distanceTo(point).coerceIn(0.15, 5.0)
        val pinRadius = camDist * 0.0075 * scale
        val toCam = (cameraPos - point)
        val norm = if (toCam.norm() > 1e-5) toCam.normalized() else Vec3.UNIT_Y
        addRing3D(point, norm, 0.0, pinRadius * 1.32, 18, 0f, 0f, 0f, 0.85f)
        addRing3D(point, norm, 0.0, pinRadius, 18, r, g, b, 1.0f)
        if (haloRadius > pinRadius * 1.4) {
            addRing3D(point, norm, haloRadius * 0.88, haloRadius, 24, r, g, b, 0.50f)
        }
    }

    /** Translucent shaded 3D polygon interior using centroid triangle fan. */
    fun addFilledPolygon3D(
        polyVertices: List<Vec3>,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        if (polyVertices.size < 3) return
        var cx = 0.0
        var cy = 0.0
        var cz = 0.0
        for (p in polyVertices) {
            cx += p.x
            cy += p.y
            cz += p.z
        }
        val centroid = Vec3(cx / polyVertices.size, cy / polyVertices.size, cz / polyVertices.size)
        for (i in polyVertices.indices) {
            val v0 = polyVertices[i]
            val v1 = polyVertices[(i + 1) % polyVertices.size]
            addTriangle(centroid, v0, v1, r, g, b, a)
        }
    }

    /** 3D caliper segment with optional ruler ticks, H/V right-angle legs, and anchor pins. */
    fun addCaliperSegment3D(
        worldA: Vec3,
        worldB: Vec3,
        cameraPos: Vec3,
        scaleFactor: Double,
        r: Float,
        g: Float,
        b: Float,
        alpha: Float,
        isPreview: Boolean,
        isActive: Boolean,
        thicknessScale: Double = 1.0,
        showTicks: Boolean = true,
        showHvLegs: Boolean = true,
        drawPins: Boolean = true,
        haloRadiusA: Double = 0.0,
        haloRadiusB: Double = 0.0,
    ) {
        val delta = worldB - worldA
        val rawLen = delta.norm()
        if (rawLen < 1e-4) return

        val mid = (worldA + worldB) * 0.5
        val camDist = cameraPos.distanceTo(mid).coerceIn(0.15, 5.0)
        val baseHalfW = camDist * (if (isActive) 0.0021 else 0.0015) * thicknessScale

        if (isPreview) {
            val dashLen = (camDist * 0.015).coerceIn(0.004, 0.025)
            addDashedRibbon3D(worldA, worldB, cameraPos, baseHalfW * 1.4, dashLen, 0f, 0f, 0f, 0.55f)
            addDashedRibbon3D(worldA, worldB, cameraPos, baseHalfW, dashLen, r, g, b, alpha)
        } else {
            addRibbon3D(worldA, worldB, cameraPos, baseHalfW * 1.7, 0f, 0f, 0f, 0.62f * alpha)
            addRibbon3D(worldA, worldB, cameraPos, baseHalfW, r, g, b, alpha)
        }

        if (showTicks) {
            val dir = delta / rawLen
            val toCam = cameraPos - mid
            val tickNormal = if (toCam.norm() > 1e-5) {
                val cross = dir.cross(toCam.normalized())
                if (cross.norm() > 1e-5) cross.normalized() else Vec3.UNIT_Y
            } else {
                Vec3.UNIT_Y
            }

            val calLen = rawLen * scaleFactor
            val tickStepCal = when {
                calLen <= 0.45 -> 0.01
                calLen <= 1.50 -> 0.05
                else -> 0.10
            }
            val tickStepRaw = tickStepCal / scaleFactor
            val minorHalfH = (camDist * 0.0058).coerceIn(0.002, 0.012)
            val majorHalfH = minorHalfH * 1.85
            val endCapHalfH = minorHalfH * 2.35

            fun drawTick(center: Vec3, halfH: Double, major: Boolean) {
                val t0 = center - tickNormal * halfH
                val t1 = center + tickNormal * halfH
                val tw = if (major) baseHalfW * 0.9 else baseHalfW * 0.6
                addRibbon3D(t0, t1, cameraPos, tw * 1.5, 0f, 0f, 0f, 0.50f * alpha)
                if (major) {
                    addRibbon3D(t0, t1, cameraPos, tw, 1f, 1f, 1f, alpha)
                } else {
                    addRibbon3D(t0, t1, cameraPos, tw, r, g, b, alpha * 0.9f)
                }
            }

            drawTick(worldA, endCapHalfH, major = true)
            drawTick(worldB, endCapHalfH, major = true)

            val steps = (rawLen / tickStepRaw).toInt().coerceAtMost(45)
            for (i in 1 until steps) {
                val pos = worldA + dir * (i * tickStepRaw)
                drawTick(pos, if (i % 5 == 0) majorHalfH else minorHalfH, major = (i % 5 == 0))
            }
        }

        if (showHvLegs && isActive && !isPreview) {
            val vertDiff = abs(worldB.y - worldA.y) * scaleFactor
            val horizDiff = hypot(delta.x, delta.z) * scaleFactor
            if (vertDiff > 0.012 && horizDiff > 0.012) {
                val corner = Vec3(worldB.x, worldA.y, worldB.z)
                val dashLen = (camDist * 0.012).coerceIn(0.004, 0.02)
                addDashedRibbon3D(worldA, corner, cameraPos, baseHalfW * 0.7, dashLen, 0.50f, 0.87f, 0.92f, 0.82f)
                addDashedRibbon3D(corner, worldB, cameraPos, baseHalfW * 0.7, dashLen, 1.0f, 0.88f, 0.51f, 0.82f)
            }
        }

        if (drawPins) {
            val pinScale = if (isActive) 1.1 else 0.75
            addAnchorPin3D(worldA, cameraPos, 0.0f, 0.90f, 0.46f, pinScale, haloRadiusA)
            if (!isPreview) {
                addAnchorPin3D(worldB, cameraPos, 1.0f, 0.84f, 0.0f, pinScale, haloRadiusB)
            }
        }
    }

    /** Draws a 3D protractor arc sector between rays vertex->pA and vertex->pC. */
    fun addAngleArc3D(
        vertex: Vec3,
        pA: Vec3,
        pC: Vec3,
        cameraPos: Vec3,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        thicknessScale: Double = 1.0,
    ) {
        val v1 = pA - vertex
        val v2 = pC - vertex
        val n1 = v1.norm()
        val n2 = v2.norm()
        if (n1 < 1e-4 || n2 < 1e-4) return
        val u1 = v1 / n1
        val u2 = v2 / n2
        val cosT = u1.dot(u2).coerceIn(-1.0, 1.0)
        val angleRad = acos(cosT)
        if (angleRad < 1e-3) return

        val perpRaw = u2 - u1 * cosT
        if (perpRaw.norm() < 1e-5) return
        val uPerp = perpRaw.normalized()

        val radius = (minOf(n1, n2) * 0.35).coerceIn(0.018, 0.14)
        val camDist = cameraPos.distanceTo(vertex).coerceIn(0.15, 5.0)
        val halfW = camDist * 0.0018 * thicknessScale

        val steps = 20
        var prev = vertex + u1 * radius
        for (i in 1..steps) {
            val theta = (angleRad * i) / steps
            val curr = vertex + (u1 * cos(theta) + uPerp * sin(theta)) * radius
            addTriangle(vertex, prev, curr, r, g, b, a * 0.24f)
            addRibbon3D(prev, curr, cameraPos, halfW, r, g, b, a)
            prev = curr
        }
    }

    /** Draws a 12-edge 3D wireframe box from 8 corners (0..3 bottom, 4..7 top). */
    fun addBoxWireframe3D(
        corners: List<Vec3>,
        cameraPos: Vec3,
        thicknessScale: Double,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        if (corners.size != 8) return
        val mid = (corners[0] + corners[6]) * 0.5
        val camDist = cameraPos.distanceTo(mid).coerceIn(0.15, 5.0)
        val halfW = camDist * 0.0018 * thicknessScale

        // Subtle shaded footprint on the bottom face.
        addFilledPolygon3D(corners.subList(0, 4), r, g, b, a * 0.16f)

        val edges = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0, // bottom
            4 to 5, 5 to 6, 6 to 7, 7 to 4, // top
            0 to 4, 1 to 5, 2 to 6, 3 to 7, // verticals
        )
        for ((i0, i1) in edges) {
            val p0 = corners[i0]
            val p1 = corners[i1]
            addRibbon3D(p0, p1, cameraPos, halfW * 1.6, 0f, 0f, 0f, 0.55f * a)
            addRibbon3D(p0, p1, cameraPos, halfW, r, g, b, a)
        }
        for (i in 0 until 4) {
            addAnchorPin3D(corners[i], cameraPos, r, g, b, scale = 0.72)
        }
        addAnchorPin3D(corners[6], cameraPos, 1.0f, 0.84f, 0.0f, scale = 0.85)
    }

    /** Draws a crisp vertical plumb line showing pure vertical height between base and top. */
    fun addPlumbLine3D(
        base: Vec3,
        top: Vec3,
        cameraPos: Vec3,
        thicknessScale: Double,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        isPreview: Boolean,
    ) {
        val verticalTop = Vec3(base.x, top.y, base.z)
        val camDist = cameraPos.distanceTo((base + verticalTop) * 0.5).coerceIn(0.15, 5.0)
        val halfW = camDist * 0.0022 * thicknessScale
        val dashLen = (camDist * 0.014).coerceIn(0.004, 0.022)

        if (isPreview) {
            addDashedRibbon3D(base, verticalTop, cameraPos, halfW, dashLen, r, g, b, a)
        } else {
            addRibbon3D(base, verticalTop, cameraPos, halfW * 1.65, 0f, 0f, 0f, 0.6f * a)
            addRibbon3D(base, verticalTop, cameraPos, halfW, r, g, b, a)
        }

        // Dashed horizontal transfer line from top picked point back to the plumb axis.
        if (verticalTop.distanceTo(top) > 0.008) {
            addDashedRibbon3D(verticalTop, top, cameraPos, halfW * 0.75, dashLen, 1f, 1f, 1f, a * 0.82f)
        }

        // Horizontal flat target rings at base and verticalTop.
        val capR = (camDist * 0.014).coerceIn(0.006, 0.024)
        addRing3D(base, Vec3.UNIT_Y, capR * 0.65, capR, 20, r, g, b, a)
        addRing3D(verticalTop, Vec3.UNIT_Y, capR * 0.65, capR, 20, 1.0f, 0.84f, 0.0f, a)
        addAnchorPin3D(base, cameraPos, 0.0f, 0.90f, 0.46f, scale = 0.9)
        if (!isPreview) {
            addAnchorPin3D(top, cameraPos, 1.0f, 0.84f, 0.0f, scale = 0.9)
        }
    }

    fun flush(viewProjColMajor: FloatArray) {
        if (vertexCount == 0) return

        vertexBuffer.position(0)
        vertexBuffer.put(vertices, 0, vertexCount * FLOATS_PER_VERTEX)
        vertexBuffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjColMajor, 0)

        val strideBytes = FLOATS_PER_VERTEX * 4
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(colorAttrib)
        GLES20.glVertexAttribPointer(colorAttrib, 4, GLES20.GL_FLOAT, false, strideBytes, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun addTriangle(
        v0: Vec3,
        v1: Vec3,
        v2: Vec3,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        if (vertexCount + 3 > MAX_VERTICES) return
        putVertex(v0, r, g, b, a)
        putVertex(v1, r, g, b, a)
        putVertex(v2, r, g, b, a)
    }

    private fun putVertex(v: Vec3, r: Float, g: Float, b: Float, a: Float) {
        val base = vertexCount * FLOATS_PER_VERTEX
        vertices[base + 0] = v.x.toFloat()
        vertices[base + 1] = v.y.toFloat()
        vertices[base + 2] = v.z.toFloat()
        vertices[base + 3] = r
        vertices[base + 4] = g
        vertices[base + 5] = b
        vertices[base + 6] = a
        vertexCount++
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) {
            "Overlay3DRenderer shader error: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val FLOATS_PER_VERTEX = 7
        private const val MAX_VERTICES = 40000

        private const val VERTEX_SHADER = """
            uniform mat4 u_MvpMatrix;
            attribute vec3 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_MvpMatrix * vec4(a_Position, 1.0);
                v_Color = a_Color;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = v_Color;
            }
        """
    }
}
