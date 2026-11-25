package com.bilal.meowbot.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Platformdan bağımsız görüntü: ARGB pikseller (Android Bitmap.getPixels ile aynı düzen). */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height)
    }

    fun r(x: Int, y: Int) = (pixels[y * width + x] shr 16) and 0xFF
    fun g(x: Int, y: Int) = (pixels[y * width + x] shr 8) and 0xFF
    fun b(x: Int, y: Int) = pixels[y * width + x] and 0xFF

    fun sat(x: Int, y: Int): Int {
        val p = pixels[y * width + x]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    fun diff(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val p = pixels[y1 * width + x1]
        val q = pixels[y2 * width + x2]
        return abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
            abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
            abs((p and 0xFF) - (q and 0xFF))
    }
}

/** Tahta sınırı; x2/y2 hariç. */
data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    val width get() = x2 - x1
    val height get() = y2 - y1
}

/** [colors]: hücre başına [r, g, b], satır satır (N*N eleman). */
class Board(val box: Box, val size: Int, val regions: Array<IntArray>, val colors: Array<IntArray>) {
    /** Hücre merkezi, görüntü pikseli cinsinden. */
    fun cellCenter(row: Int, col: Int): Pair<Float, Float> {
        val cw = box.width.toFloat() / size
        val ch = box.height.toFloat() / size
        return Pair(box.x1 + (col + 0.5f) * cw, box.y1 + (row + 0.5f) * ch)
    }

    fun sameRegionsAs(other: Board?): Boolean =
        other != null && other.size == size && regions.indices.all { regions[it].contentEquals(other.regions[it]) }
}

class VisionException(message: String) : Exception(message)

object Vision {

    // ------------------------------------------------------------ 1. tahta sınırı

    private fun fillGaps(mask: BooleanArray, maxGap: Int): BooleanArray {
        val out = mask.copyOf()
        var last = -1
        for (i in mask.indices) {
            if (mask[i]) {
                if (last >= 0 && i - last in 2..maxGap + 1) for (k in last until i) out[k] = true
                last = i
            }
        }
        return out
    }

    /** En uzun ardışık true dizisi: [start, end) */
    private fun longestRun(maskIn: BooleanArray, maxGap: Int): IntArray {
        val mask = if (maxGap > 0) fillGaps(maskIn, maxGap) else maskIn
        var best = intArrayOf(0, 0)
        var start = -1
        for (i in 0..mask.size) {
            val v = i < mask.size && mask[i]
            if (v && start < 0) start = i
            else if (!v && start >= 0) {
                if (i - start > best[1] - best[0]) best = intArrayOf(start, i)
                start = -1
            }
        }
        return best
    }

    fun findGridBox(img: PixelImage, satThreshold: Int = 22): Box {
        val w = img.width
        val h = img.height
        val gap = max(2, (min(w, h) * 0.025).toInt())

        val rowFrac = BooleanArray(h) { y ->
            var cnt = 0
            for (x in 0 until w) if (img.sat(x, y) > satThreshold) cnt++
            cnt > w * 0.35
        }
        val (y1r, y2r) = longestRun(rowFrac, gap).let { it[0] to it[1] }
        if (y2r - y1r < h * 0.15) throw VisionException("Tahta bulunamadı (renkli alan yok)")

        val colMask = BooleanArray(w) { x ->
            var cnt = 0
            for (y in y1r until y2r) if (img.sat(x, y) > satThreshold) cnt++
            cnt > (y2r - y1r) * 0.35
        }
        var (x1, x2) = longestRun(colMask, gap).let { it[0] to it[1] }
        if (x2 - x1 < w * 0.3) throw VisionException("Tahta bulunamadı (genişlik çok küçük)")
        var y1 = y1r
        var y2 = y2r

        val bw = x2 - x1
        val bh = y2 - y1
        if (bh > bw * 1.08) {
            // Üstte/altta renkli başka şeyler var: en dolu kare pencereyi seç
            var bestFrac = -1.0
            var bestStart = y1
            val step = max(1, bw / 50)
            var s = y1
            while (s <= y2 - bw) {
                var cnt = 0L
                var tot = 0L
                var yy = s
                while (yy < s + bw) {
                    var xx = x1
                    while (xx < x2) {
                        if (img.sat(xx, yy) > satThreshold) cnt++
                        tot++
                        xx += 2
                    }
                    yy += 2
                }
                val frac = cnt.toDouble() / tot
                if (frac > bestFrac) {
                    bestFrac = frac
                    bestStart = s
                }
                s += step
            }
            y1 = bestStart
            y2 = bestStart + bw
        } else if (bw > bh * 1.08) {
            val cx = (x1 + x2) / 2
            x1 = cx - bh / 2
            x2 = x1 + bh
        }
        return refineBox(img, Box(x1, y1, x2, y2))
    }

