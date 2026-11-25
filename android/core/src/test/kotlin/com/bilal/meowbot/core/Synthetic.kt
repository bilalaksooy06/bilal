package com.bilal.meowbot.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.random.Random

/** Testler için rastgele, çözümü garantili Meowdoku tahtası + telefon ekranı görüntüsü. */
object Synthetic {
    private val PASTELS = listOf(
        0xFFB3BA, 0xFFDFBA, 0xFFFFBA, 0xBAFFC9, 0xBAE1FF, 0xD2BEFF, 0xFFBEF0, 0xBEF0F0,
        0xF0C896, 0xC8E696, 0xAAC8E6, 0xE6AAAA, 0x96DCBE, 0xFAD2E6, 0xC8C8FA, 0xEBEBA0,
    )

    private fun randomCats(n: Int, rnd: Random): IntArray {
        val out = IntArray(n)
        val used = BooleanArray(n)
        fun rec(r: Int): Boolean {
            if (r == n) return true
            for (c in (0 until n).shuffled(rnd)) {
                if (used[c] || (r > 0 && Math.abs(c - out[r - 1]) <= 1)) continue
                out[r] = c; used[c] = true
                if (rec(r + 1)) return true
                used[c] = false
            }
            return false
        }
        check(rec(0))
        return out
    }

    fun randomPuzzle(n: Int, seed: Int): Array<IntArray> {
        val rnd = Random(seed)
        val cats = randomCats(n, rnd)
        val regions = Array(n) { IntArray(n) { -1 } }
        val frontier = ArrayList<IntArray>()
        for (r in 0 until n) {
            regions[r][cats[r]] = r
            frontier.add(intArrayOf(r, cats[r]))
        }
        while (frontier.isNotEmpty()) {
            val idx = rnd.nextInt(frontier.size)
            val (r, c) = frontier[idx].let { it[0] to it[1] }
            val nbrs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                .map { (dr, dc) -> r + dr to c + dc }
                .filter { (rr, cc) -> rr in 0 until n && cc in 0 until n && regions[rr][cc] == -1 }
            if (nbrs.isEmpty()) {
                frontier.removeAt(idx); continue
            }
            val (rr, cc) = nbrs[rnd.nextInt(nbrs.size)]
            regions[rr][cc] = regions[r][c]
            frontier.add(intArrayOf(rr, cc))
        }
        return regions
    }

    data class Style(
        val width: Int = 1080, val height: Int = 2400, val top: Int = 650, val margin: Int = 60,
        val line: Int = 3, val border: Int = 8,
    )

    fun render(regions: Array<IntArray>, s: Style = Style()): Pair<PixelImage, Box> {
        val n = regions.size
        val img = BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(250, 245, 235); g.fillRect(0, 0, s.width, s.height)
        g.color = Color(255, 140, 90); g.fillRect(100, 120, s.width - 200, 140) // renkli başlık
        val size = s.width - 2 * s.margin
        val cell = size.toDouble() / n
        val x0 = s.margin
        val y0 = s.top
        fun px(k: Int) = (x0 + k * cell).toInt()
        fun py(k: Int) = (y0 + k * cell).toInt()
        for (r in 0 until n) for (c in 0 until n) {
            g.color = Color(PASTELS[regions[r][c] % PASTELS.size])
            g.fillRect(px(c), py(r), px(c + 1) - px(c), py(r + 1) - py(r))
        }
        if (s.line > 0) {
            g.color = Color(90, 80, 70); g.stroke = BasicStroke(s.line.toFloat())
            for (k in 0..n) {
                g.drawLine(px(k), y0, px(k), y0 + size)
                g.drawLine(x0, py(k), x0 + size, py(k))
            }
        }
        g.color = Color(40, 30, 30); g.stroke = BasicStroke(s.border.toFloat())
        for (r in 0 until n) for (c in 0 until n) {
            if (c + 1 < n && regions[r][c] != regions[r][c + 1]) g.drawLine(px(c + 1), py(r), px(c + 1), py(r + 1))
            if (r + 1 < n && regions[r][c] != regions[r + 1][c]) g.drawLine(px(c), py(r + 1), px(c + 1), py(r + 1))
        }
        g.drawRect(x0, y0, size, size)
        g.dispose()
        val pixels = img.getRGB(0, 0, s.width, s.height, null, 0, s.width)
        return PixelImage(s.width, s.height, pixels) to Box(x0, y0, x0 + size, y0 + size)
    }

    fun samePartition(a: Array<IntArray>, b: Array<IntArray>): Boolean {
        val map = HashMap<Int, Int>()
        for (r in a.indices) for (c in a.indices) {
            if (map.getOrPut(a[r][c]) { b[r][c] } != b[r][c]) return false
        }
        return map.values.toSet().size == map.size
    }
}
