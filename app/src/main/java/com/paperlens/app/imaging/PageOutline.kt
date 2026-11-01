package com.paperlens.app.imaging

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where a page lies in a photo: four corners (TL, TR, BR, BL, clockwise) and, for curved book pages,
 * how far each side bows away from the straight line between its corners.
 *
 * [bends] has one array per side (TL→TR, TR→BR, BR→BL, BL→TL) of [SAMPLES] offsets along the side,
 * measured outwards and as a fraction of that side's length, so they stay valid when a corner is moved.
 */
class PageOutline(val corners: List<PointF>, val bends: List<FloatArray>? = null) {

    /** True when any side bows noticeably (a curled book page) and flattening should follow it. */
    val curved: Boolean get() = bends?.any { side -> side.any { abs(it) > 0.004f } } == true

    fun withCorners(newCorners: List<PointF>) = PageOutline(newCorners, bends)

    fun scaled(f: Float) = PageOutline(corners.map { PointF(it.x * f, it.y * f) }, bends)

    /** Point on [side] at [t] (0 = its first corner, 1 = the next one). */
    fun sidePoint(side: Int, t: Float): PointF {
        val a = corners[side]
        val b = corners[(side + 1) % 4]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val off = bend(side, t) * hypot(dx, dy)
        // Outward normal of a clockwise outline in screen coordinates (y down).
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        return PointF(a.x + dx * t + dy / len * off, a.y + dy * t - dx / len * off)
    }

    private fun bend(side: Int, t: Float): Float {
        val b = bends?.getOrNull(side) ?: return 0f
        val x = (t.coerceIn(0f, 1f) * (b.size - 1))
        val i = x.toInt().coerceAtMost(b.size - 2)
        val f = x - i
        return b[i] * (1 - f) + b[i + 1] * f
    }

    /** "x,y,x,y,x,y,x,y" plus ";" and the bends when curved. */
    fun encode(): String {
        val c = corners.joinToString(",") { "${it.x},${it.y}" }
        val b = bends?.takeIf { curved } ?: return c
        return c + ";" + b.joinToString("|") { side -> side.joinToString(",") }
    }

    companion object {
        const val SAMPLES = 17

        fun decode(text: String?): PageOutline? {
            if (text.isNullOrBlank()) return null
            val parts = text.split(";")
            val nums = parts[0].split(",").mapNotNull { it.toFloatOrNull() }
            if (nums.size != 8) return null
            val corners = nums.chunked(2).map { (x, y) -> PointF(x, y) }
            val bends = parts.getOrNull(1)?.split("|")?.map { side -> side.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray() }
                ?.takeIf { sides -> sides.size == 4 && sides.all { it.size >= 2 } }
            return PageOutline(corners, bends)
        }

        fun of(corners: List<PointF>) = PageOutline(corners, null)
    }
}
