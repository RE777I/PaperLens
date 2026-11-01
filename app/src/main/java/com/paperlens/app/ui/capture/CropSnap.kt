package com.paperlens.app.ui.capture

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Magnetic crop handles. A dragged corner jumps onto a nearby corner of the page (or of the photo);
 * a dragged side jumps onto a nearby straight edge running the same way, its two corners sliding along
 * the neighbouring sides. All coordinates are photo pixels.
 */
internal class CropSnap(
    private val width: Float,
    private val height: Float,
    private val points: List<PointF>,
    private val edges: List<Pair<PointF, PointF>>,
) {
    /** Where corner [index] goes when dragged by [drag] from [start]; second = whether it snapped. */
    fun moveCorner(start: List<PointF>, index: Int, drag: PointF, radius: Float): Pair<List<PointF>, Boolean> {
        val raw = PointF(start[index].x + drag.x, start[index].y + drag.y)
        val target = points.filter { dist(it, raw) <= radius }.minByOrNull { dist(it, raw) }
        val out = start.toMutableList()
        out[index] = clamp(target ?: raw)
        return out to (target != null)
    }

    /**
     * Side [side] (corners side → side + 1) moved by [drag] across its own direction, its corners
     * sliding along the two neighbouring sides; snapped onto a guide edge within [radius] that runs
     * the same way (±10°). Second = whether it snapped.
     */
    fun moveSide(start: List<PointF>, side: Int, drag: PointF, radius: Float): Pair<List<PointF>, Boolean> {
        val i = side
        val j = (side + 1) % 4
        val a = start[i]
        val b = start[j]
        val len = dist(a, b).coerceAtLeast(1f)
        val nx = -(b.y - a.y) / len
        val ny = (b.x - a.x) / len
        val shift = drag.x * nx + drag.y * ny
        val ra = PointF(a.x + nx * shift, a.y + ny * shift)
        val rb = PointF(b.x + nx * shift, b.y + ny * shift)
        val guide = guideFor(ra, rb, radius)
        val (la, lb) = guide ?: (ra to rb)
        // Neighbouring sides: (i - 1) → i and j → (j + 1).
        val prev = start[(i + 3) % 4]
        val next = start[(j + 1) % 4]
        val na = intersect(la, lb, prev, a)
        val nb = intersect(la, lb, b, next)
        val moved = if (na != null && nb != null && reasonable(na, a, len) && reasonable(nb, b, len)) {
            na to nb
        } else {
            ra to rb
        }
        val out = start.toMutableList()
        out[i] = clamp(moved.first)
        out[j] = clamp(moved.second)
        if (!convex(out)) return start to false
        return out to (guide != null)
    }

    /** The guide edge closest to the line ra–rb, if one runs the same way within [radius] of it. */
    private fun guideFor(ra: PointF, rb: PointF, radius: Float): Pair<PointF, PointF>? {
        val mid = PointF((ra.x + rb.x) / 2, (ra.y + rb.y) / 2)
        val len = dist(ra, rb)
        val dx = (rb.x - ra.x) / len.coerceAtLeast(1f)
        val dy = (rb.y - ra.y) / len.coerceAtLeast(1f)
        return edges.mapNotNull { (p, q) ->
            val gl = dist(p, q).coerceAtLeast(1f)
            val gx = (q.x - p.x) / gl
            val gy = (q.y - p.y) / gl
            if (abs(dx * gx + dy * gy) < COS_10) return@mapNotNull null
            // Distance from the side's middle to the guide's line, and the guide must reach alongside it.
            val off = abs((mid.x - p.x) * gy - (mid.y - p.y) * gx)
            val along = (mid.x - p.x) * gx + (mid.y - p.y) * gy
            if (off > radius || along < -0.3f * len || along > gl + 0.3f * len) return@mapNotNull null
            Triple(p, q, off)
        }.minByOrNull { it.third }?.let { it.first to it.second }
    }

    /** A corner may slide along its side, but not to the far side of the photo. */
    private fun reasonable(p: PointF, from: PointF, sideLength: Float) =
        dist(p, from) < 1.5f * sideLength + 0.5f * hypot(width, height) &&
            p.x > -0.05f * width && p.x < 1.05f * width && p.y > -0.05f * height && p.y < 1.05f * height

    private fun clamp(p: PointF) = PointF(p.x.coerceIn(0f, width), p.y.coerceIn(0f, height))

    companion object {
        private const val COS_10 = 0.985f

        fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

        /** Where line a1–a2 meets line b1–b2, or null when they are (nearly) parallel. */
        fun intersect(a1: PointF, a2: PointF, b1: PointF, b2: PointF): PointF? {
            val d1x = a2.x - a1.x; val d1y = a2.y - a1.y
            val d2x = b2.x - b1.x; val d2y = b2.y - b1.y
            val den = d1x * d2y - d1y * d2x
            if (abs(den) < 1e-3f * hypot(d1x, d1y) * hypot(d2x, d2y)) return null
            val t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / den
            return PointF(a1.x + d1x * t, a1.y + d1y * t)
        }

        fun convex(q: List<PointF>): Boolean {
            val turns = (0 until 4).map { k ->
                val a = q[k]; val b = q[(k + 1) % 4]; val c = q[(k + 2) % 4]
                (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            }
            return turns.all { it > 0 } || turns.all { it < 0 }
        }
    }
}
