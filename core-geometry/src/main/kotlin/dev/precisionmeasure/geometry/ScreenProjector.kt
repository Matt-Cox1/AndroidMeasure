package dev.precisionmeasure.geometry

/**
 * Projects 3D world points and 3D line segments into viewport pixels using ARCore's exact
 * column-major 4x4 View and Projection matrices, with proper near-plane clipping so 3D lines
 * stay smooth and visible even when one endpoint pans off-screen or behind the camera.
 */
object ScreenProjector {

    /**
     * Multiplies column-major 4x4 matrices: out = a * b.
     */
    fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 16 && b.size == 16)
        val out = FloatArray(16)
        for (col in 0 until 4) {
            val b0 = b[col * 4 + 0]
            val b1 = b[col * 4 + 1]
            val b2 = b[col * 4 + 2]
            val b3 = b[col * 4 + 3]
            out[col * 4 + 0] = a[0] * b0 + a[4] * b1 + a[8] * b2 + a[12] * b3
            out[col * 4 + 1] = a[1] * b0 + a[5] * b1 + a[9] * b2 + a[13] * b3
            out[col * 4 + 2] = a[2] * b0 + a[6] * b1 + a[10] * b2 + a[14] * b3
            out[col * 4 + 3] = a[3] * b0 + a[7] * b1 + a[11] * b2 + a[15] * b3
        }
        return out
    }

    /**
     * Projects a single 3D world point to viewport pixel coordinates (top-left origin).
     * Returns null if the point is behind the camera near plane.
     */
    fun projectPoint(
        viewProjColMajor: FloatArray,
        worldPoint: Vec3,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Vec2? {
        val clip = transformToClip(viewProjColMajor, worldPoint)
        if (clip.w <= 0.05) return null
        return clipToViewport(clip, viewportWidth, viewportHeight)
    }

    /**
     * Projects a 3D segment [worldA]..[worldB] into viewport pixels, clipping cleanly against
     * the near plane (clip.w >= 0.05) so partially off-screen 3D lines never vanish.
     */
    fun projectSegmentClipped(
        viewProjColMajor: FloatArray,
        worldA: Vec3,
        worldB: Vec3,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Pair<Vec2, Vec2>? {
        val cA = transformToClip(viewProjColMajor, worldA)
        val cB = transformToClip(viewProjColMajor, worldB)
        val minW = 0.05

        if (cA.w <= minW && cB.w <= minW) return null

        val clippedA = if (cA.w > minW) {
            cA
        } else {
            val t = (minW - cA.w) / (cB.w - cA.w)
            lerpClip(cA, cB, t)
        }

        val clippedB = if (cB.w > minW) {
            cB
        } else {
            val t = (minW - cB.w) / (cA.w - cB.w)
            lerpClip(cB, cA, t)
        }

        return clipToViewport(clippedA, viewportWidth, viewportHeight) to
            clipToViewport(clippedB, viewportWidth, viewportHeight)
    }

    private data class Clip4(val x: Double, val y: Double, val z: Double, val w: Double)

    private fun transformToClip(m: FloatArray, p: Vec3): Clip4 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return Clip4(x, y, z, w)
    }

    private fun lerpClip(a: Clip4, b: Clip4, t: Double) = Clip4(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t,
        z = a.z + (b.z - a.z) * t,
        w = a.w + (b.w - a.w) * t,
    )

    private fun clipToViewport(c: Clip4, viewportWidth: Int, viewportHeight: Int): Vec2 {
        val ndcX = c.x / c.w
        val ndcY = c.y / c.w
        val sx = (ndcX * 0.5 + 0.5) * viewportWidth
        val sy = (0.5 - ndcY * 0.5) * viewportHeight
        return Vec2(sx, sy)
    }
}