    /** Renkli alanı, tahtanın dış çerçeve çizgisine kadar genişletir. */
    private fun refineBox(img: PixelImage, box: Box): Box {
        val m = max(3, (box.width * 0.03).toInt())
        val ex1 = max(0, box.x1 - m)
        val ex2 = min(img.width, box.x2 + m)
        val ey1 = max(0, box.y1 - m)
        val ey2 = min(img.height, box.y2 + m)
        val px = edgeProfile(img, Box(ex1, box.y1, ex2, box.y2), horizontal = true)
        val py = edgeProfile(img, Box(box.x1, ey1, box.x2, ey2), horizontal = false)

        fun outer(profile: DoubleArray, start: Int, end: Int, fromLeft: Boolean): Int? {
            val s = max(0, start)
            val e = min(profile.size, end)
            if (e <= s) return null
            var mx = 0.0
            for (i in s until e) mx = max(mx, profile[i])
            if (mx <= 0) return null
            return if (fromLeft) (s until e).first { profile[it] > mx * 0.5 }
            else (s until e).last { profile[it] > mx * 0.5 } + 1
        }

        val lx = box.x1 - ex1
        val hx = box.x2 - ex1
        val ly = box.y1 - ey1
        val hy = box.y2 - ey1
        return Box(
            ex1 + (outer(px, lx - m, lx + m, true) ?: lx),
            ey1 + (outer(py, ly - m, ly + m, true) ?: ly),
            ex1 + (outer(px, hx - m, hx + m, false) ?: hx),
            ey1 + (outer(py, hy - m, hy + m, false) ?: hy),
        )
    }

    // ------------------------------------------------------------ 2. N tespiti

    /** Komşu pikseller arası renk farkının ortalaması (horizontal: x boyunca). */
    fun edgeProfile(img: PixelImage, box: Box, horizontal: Boolean): DoubleArray {
        return if (horizontal) {
            DoubleArray(max(0, box.width - 1)) { i ->
                val x = box.x1 + i
                var s = 0L
                for (y in box.y1 until box.y2) s += img.diff(x, y, x + 1, y)
                s.toDouble() / box.height
            }
        } else {
            DoubleArray(max(0, box.height - 1)) { i ->
                val y = box.y1 + i
                var s = 0L
                for (x in box.x1 until box.x2) s += img.diff(x, y, x, y + 1)
                s.toDouble() / box.width
            }
        }
    }

    private fun scoreN(profile: DoubleArray, n: Int): Double {
        val length = profile.size + 1
        val step = length.toDouble() / n
        val win = max(1, (step * 0.12).toInt())
        fun peak(pos: Double): Double {
            val p = pos.roundToInt()
            val lo = max(0, p - win - 1)
            val hi = min(profile.size, p + win + 1)
            var m = 0.0
            for (i in lo until hi) m = max(m, profile[i])
            return m
        }
        val lines = (1 until n).map { peak(it * step) }.average()
        val mids = (0 until n).map { peak((it + 0.5) * step) }.average()
        return lines - mids
    }

