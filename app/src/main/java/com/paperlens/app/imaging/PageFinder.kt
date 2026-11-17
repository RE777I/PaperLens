package com.paperlens.app.imaging

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds paper by its colour, the way book scanners such as vFlat do: pages are brighter and less
 * colourful than the desk or floor around them. Unlike line finding this follows a page's real outline,
 * so the curl of an open book and the bulge near its spine are kept as [PageOutline.bends] and can be
 * flattened.
 *
 *  1. "Paperness" = lightness − 1.6 × colourfulness, split with Otsu's threshold.
 *  2. Clean-up (open/close), largest region, holes (text, pictures) filled.
 *  3. Corners: the largest quadrilateral on the region's convex hull.
 *  4. Sides: rays across each corner-to-corner line find the region's edge; a robust cubic through those
 *     points ignores dents such as a finger holding the page.
 *
 * The region must fill its quadrilateral well and the sides may only bow a little; anything else (a
 * white wall, a shop front) is rejected so other detectors can try.
 */
object PageFinder {

    private const val WORK = 480

    fun find(src: Bitmap): PageOutline? {
        val scale = min(1f, WORK.toFloat() / max(src.width, src.height))
        val w = max(8, (src.width * scale).roundToInt())
        val h = max(8, (src.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val light = FloatArray(w * h)
        val score = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            light[i] = (mx + mn) / 2f
            score[i] = light[i] - 1.6f * (mx - mn)
        }
        val smooth = boxBlur(boxBlur(score, w, h), w, h)
        val t = otsu(smooth)
        var mask = BooleanArray(w * h) { smooth[it] > t }
        mask = erode(mask, w, h, 2); mask = dilate(mask, w, h, 2)
        mask = dilate(mask, w, h, 4); mask = erode(mask, w, h, 4)
        mask = largestRegion(mask, w, h) ?: return null
        fillHoles(mask, w, h)

        val area = mask.count { it }
        val frac = area.toFloat() / (w * h)
        if (frac < 0.08f || frac > 0.97f) return null

        // Paper must be clearly lighter than what surrounds it.
        var inSum = 0.0; var outSum = 0.0
        for (i in mask.indices) if (mask[i]) inSum += light[i] else outSum += light[i]
        val outCount = w * h - area
        if (outCount > 0 && inSum / area - outSum / outCount < 18) return null

        val quad = hullQuad(mask, w, h) ?: return null
        val (corners, quadArea) = quad
        val solidity = area / quadArea.coerceAtLeast(1f)
        if (solidity < 0.88f || solidity > 1.16f) return null

        val bends = (0 until 4).map { side -> sideBend(mask, w, h, corners, side) ?: return null }
        if (bends.any { b -> b.any { abs(it) > 0.08f } }) return null
        return PageOutline(corners.map { PointF(it.x / scale, it.y / scale) }, bends)
    }

    // ---- mask ------------------------------------------------------------------------------------

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = neighbourMean(src, w, h, x, y)
        return out
    }

