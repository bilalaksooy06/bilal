package com.bilal.meowbot.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotTest {

    @Test
    fun solverSolvesRandomPuzzles() {
        for (n in 5..12) for (seed in 0 until 5) {
            val regions = Synthetic.randomPuzzle(n, seed)
            val t = System.nanoTime()
            val cats = Solver.solve(regions)
            assertTrue((System.nanoTime() - t) / 1e9 < 2.0, "n=$n çok yavaş")
            assertNotNull(cats, "n=$n seed=$seed çözülemedi")
            assertTrue(Solver.isValid(regions, cats), "n=$n seed=$seed geçersiz çözüm")
        }
    }

    @Test
    fun solverDetectsUnsolvable() {
        val regions = arrayOf(
            intArrayOf(0, 0, 1, 1),
            intArrayOf(2, 2, 2, 2),
            intArrayOf(3, 3, 3, 3),
            intArrayOf(3, 3, 3, 3),
        )
        assertNull(Solver.solve(regions))
    }

    @Test
    fun visionReadsDefaultStyle() {
        for (n in 5..12) for (seed in 0 until 3) {
            val regions = Synthetic.randomPuzzle(n, seed + 100)
            val (img, box) = Synthetic.render(regions)
            val board = Vision.readBoard(img)
            assertEquals(n, board.size, "n=$n seed=$seed boyut")
            assertTrue(abs(board.box.x1 - box.x1) <= 12 && abs(board.box.y2 - box.y2) <= 12, "kutu: ${board.box} != $box")
            assertTrue(Synthetic.samePartition(board.regions, regions), "n=$n seed=$seed bölgeler")
        }
    }

    @Test
    fun visionReadsOtherStyles() {
        val styles = listOf(
            Synthetic.Style(line = 0, border = 6),
            Synthetic.Style(width = 720, height = 1600, top = 400, margin = 30, line = 2, border = 4),
            // S24 Ultra çözünürlüğü (QHD+)
            Synthetic.Style(width = 1440, height = 3120, top = 900, margin = 80, line = 1, border = 10),
        )
        for (style in styles) for (n in listOf(6, 8, 10)) {
            val regions = Synthetic.randomPuzzle(n, 42 + n)
            val (img, _) = Synthetic.render(regions, style)
            val board = Vision.readBoard(img)
            assertEquals(n, board.size, "$style n=$n boyut")
            assertTrue(Synthetic.samePartition(board.regions, regions), "$style n=$n bölgeler")
            assertTrue(Solver.isValid(regions, Solver.solve(board.regions)!!))
        }
    }
}