    fun detectSize(img: PixelImage, box: Box, nMin: Int = 4, nMax: Int = 16): Int {
        val px = edgeProfile(img, box, horizontal = true)
        val py = edgeProfile(img, box, horizontal = false)
        val scores = (nMin..nMax).associateWith { scoreN(px, it) + scoreN(py, it) }
        val best = scores.values.max()
        // 12x12 tahtada 4 ve 6 da benzer puan alır; en iyiye yakın olanlardan en büyüğünü seç
        return scores.filter { it.value >= best * 0.85 }.keys.max()
    }

    // ------------------------------------------------------------ 3. bölgeler

    fun sampleCellColors(img: PixelImage, box: Box, n: Int, frac: Double = 0.3): Array<IntArray> {
        val cw = box.width.toDouble() / n
        val ch = box.height.toDouble() / n
        return Array(n * n) { idx ->
            val r = idx / n
            val c = idx % n
            val cx = box.x1 + (c + 0.5) * cw
            val cy = box.y1 + (r + 0.5) * ch
            val hw = max(1.0, cw * frac / 2)
            val hh = max(1.0, ch * frac / 2)
            val rs = ArrayList<Int>()
            val gs = ArrayList<Int>()
            val bs = ArrayList<Int>()
            for (y in (cy - hh).toInt()..(cy + hh).toInt()) {
                for (x in (cx - hw).toInt()..(cx + hw).toInt()) {
                    if (x !in 0 until img.width || y !in 0 until img.height) continue
                    rs.add(img.r(x, y)); gs.add(img.g(x, y)); bs.add(img.b(x, y))
                }
            }
            intArrayOf(median(rs), median(gs), median(bs))
        }
    }

    private fun median(v: MutableList<Int>): Int {
        if (v.isEmpty()) return 0
        v.sort()
        return v[v.size / 2]
    }

    /** Renkleri tam k gruba ayırır (ortalama bağlantılı hiyerarşik kümeleme). */
    fun clusterColors(colors: Array<IntArray>, k: Int, n: Int): Array<IntArray> {
        val m = colors.size
        val cd = Array(m) { i ->
            DoubleArray(m) { j ->
                if (i == j) Double.POSITIVE_INFINITY else {
                    val dr = (colors[i][0] - colors[j][0]).toDouble()
                    val dg = (colors[i][1] - colors[j][1]).toDouble()
                    val db = (colors[i][2] - colors[j][2]).toDouble()
                    sqrt(dr * dr + dg * dg + db * db)
                }
            }
        }
        val members = HashMap<Int, MutableList<Int>>()
        for (i in 0 until m) members[i] = mutableListOf(i)
        val active = (0 until m).toMutableList()

        while (active.size > k) {
            var ba = -1
            var bb = -1
            var bd = Double.POSITIVE_INFINITY
            for (ai in active.indices) for (bi in ai + 1 until active.size) {
                val d = cd[active[ai]][active[bi]]
                if (d < bd) {
                    bd = d; ba = active[ai]; bb = active[bi]
                }
            }
            val na = members.getValue(ba).size
            val nb = members.getValue(bb).size
            for (j in 0 until m) {
                val v = (cd[ba][j] * na + cd[bb][j] * nb) / (na + nb)
                cd[ba][j] = v
                cd[j][ba] = v
            }
            cd[ba][ba] = Double.POSITIVE_INFINITY
            for (j in 0 until m) {
                cd[bb][j] = Double.POSITIVE_INFINITY
                cd[j][bb] = Double.POSITIVE_INFINITY
            }
            members.getValue(ba).addAll(members.remove(bb)!!)
            active.remove(bb)
        }

        val labels = IntArray(m)
        members.values.sortedBy { it.min() }.forEachIndexed { id, list -> list.forEach { labels[it] = id } }
        return Array(n) { r -> IntArray(n) { c -> labels[r * n + c] } }
    }

    // ------------------------------------------------------------ hepsi bir arada

    fun readBoard(img: PixelImage, box: Box? = null, size: Int? = null): Board {
        val b = box ?: findGridBox(img)
        val n = size ?: detectSize(img, b)
        val colors = sampleCellColors(img, b, n)
        val regions = clusterColors(colors, n, n)
        return Board(b, n, regions, colors)
    }
}