    /** Mean of the pixel at (x, y) and its neighbours that lie inside the image. */
    private fun neighbourMean(src: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        var sum = 0f
        var count = 0
        for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
            sum += src[yy * w + xx]
            count++
        }
        return sum / count
    }

    private fun otsu(v: FloatArray): Float {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (x in v) { if (x < lo) lo = x; if (x > hi) hi = x }
        if (hi - lo < 1e-3f) return hi
        val bins = 256
        val hist = IntArray(bins)
        val k = (bins - 1) / (hi - lo)
        for (x in v) hist[((x - lo) * k).toInt()]++
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until bins) sumAll += i.toDouble() * hist[i]
        var sumB = 0.0; var wB = 0.0; var best = -1.0; var bestI = 0
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestI = i }
        }
        return lo + bestI / k
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        var cur = m
        repeat(r) {
            val out = cur.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                if (cur[y * w + x]) continue
                if ((x > 0 && cur[y * w + x - 1]) || (x < w - 1 && cur[y * w + x + 1]) ||
                    (y > 0 && cur[(y - 1) * w + x]) || (y < h - 1 && cur[(y + 1) * w + x])
                ) out[y * w + x] = true
            }
            cur = out
        }
        return cur
    }

    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val inv = BooleanArray(m.size) { !m[it] }
        val d = dilate(inv, w, h, r)
        return BooleanArray(m.size) { !d[it] }
    }

    /** Labels 4-connected regions and keeps the biggest. */
    private fun largestRegion(m: BooleanArray, w: Int, h: Int): BooleanArray? {
        val label = IntArray(m.size)
        val queue = IntArray(m.size)
        var next = 0; var bestLabel = 0; var bestSize = 0
        for (start in m.indices) {
            if (!m[start] || label[start] != 0) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x > 0 && m[i - 1] && label[i - 1] == 0) { label[i - 1] = next; queue[tail++] = i - 1 }
                if (x < w - 1 && m[i + 1] && label[i + 1] == 0) { label[i + 1] = next; queue[tail++] = i + 1 }
                if (y > 0 && m[i - w] && label[i - w] == 0) { label[i - w] = next; queue[tail++] = i - w }
                if (y < h - 1 && m[i + w] && label[i + w] == 0) { label[i + w] = next; queue[tail++] = i + w }
            }
            if (tail > bestSize) { bestSize = tail; bestLabel = next }
        }
        if (bestLabel == 0) return null
        return BooleanArray(m.size) { label[it] == bestLabel }
    }

    /** Fills every background pocket that does not reach the photo border. */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(m.size)
        val queue = IntArray(m.size)
        var tail = 0
        fun seed(i: Int) { if (!m[i] && !outside[i]) { outside[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        for (i in m.indices) if (!m[i] && !outside[i]) m[i] = true
    }

    // ---- corners ---------------------------------------------------------------------------------

    /** Largest quadrilateral with corners on the convex hull of the region, ordered TL, TR, BR, BL. */
    private fun hullQuad(m: BooleanArray, w: Int, h: Int): Pair<List<PointF>, Float>? {
        val boundary = boundaryPoints(m, w, h)
        if (boundary.size < 4) return null
        var hull = convexHull(boundary)
        if (hull.size < 4) return null
        if (hull.size > 80) hull = List(80) { hull[it * (hull.size - 1) / 79] }
        val n = hull.size
        var best: List<PointF>? = null
        var bestArea = -1f
        // Every diagonal i–k, with the farthest hull point on each side of it as the other two corners.
        for (i in 0 until n) for (k in i + 2 until n) {
            if (k - i >= n - 1) continue
            val (j, nearSide) = farthest(hull, i, k, i + 1 until k) ?: continue
            val (l, farSide) = farthest(hull, i, k, (k + 1 until n) + (0 until i)) ?: continue
            if (nearSide + farSide > bestArea) {
                bestArea = nearSide + farSide
                best = listOf(hull[i], hull[j], hull[k], hull[l])
            }
        }
        return best?.let { order(it) to bestArea / 2 }
    }

    /** The region's pixels that touch the outside (or the image border). */
    private fun boundaryPoints(m: BooleanArray, w: Int, h: Int): MutableList<PointF> {
        val pts = ArrayList<PointF>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!m[i]) continue
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 || !m[i - 1] || !m[i + 1] || !m[i - w] || !m[i + w]
            if (edge) pts += PointF(x.toFloat(), y.toFloat())
        }
        return pts
    }

    private fun cross(o: PointF, a: PointF, b: PointF) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Convex hull (Andrew's monotone chain); sorts [pts]. */
    private fun convexHull(pts: MutableList<PointF>): List<PointF> {
        pts.sortWith(compareBy<PointF> { it.x }.thenBy { it.y })
        fun chain(points: List<PointF>): List<PointF> {
            val out = ArrayList<PointF>()
            for (p in points) {
                while (out.size >= 2 && cross(out[out.size - 2], out[out.size - 1], p) <= 0) out.removeAt(out.size - 1)
                out += p
            }
            return out
        }
        return chain(pts).dropLast(1) + chain(pts.asReversed()).dropLast(1)
    }

    /**
     * Among the hull points at [candidates], the one farthest from the line through hull[i] and hull[k] (the
     * first of equals), with that distance times the line's length; null when there are no candidates.
     */
    private fun farthest(hull: List<PointF>, i: Int, k: Int, candidates: Iterable<Int>): Pair<Int, Float>? {
        var best = -1
        var bestSpan = -1f
        for (q in candidates) {
            val span = abs(cross(hull[i], hull[k], hull[q]))
            if (span > bestSpan) {
                bestSpan = span
                best = q
            }
        }
        return if (best < 0) null else best to bestSpan
    }

    /** Clockwise from the top-left, where "top" is the side closest to horizontal. */
    private fun order(p: List<PointF>): List<PointF> {
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        val s = p.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        val start = (0 until 4).filter { s[(it + 1) % 4].x > s[it].x }.minByOrNull { k ->
            val a = s[k]; val b = s[(k + 1) % 4]
            abs(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())) * 4 + (a.y + b.y) / (2.0 * max(1f, cy * 2))
        } ?: 0
        return List(4) { s[(start + it) % 4] }
    }

    // ---- sides -----------------------------------------------------------------------------------

    /** Outward bow of one side as fractions of its length at [PageOutline.SAMPLES] points. */
    private fun sideBend(m: BooleanArray, w: Int, h: Int, c: List<PointF>, side: Int): FloatArray? {
        val a = c[side]; val b = c[(side + 1) % 4]
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 8f) return null
        val nx = dy / len; val ny = -dx / len // outward for a clockwise outline
        val reach = 0.18f * len
        val rays = 48
        val ts = FloatArray(rays); val offs = FloatArray(rays); var count = 0
        for (r in 1 until rays - 1) {
            val t = r.toFloat() / (rays - 1)
            val px = a.x + dx * t; val py = a.y + dy * t
            // Walk from outside inwards; the first page pixel is the edge.
            var s = reach
            while (s >= -reach) {
                val x = (px + nx * s).roundToInt(); val y = (py + ny * s).roundToInt()
                if (x in 0 until w && y in 0 until h && m[y * w + x]) {
                    ts[count] = t; offs[count] = s / len; count++
                    break
                }
                s -= 1f
            }
        }
        if (count < 6) return null
        val coef = robustCubic(ts.copyOf(count), offs.copyOf(count))
        fun f(t: Float) = coef[0] + coef[1] * t + coef[2] * t * t + coef[3] * t * t * t
        val f0 = f(0f); val f1 = f(1f)
        // Pin the ends to the corners.
        return FloatArray(PageOutline.SAMPLES) { i ->
            val t = i.toFloat() / (PageOutline.SAMPLES - 1)
            (f(t) - (f0 + (f1 - f0) * t)).toFloat()
        }
    }

    /** Cubic least squares, refitted twice without points far off the curve (fingers, nicks). */
    private fun robustCubic(t: FloatArray, y: FloatArray): DoubleArray {
        var keep = BooleanArray(t.size) { true }
        var coef = DoubleArray(4)
        repeat(3) {
            coef = fitCubic(t, y, keep) ?: return coef
            val res = FloatArray(t.size) { i -> abs(y[i] - (coef[0] + coef[1] * t[i] + coef[2] * t[i] * t[i] + coef[3] * t[i] * t[i] * t[i])).toFloat() }
            val kept = res.filterIndexed { i, _ -> keep[i] }.sorted()
            val mad = (kept.getOrElse(kept.size / 2) { 0f } * 1.4826f).coerceAtLeast(0.002f)
            keep = BooleanArray(t.size) { res[it] < 2.5f * mad }
            if (keep.count { it } < 6) return coef
        }
        return coef
    }

    private fun fitCubic(t: FloatArray, y: FloatArray, keep: BooleanArray): DoubleArray? {
        // Normal equations of the least-squares fit, as a 4 × 5 augmented matrix.
        val a = Array(4) { DoubleArray(5) }
        for (i in t.indices) if (keep[i]) addSample(a, t[i], y[i])
        // Gaussian elimination with partial pivoting.
        for (col in 0 until 4) if (!eliminate(a, col)) return null
        return DoubleArray(4) { a[it][4] / a[it][it] }
    }

    /** Adds the point (t, y) to the normal equations [a] of a cubic fit. */
    private fun addSample(a: Array<DoubleArray>, t: Float, y: Float) {
        val p = doubleArrayOf(1.0, t.toDouble(), (t * t).toDouble(), (t * t * t).toDouble())
        for (r in 0 until 4) {
            for (c in 0 until 4) a[r][c] += p[r] * p[c]
            a[r][4] += p[r] * y
        }
    }

    /** Clears column [col] from every other row of [a]; false when the system has no unique solution. */
    private fun eliminate(a: Array<DoubleArray>, col: Int): Boolean {
        val pivot = (col until 4).maxBy { abs(a[it][col]) }
        if (abs(a[pivot][col]) < 1e-12) return false
        val swapped = a[col]; a[col] = a[pivot]; a[pivot] = swapped
        for (r in 0 until 4) {
            if (r == col) continue
            val f = a[r][col] / a[col][col]
            for (c in col until 5) a[r][c] -= f * a[col][c]
        }
        return true
    }
}
