package com.paperlens.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.paperlens.app.runLogged
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds a document (page, card, sign, screen, whiteboard…) in a photo and flattens it, for images
 * that did not come through the ML Kit scanner (gallery imports, shared files, the in-app camera).
 *
 * First choice is the learned [CornerModel]. The fallback is edge-based, like classic scanner apps:
 *  1. Canny-style edges on a ~480 px grey copy (blur, Sobel, non-maximum suppression, hysteresis).
 *  2. Hough transform for straight lines, split into a "horizontal" and a "vertical" family; the
 *     photo borders are added as candidates so a page running off the photo can still be cropped.
 *  3. Every pair × pair of lines is a candidate quadrilateral. It must be large enough, roughly
 *     rectangular, use at most one photo border, and each real side must both follow detected edges
 *     and separate consistently different brightness (which rejects lines of text inside a page).
 *  4. The best score (edge support × side contrast × size) wins; doubtful results return null and
 *     the photo is kept uncropped, because a wrong crop is worse than none.
 */
object DocumentDetector {

    private const val WORK = 480
    private const val EDGE_TRIM = 0.006f
    private const val THETAS = 180
    private const val MAX_LINES = 24

    private val cosT = DoubleArray(THETAS) { cos(it * PI / THETAS) }
    private val sinT = DoubleArray(THETAS) { sin(it * PI / THETAS) }

    private class Line(val rho: Double, val theta: Double, val border: Boolean = false)

    /**
     * Corners TL, TR, BR, BL in [src] pixel coordinates, or null when no clear document is found.
     * The learned [CornerModel] goes first; line finding is the fallback for what it does not treat as
     * a document (whiteboards, screens, book pages filling the frame).
     */
    fun detect(src: Bitmap): List<PointF>? = detectOutline(src)?.corners

    /**
     * The page outline, curved sides included. The learned model is best for flat sheets and covers, the
     * paper-colour finder for open books and notebooks (which the model often misses), line finding for
     * the rest. When the model and the paper outline agree, the model's corners get the paper's curl.
     */
    fun detectOutline(src: Bitmap): PageOutline? {
        val found = runLogged("DocumentDetector", "Corner model") { CornerModel.detectPass(src) }.getOrNull()
        val learned = found?.corners?.let(::order)?.takeIf { plausible(it, src.width, src.height) }
        val paper = runLogged("DocumentDetector", "Paper finder") { PageFinder.find(src) }.getOrNull()
        // The model calls the whole photo the page for screenshots and photos of a screen; a smaller page
        // the paper finder sees inside it is the better crop.
        if (learned != null && paper != null && fillsPhoto(learned, src.width, src.height) && !fillsPhoto(paper.corners, src.width, src.height)) {
            return paper
        }
        if (learned != null) {
            val diagonal = hypot(src.width.toFloat(), src.height.toFloat())
            val agree = paper != null && learned.indices.all { dist(learned[it], paper.corners[it]) < 0.04f * diagonal }
            // The padded look sees the photo smaller; where the paper finder traced the same page, its edge is closer.
            if (agree && found!!.padded) return paper
            return if (agree && paper!!.curved) PageOutline(learned, paper.bends) else PageOutline.of(learned)
        }
        if (paper != null) return paper
        return detectByEdges(src)?.let(PageOutline::of)
    }

    /** Every corner within 4 % of the photo's matching corner. */
    private fun fillsPhoto(q: List<PointF>, w: Int, h: Int): Boolean {
        val tol = 0.04f * hypot(w.toFloat(), h.toFloat())
        val photo = listOf(PointF(0f, 0f), PointF(w.toFloat(), 0f), PointF(w.toFloat(), h.toFloat()), PointF(0f, h.toFloat()))
        return q.indices.all { dist(q[it], photo[it]) < tol }
    }

