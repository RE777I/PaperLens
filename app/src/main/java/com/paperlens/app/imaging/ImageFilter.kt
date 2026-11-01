package com.paperlens.app.imaging

import com.paperlens.app.S
import com.paperlens.app.R

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Enhancement modes offered after scanning, in the usual scanner app order. Names are stored with each page, so
 * entries are never renamed (MAGIC_COLOR is shown as "Magic Pro").
 */
enum class ImageFilter(private val labelRes: Int) {
    ORIGINAL(R.string.original),
    LIGHTEN(R.string.lighten),
    ENHANCE(R.string.enhance),
    MAGIC_COLOR(R.string.magic_pro),
    NO_SHADOW(R.string.no_shadow),
    BLACK_WHITE(R.string.b_w),
    ECO(R.string.eco),
    GRAYSCALE(R.string.grayscale),
    INVERT(R.string.invert),
    ;
    val label: String get() = S.str(labelRes)


    companion object {
        fun fromName(name: String): ImageFilter = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/**
 * The filters, fitted to a scanner app's results on 17 sample photos (see [FilterCurves]):
 *
 * - Lighten: one tone curve, after a gain that brings the photo's white point up (dim photos get more).
 * - Enhance, Magic Pro, No Shadow: lighting-independent. The paper colour is estimated everywhere on
 *   the page (a coarse, text-free copy). Where that estimate is dark or strongly coloured it is not
 *   trusted as paper (a floor on its brightness, and its colour pulled towards grey), so dark objects,
 *   coloured packaging and signs keep their colour instead of washing out. Each pixel is divided by it,
 *   thin strokes are deepened, saturation is boosted, and per-channel curves give the final look.
 *   No Shadow leaves strongly coloured or dark photos untouched, like the scanner app.
 * - B&W, Eco: the same on brightness only, so the result is neutral grey / black and white.
 * - Grayscale and Invert are exact formulas.
 */
object ImageProcessor {

    fun render(original: Bitmap, filter: ImageFilter, rotation: Int): Bitmap =
        apply(BitmapUtils.rotate(original, rotation), filter)

    fun apply(src: Bitmap, filter: ImageFilter): Bitmap = when (filter) {
        ImageFilter.ORIGINAL -> src
        ImageFilter.LIGHTEN -> lighten(src)
        ImageFilter.ENHANCE -> paperLook(src, FilterCurves.ENHANCE)
        ImageFilter.MAGIC_COLOR -> paperLook(src, FilterCurves.MAGIC_PRO)
        ImageFilter.NO_SHADOW -> paperLook(src, FilterCurves.NO_SHADOW)
        ImageFilter.BLACK_WHITE -> paperLook(src, FilterCurves.BW)
        ImageFilter.ECO -> paperLook(src, FilterCurves.ECO)
        ImageFilter.GRAYSCALE -> perPixel(src) { r, g, b -> luma(r, g, b).let { rgb(it, it, it) } }
        ImageFilter.INVERT -> perPixel(src) { r, g, b -> rgb(255 - r, 255 - g, 255 - b) }
    }

    // ---- simple per-pixel filters -----------------------------------------------------------------

    private inline fun perPixel(src: Bitmap, f: (Int, Int, Int) -> Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = f((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- paper-relative filters -------------------------------------------------------------------

    /** Lighten: a gain from the photo's white point (green channel, 99.9th percentile), then one curve. */
    private fun lighten(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (c in px) hist[(c shr 8) and 0xFF]++
        var white = 255
        var above = 0
        while (white > 0 && above + hist[white] <= px.size / 1000) { above += hist[white]; white-- }
        val gain = (FilterCurves.LIGHTEN_WHITE / max(1, white)).coerceIn(0.94f, 1.4f)
        val lut = IntArray(256) { FilterCurves.LIGHTEN[min(255, (it * gain).toInt())] }
        for (i in px.indices) {
            val c = px[i]
            px[i] = rgb(lut[(c shr 16) and 0xFF], lut[(c shr 8) and 0xFF], lut[c and 0xFF])
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Enhance, Magic Pro, No Shadow, B&W and Eco (see the class comment): each pixel divided by the
     * paper colour there (made at least [Look.floor] bright and greyer where saturated), times the stroke
     * factor, saturation boosted, then through the [Look]'s curves.
     */
    private fun paperLook(src: Bitmap, look: Look): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (look.skipNonPaper && notPaper(px, w, h)) return src
        val paper = Paper(px, w, h, look.radius, look.blur, look.cells)
        val floor = max(look.floor, look.relFloor * paper.lumaPercentile(0.95f))
        val strokes = Strokes(px, w, h, look.strokeGamma, needed = look.strokeGamma != 0f || look.deepen != 1f)
        val k = 255f / FilterCurves.RATIO_MAX
        // Stroke deepening: brightness L < 200 -> 200 (L / 200)^e, e = 1 + (deepen - 1) x weight, the
        // weight going from 0 on flat areas to 1 where a pixel is half as bright as its surroundings.
        val logL = FloatArray(256) { ln(max(1, it) / 200f) }
        val cr = look.curves[0]
        val cg = look.curves.getOrElse(1) { cr }
        val cb = look.curves.getOrElse(2) { cr }
        val bg = FloatArray(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                paper.at(x, y, bg)
                var pl = max(1f, 0.299f * bg[0] + 0.587f * bg[1] + 0.114f * bg[2])
                if (pl < floor) {
                    val f = floor / pl
                    bg[0] *= f; bg[1] *= f; bg[2] *= f
                    pl = floor
                }
                if (look.neutral > 0f) {
                    val hi = max(bg[0], max(bg[1], bg[2]))
                    val lo = min(bg[0], min(bg[1], bg[2]))
                    val wt = (look.neutral * (hi - lo) / max(1f, hi)).coerceIn(0f, 1f)
                    bg[0] += (pl - bg[0]) * wt; bg[1] += (pl - bg[1]) * wt; bg[2] += (pl - bg[2]) * wt
                }
                val s = strokes.factor(i)
                var xr = ((c shr 16) and 0xFF) / max(1f, bg[0]) * s
                var xg = ((c shr 8) and 0xFF) / max(1f, bg[1]) * s
                var xb = (c and 0xFF) / max(1f, bg[2]) * s
                val xl = 0.299f * xr + 0.587f * xg + 0.114f * xb
                px[i] = if (look.luma) {
                    cr[index(xl, k)].let { rgb(it, it, it) }
                } else {
                    if (look.saturation != 1f) {
                        xr = xl + look.saturation * (xr - xl)
                        xg = xl + look.saturation * (xg - xl)
                        xb = xl + look.saturation * (xb - xl)
                    }
                    var r = cr[index(xr, k)]
                    var g = cg[index(xg, k)]
                    var b = cb[index(xb, k)]
                    if (look.deepen != 1f) {
                        val weight = ((1f - strokes.ratio(i)) / 0.5f).coerceIn(0f, 1f)
                        val l = (r * 299 + g * 587 + b * 114) / 1000
                        if (weight > 0f && l < 200) {
                            val f = exp(logL[l] * (look.deepen - 1f) * weight)
                            r = (r * f).toInt(); g = (g * f).toInt(); b = (b * f).toInt()
                        }
                    }
                    rgb(r, g, b)
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun index(ratio: Float, k: Float): Int = (ratio * k).toInt().coerceIn(0, 255)

    /**
     * True for photos the scanner app's No Shadow leaves alone: mostly strongly coloured (median saturation
     * above 0.3: packaging, coloured signs) or with no bright paper anywhere (95 % of the paper estimate
     * darker than 140).
     */
    private fun notPaper(px: IntArray, w: Int, h: Int): Boolean {
        val hist = IntArray(101)
        var n = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            hist[if (hi == 0) 0 else (100 * (hi - lo) / hi)]++
            n++
            i += 7
        }
        var acc = 0
        var median = 0
        while (median < 100 && acc + hist[median] <= n / 2) { acc += hist[median]; median++ }
        if (median > 30) return true
        return Paper(px, w, h, 4, 3, Paper.CELLS_ACROSS).lumaPercentile(0.95f) < 140f
    }

    /**
     * How much darker a pixel is than the brightest pixel within a few pixels of it (about 0.45 % of
     * the page's short side). Thin pencil strokes are much darker than their surroundings, flat
     * coloured areas are not, so raising this to [gamma] darkens strokes without greying the bands.
     */
    private class Strokes(px: IntArray, w: Int, h: Int, private val gamma: Float, needed: Boolean = gamma != 0f) {
        private val luma: ByteArray?
        private val localMax: ByteArray?

        init {
            if (!needed) {
                luma = null
                localMax = null
            } else {
                val l = ByteArray(w * h)
                for (i in px.indices) {
                    val c = px[i]
                    l[i] = ((((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000).toByte()
                }
                val radius = max(1, (2f * min(w, h) / 1117f).roundToInt())
                val tmp = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var m = 0
                        for (xx in max(0, x - radius)..min(w - 1, x + radius)) m = max(m, l[y * w + xx].toInt() and 0xFF)
                        tmp[y * w + x] = m.toByte()
                    }
                }
                val out = ByteArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var m = 0
                        for (yy in max(0, y - radius)..min(h - 1, y + radius)) m = max(m, tmp[yy * w + x].toInt() and 0xFF)
                        out[y * w + x] = m.toByte()
                    }
                }
                luma = l
                localMax = out
            }
        }

        /** Pixel brightness / local brightest (1 on flat areas, small on thin dark strokes). */
        fun ratio(i: Int): Float {
            val l = luma ?: return 1f
            val m = localMax!![i].toInt() and 0xFF
            return min(1f, (l[i].toInt() and 0xFF) / max(1f, m.toFloat()))
        }

        /** [ratio]^gamma; 1 for flat areas or when switched off. */
        fun factor(i: Int): Float {
            if (gamma == 0f) return 1f
            val f = ratio(i)
            return when (gamma) {
                1f -> f
                0.5f -> sqrt(f)
                1.5f -> f * sqrt(f)
                else -> f.toDouble().pow(gamma.toDouble()).toFloat()
            }
        }
    }


    /**
     * The paper's colour at every pixel. The page is shrunk to about [cells] cells across its short side
     * (block averages), a per-channel maximum over (2 × [radius] + 1) cells removes text, lines and
     * thin coloured bands, a box blur over (2 × [blur] + 1) cells smooths it, and [at] enlarges it
     * bilinearly.
     */
    private class Paper(px: IntArray, private val w: Int, private val h: Int, radius: Int, blur: Int, cells: Float = CELLS_ACROSS) {
        private val cell = max(1, (min(w, h) / cells).roundToInt())
        private val sw = max(1, w / cell)
        private val sh = max(1, h / cell)
        private val r = FloatArray(sw * sh)
        private val g = FloatArray(sw * sh)
        private val b = FloatArray(sw * sh)

        // Upsampling coordinates, worked out once per column / row.
        private val x0 = IntArray(w); private val x1 = IntArray(w); private val fx = FloatArray(w)
        private val y0 = IntArray(h); private val y1 = IntArray(h); private val fy = FloatArray(h)

        init {
            // Block averages
            val count = IntArray(sw * sh)
            for (y in 0 until h) {
                val cy = min(sh - 1, y * sh / h)
                for (x in 0 until w) {
                    val i = cy * sw + min(sw - 1, x * sw / w)
                    val c = px[y * w + x]
                    r[i] += ((c shr 16) and 0xFF).toFloat()
                    g[i] += ((c shr 8) and 0xFF).toFloat()
                    b[i] += (c and 0xFF).toFloat()
                    count[i]++
                }
            }
            for (i in count.indices) if (count[i] > 0) { r[i] /= count[i]; g[i] /= count[i]; b[i] /= count[i] }
            for (plane in arrayOf(r, g, b)) {
                maxFilter(plane, radius)
                boxBlur(plane, blur)
            }
            for (x in 0 until w) {
                val s = ((x + 0.5f) * sw / w - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                x0[x] = s.toInt(); x1[x] = min(sw - 1, x0[x] + 1); fx[x] = s - x0[x]
            }
            for (y in 0 until h) {
                val s = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh - 1).toFloat())
                y0[y] = s.toInt(); y1[y] = min(sh - 1, y0[y] + 1); fy[y] = s - y0[y]
            }
        }

        /** The brightness below which fraction [q] of the paper estimate lies. */
        fun lumaPercentile(q: Float): Float {
            val l = FloatArray(r.size) { 0.299f * r[it] + 0.587f * g[it] + 0.114f * b[it] }
            l.sort()
            return l[((l.size - 1) * q).roundToInt()]
        }

        fun at(x: Int, y: Int, out: FloatArray) {
            val a = y0[y] * sw + x0[x]
            val bIdx = y0[y] * sw + x1[x]
            val c = y1[y] * sw + x0[x]
            val d = y1[y] * sw + x1[x]
            val tx = fx[x]
            val ty = fy[y]
            out[0] = lerp2(r[a], r[bIdx], r[c], r[d], tx, ty)
            out[1] = lerp2(g[a], g[bIdx], g[c], g[d], tx, ty)
            out[2] = lerp2(b[a], b[bIdx], b[c], b[d], tx, ty)
        }

        private fun lerp2(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float): Float {
            val top = a + (b - a) * tx
            val bottom = c + (d - c) * tx
            return top + (bottom - top) * ty
        }

        /** Square maximum filter, done as a row pass then a column pass. */
        private fun maxFilter(p: FloatArray, radius: Int) {
            if (radius <= 0) return
            val tmp = FloatArray(p.size)
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 0f
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx in 0 until sw) m = max(m, p[y * sw + xx])
                }
                tmp[y * sw + x] = m
            }
            for (y in 0 until sh) for (x in 0 until sw) {
                var m = 0f
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy in 0 until sh) m = max(m, tmp[yy * sw + x])
                }
                p[y * sw + x] = m
            }
        }

        /** Square box blur (edges mirrored), row pass then column pass. */
        private fun boxBlur(p: FloatArray, radius: Int) {
            if (radius <= 0) return
            val n = 2 * radius + 1
            val tmp = FloatArray(p.size)
            fun mirror(i: Int, size: Int): Int = when {
                size == 1 -> 0
                i < 0 -> -i
                i >= size -> 2 * size - 2 - i
                else -> i
            }.coerceIn(0, size - 1)
            for (y in 0 until sh) for (x in 0 until sw) {
                var s = 0f
                for (dx in -radius..radius) s += p[y * sw + mirror(x + dx, sw)]
                tmp[y * sw + x] = s / n
            }
            for (y in 0 until sh) for (x in 0 until sw) {
                var s = 0f
                for (dy in -radius..radius) s += tmp[mirror(y + dy, sh) * sw + x]
                p[y * sw + x] = s / n
            }
        }

        companion object {
            /** Default cells across the page's short side. */
            const val CELLS_ACROSS = 70f
        }
    }

    private fun luma(r: Int, g: Int, b: Int): Int = ((r * 299 + g * 587 + b * 114 + 500) / 1000).coerceIn(0, 255)

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
