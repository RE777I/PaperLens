package com.paperlens.app.ui.capture

import android.graphics.PointF
import com.paperlens.app.imaging.PageOutline
import kotlin.math.hypot
import kotlin.math.max

/**
 * Steadies the live page outline the way scanner apps do: small frame-to-frame movements are averaged
 * away, a page missed for a frame or two keeps its outline, and [stableFrames] counts how long the page
 * has held still (used for auto-capture). Called from the camera analysis thread only.
 */
class LiveTracker {
    private var smooth: PageOutline? = null
    private var misses = 0

    /** Consecutive frames in which the page moved less than [STILL] of the frame diagonal. */
    var stableFrames = 0
        private set

    /** How far the page moved in the last frame, as a fraction of the frame diagonal (1 = lost). */
    var lastMove = 1f
        private set

    fun update(found: PageOutline?, width: Int, height: Int): PageOutline? {
        val diagonal = hypot(width.toFloat(), height.toFloat())
        if (found == null) {
            misses++
            if (misses > MAX_MISSES) {
                smooth = null
                stableFrames = 0
                lastMove = 1f
            }
            return smooth
        }
        misses = 0
        val prev = smooth
        if (prev == null) {
            smooth = found
            stableFrames = 0
            lastMove = 1f
            return found
        }
        var move = 0f
        for (i in 0 until 4) move = max(move, hypot(prev.corners[i].x - found.corners[i].x, prev.corners[i].y - found.corners[i].y))
        lastMove = move / diagonal
        smooth = if (lastMove < SAME_PAGE) blend(prev, found) else found
        stableFrames = if (lastMove < STILL) stableFrames + 1 else 0
        return smooth
    }

    private fun blend(a: PageOutline, b: PageOutline): PageOutline {
        val corners = List(4) { PointF((a.corners[it].x + b.corners[it].x) / 2, (a.corners[it].y + b.corners[it].y) / 2) }
        val ab = a.bends
        val bb = b.bends
        val bends = if (ab != null && bb != null) List(4) { s -> FloatArray(ab[s].size) { (ab[s][it] + bb[s][it]) / 2 } } else bb
        return PageOutline(corners, bends)
    }

    companion object {
        private const val MAX_MISSES = 2
        private const val SAME_PAGE = 0.08f
        private const val STILL = 0.02f
    }
}