    /** Rejects twisted or tiny quadrilaterals (e.g. two corners on the same spot). */
    private fun plausible(q: List<PointF>, w: Int, h: Int): Boolean {
        val area = polygonArea(q)
        if (area < 0.02f * w * h) return false
        // convex: every turn goes the same way
        val turns = (0 until 4).map { i ->
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        if (!(turns.all { it > 0 } || turns.all { it < 0 })) return false
        val angles = cornerAngles(q)
        if (angles.min() <= 20f || angles.max() >= 160f) return false
        // Opposite sides of a photographed page stay within ~25° of each other even at steep angles (SmartDoc
        // ground truth: at most 24.9° over 24,889 frames). Beyond 35° one corner jumped onto something
        // else, e.g. the corner of a shaded box inside the page.
        return oppositeSkew(q) <= 35f
    }

    /** The larger angle between the top and bottom sides or between the left and right sides, in degrees. */
    private fun oppositeSkew(q: List<PointF>): Float {
        fun dir(a: PointF, b: PointF) = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
        fun apart(a: Double, b: Double): Double = abs(a - b).mod(180.0).let { min(it, 180 - it) }
        return max(apart(dir(q[0], q[1]), dir(q[3], q[2])), apart(dir(q[0], q[3]), dir(q[1], q[2]))).toFloat()
    }

    /** Line-finding detector (see the class comment). */
    fun detectByEdges(src: Bitmap): List<PointF>? {
        val scale = min(1f, WORK.toFloat() / max(src.width, src.height))
        val w = max(8, (src.width * scale).roundToInt())
        val h = max(8, (src.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) { i ->
            val c = px[i]
            (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
        }
        val blurred = blur(gray, w, h, 1.6)
        val edges = edges(blurred, w, h)
        val photo = EdgePhoto(w, h, blurred, dilate(dilate(edges, w, h), w, h))
        val lines = hough(edges, w, h)

        val horizontal = lines.filter { abs(Math.toDegrees(it.theta) - 90) < 40 }.toMutableList()
        val vertical = lines.filter { abs(Math.toDegrees(it.theta) - 90) >= 40 }.toMutableList()
        horizontal += Line(0.0, PI / 2, true)
        horizontal += Line((h - 1).toDouble(), PI / 2, true)
        vertical += Line(0.0, 0.0, true)
        vertical += Line((w - 1).toDouble(), 0.0, true)

        // The first of equally good candidates wins (maxByOrNull keeps the first maximum).
        val best = quadCandidates(horizontal, vertical).mapNotNull { photo.score(it) }.maxByOrNull { it.score }
        if (best == null || best.support < 0.6f || best.contrast < 0.6f || best.score < 0.3f) return null
        return best.quad.map {
            PointF((it.x / scale).coerceIn(0f, src.width.toFloat()), (it.y / scale).coerceIn(0f, src.height.toFloat()))
        }
    }

    /** Every quadrilateral made of two "horizontal" and two "vertical" lines (corners unordered; null = parallel). */
    private fun quadCandidates(horizontal: List<Line>, vertical: List<Line>): Sequence<List<PointF?>> = sequence {
        for ((top, bottom) in pairs(horizontal)) for ((left, right) in pairs(vertical)) {
            yield(listOf(intersect(top, left), intersect(top, right), intersect(bottom, right), intersect(bottom, left)))
        }
    }

    /** Every pair of different items, in list order. */
    private fun <T> pairs(items: List<T>): Sequence<Pair<T, T>> = sequence {
        for (i in items.indices) for (j in i + 1 until items.size) yield(items[i] to items[j])
    }

    /** A candidate page outline with how well the photo supports it. */
    private class ScoredQuad(val quad: List<PointF>, val score: Float, val support: Float, val contrast: Float)

    /** How well the four sides of a quadrilateral are backed by the photo (see [EdgePhoto.sides]). */
    private class SideEvidence(val support: Float, val contrast: Float, val borders: Int, val weakest: Float)

    /** The small working copy of a photo that candidates are checked against. */
    private class EdgePhoto(val w: Int, val h: Int, val blurred: FloatArray, val dilated: BooleanArray) {
        private val area = (w * h).toFloat()

        /** [corners] as a scored page outline, or null when it cannot be one (shape, size or weak sides). */
        fun score(corners: List<PointF?>): ScoredQuad? {
            if (corners.any { it == null }) return null
            val raw = corners.filterNotNull()
            if (raw.any { it.x < -0.03f * w || it.x > 1.03f * w || it.y < -0.03f * h || it.y > 1.03f * h }) return null
            val q = order(raw)
            val quadArea = polygonArea(q)
            if (quadArea < 0.12f * area || quadArea > 0.985f * area) return null
            val angles = cornerAngles(q)
            if (angles.min() < 50f || angles.max() > 130f) return null

            val sides = sides(q) ?: return null
            if (sides.borders > 3) return null
            val frac = quadArea / area
            // A page cut off by the photo edge is close to the camera, so it is never small; a small box
            // touching the edge is usually packaging or clutter in a busy scene.
            if (sides.borders > 0 && frac < 0.25f) return null
            // A page filling the photo except for one or two strips (desk, hand, lap at the bottom), as
            // a scanner app crops it: only with large coverage and clean, high-contrast real sides.
            if (sides.borders > 1 && (frac < 0.7f || sides.weakest < 0.7f)) return null
            val score = sides.support * sides.contrast * (0.45f + 0.55f * sqrt(quadArea / area))
            return ScoredQuad(q, score, sides.support, sides.contrast)
        }

        /**
         * Mean edge support and brightness contrast of the four sides, or null when a real side (one not on
         * the photo border) has too little of either. Border sides count as moderately good.
         */
        private fun sides(q: List<PointF>): SideEvidence? {
            val cx = q.map { it.x }.average().toFloat()
            val cy = q.map { it.y }.average().toFloat()
            var support = 0f
            var contrast = 0f
            var borders = 0
            var weakest = 1f
            for (s in 0 until 4) {
                val a = q[s]
                val b = q[(s + 1) % 4]
                if (onBorder(a, b, w, h)) {
                    borders++
                    support += 0.6f
                    contrast += 0.6f
                    continue
                }
                val sp = sideSupport(dilated, w, h, a, b)
                val cs = sideContrast(blurred, w, h, a, b, cx, cy)
                if (cs == null || sp < 0.35f || cs < 0.45f) return null
                support += sp
                contrast += cs
                weakest = min(weakest, min(sp, cs))
            }
            return SideEvidence(support / 4f, contrast / 4f, borders, weakest)
        }
    }

    /** Straight edges in a photo ([Guides.edges], as end points) and the corners where two of them meet. */
    class Guides(val edges: List<Pair<PointF, PointF>>, val corners: List<PointF>)

    /**
     * Snap targets for the crop handles: the stretches of the strongest straight lines that really
     * follow edges in [src] (page borders, sign frames, table edges…), and the points where two roughly
     * perpendicular ones meet near both. In [src] pixel coordinates.
     */
    fun guides(src: Bitmap): Guides {
        val scale = min(1f, WORK.toFloat() / max(src.width, src.height))
        val w = max(8, (src.width * scale).roundToInt())
        val h = max(8, (src.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h) { i ->
            val c = px[i]
            (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
        }
        val edges = edges(blur(gray, w, h, 1.6), w, h)
        val near = dilate(edges, w, h)
        val lines = hough(edges, w, h)
        val runs = lines.mapNotNull { line -> supportedRun(near, w, h, line)?.let { line to it } }
        val reach = 0.04f * min(w, h)
        val corners = ArrayList<PointF>()
        for (i in runs.indices) for (j in i + 1 until runs.size) {
            val (la, a) = runs[i]
            val (lb, b) = runs[j]
            val angle = abs(Math.toDegrees(la.theta - lb.theta)) % 180
            if (angle < 45 || angle > 135) continue
            val p = intersect(la, lb) ?: continue
            if (p.x < -reach || p.y < -reach || p.x > w + reach || p.y > h + reach) continue
            if (segmentDistance(p, a.first, a.second) > reach || segmentDistance(p, b.first, b.second) > reach) continue
            corners += p
        }
        fun up(p: PointF) = PointF((p.x / scale).coerceIn(0f, src.width.toFloat()), (p.y / scale).coerceIn(0f, src.height.toFloat()))
        return Guides(runs.map { (_, s) -> up(s.first) to up(s.second) }, corners.map(::up))
    }

    /**
     * The longest stretch of [line] lying on edge pixels (gaps of up to 6 px bridged, at least 70 %
     * covered), or null when it is shorter than a tenth of the photo's short side.
     */
    private fun supportedRun(edge: BooleanArray, w: Int, h: Int, line: Line): Pair<PointF, PointF>? {
        val c = cos(line.theta).toFloat()
        val s = sin(line.theta).toFloat()
        val x0 = line.rho.toFloat() * c
        val y0 = line.rho.toFloat() * s
        val reach = hypot(w.toFloat(), h.toFloat())
        var bestStart = 0f; var bestEnd = 0f; var bestLen = 0f
        var start = Float.NaN; var last = Float.NaN; var hits = 0
        fun close() {
            // Samples are 1 px apart, so the run covers (last - start + 1) of them.
            if (!start.isNaN() && last - start > bestLen && hits >= 0.7f * (last - start + 1)) {
                bestStart = start; bestEnd = last; bestLen = last - start
            }
            start = Float.NaN; hits = 0
        }
        var t = -reach
        while (t <= reach) {
            val x = (x0 - s * t).roundToInt()
            val y = (y0 + c * t).roundToInt()
            if (x in 0 until w && y in 0 until h && edge[y * w + x]) {
                if (start.isNaN() || t - last > 6f) { close(); start = t }
                last = t
                hits++
            }
            t += 1f
        }
        close()
        if (bestLen < 0.1f * min(w, h)) return null
        return PointF(x0 - s * bestStart, y0 + c * bestStart) to PointF(x0 - s * bestEnd, y0 + c * bestEnd)
    }

    private fun segmentDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t))
    }

    /** Perspective-corrects the region inside [corners] (TL, TR, BR, BL) into an upright page. */
    fun warp(src: Bitmap, corners: List<PointF>): Bitmap {
        val (tl, tr, br, bl) = corners
        val outW = ((dist(tl, tr) + dist(bl, br)) / 2).roundToInt().coerceAtLeast(1)
        val outH = ((dist(tl, bl) + dist(tr, br)) / 2).roundToInt().coerceAtLeast(1)
        // Map the corners slightly outside the output so the outermost ~0.6 % (where the background
        // bleeds in at the detected edge) falls off the page.
        val m = EDGE_TRIM * max(outW, outH)
        val matrix = Matrix()
        matrix.setPolyToPoly(
            floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y), 0,
            floatArrayOf(-m, -m, outW + m, -m, outW + m, outH + m, -m, outH + m), 0,
            4,
        )
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Crops to the detected document, or returns [src] unchanged when detection is not confident. */
    fun autoCrop(src: Bitmap): Bitmap = detectOutline(src)?.let { warp(src, it) } ?: src

    /**
     * Flattens [outline]: a plain perspective warp for flat pages; for curled pages the perspective is
     * undone first and the curved sides are then straightened with a Coons patch (the bowed top and
     * bottom edges of an open book become straight lines, and the lines of text between them follow).
     */
    fun warp(src: Bitmap, outline: PageOutline): Bitmap {
        if (!outline.curved) return warp(src, outline.corners)
        val (tl, tr, br, bl) = outline.corners
        val baseW = (dist(tl, tr) + dist(bl, br)) / 2
        val baseH = (dist(tl, bl) + dist(tr, br)) / 2
        // toImage maps the flat rectangle (0..baseW, 0..baseH) onto the photo's quadrilateral.
        val toImage = Matrix().apply {
            setPolyToPoly(floatArrayOf(0f, 0f, baseW, 0f, baseW, baseH, 0f, baseH), 0, floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y), 0, 4)
        }
        val toFlat = Matrix().also { if (!toImage.invert(it)) return warp(src, outline.corners) }

        // The four sides, sampled and moved into the flat frame (top and left run from TL).
        val n = 33
        fun sideFlat(side: Int, reverse: Boolean): FloatArray {
            val pts = FloatArray(n * 2)
            for (i in 0 until n) {
                val t = i.toFloat() / (n - 1)
                val p = outline.sidePoint(side, if (reverse) 1 - t else t)
                pts[i * 2] = p.x; pts[i * 2 + 1] = p.y
            }
            toFlat.mapPoints(pts)
            return pts
        }
        val top = sideFlat(0, false)
        val right = sideFlat(1, false)
        val bottom = sideFlat(2, true)
        val left = sideFlat(3, true)
        fun length(c: FloatArray): Float {
            var l = 0f
            for (i in 1 until n) l += hypot(c[i * 2] - c[i * 2 - 2], c[i * 2 + 1] - c[i * 2 - 1])
            return l
        }
        var outW = (length(top) + length(bottom)) / 2
        var outH = (length(left) + length(right)) / 2
        val k = min(1f, BitmapUtils.MAX_PAGE_DIMENSION / max(outW, outH))
        outW *= k; outH *= k
        val w = max(8, outW.roundToInt())
        val h = max(8, outH.roundToInt())

        fun at(c: FloatArray, t: Float, axis: Int): Float {
            val x = t * (n - 1)
            val i = x.toInt().coerceIn(0, n - 2)
            val f = x - i
            return c[i * 2 + axis] * (1 - f) + c[(i + 1) * 2 + axis] * f
        }
        val e = EDGE_TRIM
        val us = FloatArray(w) { e + (1 - 2 * e) * it / (w - 1f) }
        val vs = FloatArray(h) { e + (1 - 2 * e) * it / (h - 1f) }
        val topX = FloatArray(w) { at(top, us[it], 0) }; val topY = FloatArray(w) { at(top, us[it], 1) }
        val botX = FloatArray(w) { at(bottom, us[it], 0) }; val botY = FloatArray(w) { at(bottom, us[it], 1) }
        val leftX = FloatArray(h) { at(left, vs[it], 0) }; val leftY = FloatArray(h) { at(left, vs[it], 1) }
        val rightX = FloatArray(h) { at(right, vs[it], 0) }; val rightY = FloatArray(h) { at(right, vs[it], 1) }
        val c00x = top[0]; val c00y = top[1]
        val c10x = top[(n - 1) * 2]; val c10y = top[(n - 1) * 2 + 1]
        val c01x = bottom[0]; val c01y = bottom[1]
        val c11x = bottom[(n - 1) * 2]; val c11y = bottom[(n - 1) * 2 + 1]

        val m = FloatArray(9).also { toImage.getValues(it) }
        val sw = src.width
        val sh = src.height
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val v = vs[y]
            for (x in 0 until w) {
                val u = us[x]
                // Coons patch in the flat frame
                val fx = (1 - v) * topX[x] + v * botX[x] + (1 - u) * leftX[y] + u * rightX[y] -
                    ((1 - u) * (1 - v) * c00x + u * (1 - v) * c10x + (1 - u) * v * c01x + u * v * c11x)
                val fy = (1 - v) * topY[x] + v * botY[x] + (1 - u) * leftY[y] + u * rightY[y] -
                    ((1 - u) * (1 - v) * c00y + u * (1 - v) * c10y + (1 - u) * v * c01y + u * v * c11y)
                // back into the photo
                val d = m[6] * fx + m[7] * fy + m[8]
                val ix = (m[0] * fx + m[1] * fy + m[2]) / d
                val iy = (m[3] * fx + m[4] * fy + m[5]) / d
                out[y * w + x] = sample(srcPx, sw, sh, ix, iy)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Bilinear sample (white outside the photo). */
    private fun sample(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        if (x < 0f || y < 0f || x > w - 1f || y > h - 1f) return Color.WHITE
        val x0 = x.toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = y.toInt().coerceAtMost(h - 2).coerceAtLeast(0)
        val fx = x - x0
        val fy = y - y0
        val a = px[y0 * w + x0]; val b = px[y0 * w + x0 + 1]
        val c = px[(y0 + 1) * w + x0]; val d = px[(y0 + 1) * w + x0 + 1]
        fun ch(shift: Int): Int {
            val top = ((a shr shift) and 0xFF) * (1 - fx) + ((b shr shift) and 0xFF) * fx
            val bot = ((c shr shift) and 0xFF) * (1 - fx) + ((d shr shift) and 0xFF) * fx
            return (top * (1 - fy) + bot * fy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- edges -----------------------------------------------------------------------------------

    private fun blur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val radius = (sigma * 3).roundToInt()
        val kernel = FloatArray(2 * radius + 1) { i -> exp(-((i - radius) * (i - radius)) / (2 * sigma * sigma)).toFloat() }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val acrossRows = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) acrossRows[y * w + x] = weighted(src, w, h, x, y, kernel, alongX = true)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = weighted(acrossRows, w, h, x, y, kernel, alongX = false)
        return out
    }

    /** The [kernel]-weighted sum of the pixels around (x, y) along one axis; the border pixel repeats outside. */
    private fun weighted(src: FloatArray, w: Int, h: Int, x: Int, y: Int, kernel: FloatArray, alongX: Boolean): Float {
        val radius = kernel.size / 2
        var acc = 0f
        for (k in -radius..radius) {
            val i = if (alongX) y * w + (x + k).coerceIn(0, w - 1) else (y + k).coerceIn(0, h - 1) * w + x
            acc += src[i] * kernel[k + radius]
        }
        return acc
    }

    /** Canny-style edge map: Sobel, non-maximum suppression, hysteresis (85th percentile high threshold). */
    private fun edges(g: FloatArray, w: Int, h: Int): BooleanArray {
        val mag = FloatArray(w * h)
        val dir = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
            val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
            mag[i] = hypot(gx, gy)
            val angle = (Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())) + 180) % 180
            dir[i] = (((angle / 45).roundToInt()) % 4)
        }
        val nms = FloatArray(w * h)
        val offsets = arrayOf(intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, 0), intArrayOf(1, -1))
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val (dy, dx) = offsets[dir[i]].let { it[0] to it[1] }
            val a = mag[(y + dy) * w + (x + dx)]
            val b = mag[(y - dy) * w + (x - dx)]
            if (mag[i] >= a && mag[i] >= b) nms[i] = mag[i]
        }
        val nonZero = nms.filter { it > 0f }
        if (nonZero.isEmpty()) return BooleanArray(w * h)
        val sorted = nonZero.sorted()
        val hi = sorted[(sorted.size * 0.85).toInt().coerceAtMost(sorted.size - 1)]
        val lo = hi * 0.4f
        var out = BooleanArray(w * h) { nms[it] >= hi }
        val weak = BooleanArray(w * h) { nms[it] >= lo }
        repeat(8) {
            val grown = dilate(out, w, h)
            var changed = false
            for (i in grown.indices) {
                val v = grown[i] && weak[i]
                if (v != out[i]) changed = true
                grown[i] = v
            }
            out = grown
            if (!changed) return@repeat
        }
        // ignore the 2 px frame (resampling artefacts)
        for (y in 0 until h) for (x in 0 until w) if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) out[y * w + x] = false
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (!src[y * w + x]) continue
            if (x > 0) out[y * w + x - 1] = true
            if (x < w - 1) out[y * w + x + 1] = true
            if (y > 0) out[(y - 1) * w + x] = true
            if (y < h - 1) out[(y + 1) * w + x] = true
        }
        return out
    }

    // ---- lines -----------------------------------------------------------------------------------

    private fun hough(edge: BooleanArray, w: Int, h: Int): List<Line> {
        val diag = hypot(w.toDouble(), h.toDouble()).toInt() + 1
        val rows = 2 * diag
        val acc = IntArray(rows * THETAS)
        for (y in 0 until h) for (x in 0 until w) {
            if (edge[y * w + x]) voteForLinesThrough(x, y, acc, diag)
        }
        val minVotes = max(20, (0.12 * min(w, h)).toInt())
        val lines = ArrayList<Line>()
        repeat(MAX_LINES) {
            val strongest = indexOfMax(acc)
            if (acc[strongest] < minVotes) return lines
            val r = strongest / THETAS
            val t = strongest % THETAS
            lines += Line((r - diag).toDouble(), t * PI / THETAS)
            clearVotesNear(r, t, acc, rows)
        }
        return lines
    }

    /** An edge pixel votes for every line (distance, angle) that passes through it. */
    private fun voteForLinesThrough(x: Int, y: Int, acc: IntArray, diag: Int) {
        for (t in 0 until THETAS) {
            val r = (x * cosT[t] + y * sinT[t]).roundToInt() + diag
            acc[r * THETAS + t]++
        }
    }

    /** Index of the largest value (the first one when several are equal). */
    private fun indexOfMax(values: IntArray): Int {
        var best = 0
        for (i in values.indices) if (values[i] > values[best]) best = i
        return best
    }

    /** Removes the votes around a found line, so its near-duplicates are not found again. */
    private fun clearVotesNear(r: Int, t: Int, acc: IntArray, rows: Int) {
        for (rr in max(0, r - 10)..min(rows - 1, r + 10)) {
            for (dt in -6..6) acc[rr * THETAS + Math.floorMod(t + dt, THETAS)] = 0
        }
    }

    private fun intersect(a: Line, b: Line): PointF? {
        val a1 = cos(a.theta); val b1 = sin(a.theta)
        val a2 = cos(b.theta); val b2 = sin(b.theta)
        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-6) return null
        val x = (a.rho * b2 - b.rho * b1) / det
        val y = (a1 * b.rho - a2 * a.rho) / det
        return PointF(x.toFloat(), y.toFloat())
    }

    // ---- quad geometry ---------------------------------------------------------------------------

    /**
     * Clockwise from the top-left corner, where "top" is the side closest to horizontal. Using the most
     * horizontal edge (rather than the smallest x + y) keeps strongly tilted documents the right way
     * up instead of turning them 90°.
     */
    private fun order(p: List<PointF>): List<PointF> {
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        val sorted = p.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        // In screen coordinates this order is clockwise; edge k runs sorted[k] -> sorted[k + 1].
        val start = (0 until 4).filter { k ->
            sorted[(k + 1) % 4].x > sorted[k].x // the top edge runs left to right
        }.minByOrNull { k ->
            val a = sorted[k]
            val b = sorted[(k + 1) % 4]
            val tilt = abs(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
            // prefer the most horizontal edge; between near-ties prefer the upper one
            tilt * 4 + (a.y + b.y) / (2.0 * max(1f, cy * 2))
        } ?: sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }

    private fun polygonArea(p: List<PointF>): Float {
        var a = 0f
        for (i in p.indices) {
            val j = (i + 1) % p.size
            a += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(a) / 2f
    }

    private fun cornerAngles(p: List<PointF>): List<Float> = List(4) { i ->
        val a = p[(i + 3) % 4]
        val b = p[i]
        val c = p[(i + 1) % 4]
        val v1x = a.x - b.x; val v1y = a.y - b.y
        val v2x = c.x - b.x; val v2y = c.y - b.y
        val cosv = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-6f)
        Math.toDegrees(acos(cosv.coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    private fun onBorder(a: PointF, b: PointF, w: Int, h: Int, tol: Float = 4f): Boolean =
        (abs(a.x) <= tol && abs(b.x) <= tol) || (abs(a.x - (w - 1)) <= tol && abs(b.x - (w - 1)) <= tol) ||
            (abs(a.y) <= tol && abs(b.y) <= tol) || (abs(a.y - (h - 1)) <= tol && abs(b.y - (h - 1)) <= tol)

    /** Share of points along the side that land on (dilated) edges. */
    private fun sideSupport(edge: BooleanArray, w: Int, h: Int, a: PointF, b: PointF): Float {
        val n = max(8, (dist(a, b) / 2).toInt())
        var hit = 0
        for (k in 0..n) {
            val x = (a.x + (b.x - a.x) * k / n).roundToInt()
            val y = (a.y + (b.y - a.y) * k / n).roundToInt()
            if (x in 0 until w && y in 0 until h && edge[y * w + x]) hit++
        }
        return hit.toFloat() / (n + 1)
    }

    /**
     * Share of points along the side where brightness differs across it (4 px in vs out) with the
     * dominant sign. Page borders score high; a line of text inside a page does not. Null when the side
     * lies on the photo border.
     */
    private fun sideContrast(g: FloatArray, w: Int, h: Int, a: PointF, b: PointF, cx: Float, cy: Float, off: Float = 4f): Float? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy).takeIf { it > 0f } ?: 1f
        var nx = -dy / len
        var ny = dx / len
        val mx = (a.x + b.x) / 2
        val my = (a.y + b.y) / 2
        if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny }
        val n = max(8, (len / 3).toInt())
        var pos = 0
        var neg = 0
        var count = 0
        for (k in 1 until n) {
            val x = a.x + dx * k / n
            val y = a.y + dy * k / n
            val xi = (x + nx * off).roundToInt(); val yi = (y + ny * off).roundToInt()
            val xo = (x - nx * off).roundToInt(); val yo = (y - ny * off).roundToInt()
            if (xi in 0 until w && yi in 0 until h && xo in 0 until w && yo in 0 until h) {
                val d = g[yi * w + xi] - g[yo * w + xo]
                if (d > 10f) pos++ else if (d < -10f) neg++
                count++
            }
        }
        if (count < 4) return null
        return max(pos, neg).toFloat() / count
    }

    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
}
