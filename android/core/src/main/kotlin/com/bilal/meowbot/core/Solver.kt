package com.bilal.meowbot.core

/**
 * Meowdoku çözücü.
 *
 * Kurallar: her satırda, her sütunda ve her renkli bölgede tam 1 kedi;
 * kediler birbirine (çapraz dahil) değemez.
 *
 * [regions] NxN, `regions[r][c]` = bölge numarası (0..N-1).
 * Dönüş: kedilerin (satır, sütun) listesi ya da çözüm yoksa null.
 */
object Solver {

    data class Cell(val row: Int, val col: Int)

    fun solve(regions: Array<IntArray>): List<Cell>? {
        val n = regions.size
        require(n > 0 && regions.all { it.size == n }) { "Tahta NxN olmalı" }
        val ids = regions.flatMap { it.toList() }.toSortedSet()
        require(ids.size == n) { "${n}x$n tahtada $n bölge olmalı, ${ids.size} bulundu" }

        val cellsOf = HashMap<Int, MutableList<Cell>>()
        for (r in 0 until n) for (c in 0 until n) {
            cellsOf.getOrPut(regions[r][c]) { mutableListOf() }.add(Cell(r, c))
        }

        val blocked = Array(n) { IntArray(n) }
        val placed = ArrayList<Cell>()
        val remaining = ids.toMutableSet()
        val rowUsed = BooleanArray(n)
        val colUsed = BooleanArray(n)

        fun blockList(cell: Cell): List<Cell> {
            val out = ArrayList<Cell>(4 * n + 8)
            for (i in 0 until n) {
                out.add(Cell(cell.row, i))
                out.add(Cell(i, cell.col))
            }
            for (dr in -1..1) for (dc in -1..1) {
                val rr = cell.row + dr
                val cc = cell.col + dc
                if ((dr != 0 || dc != 0) && rr in 0 until n && cc in 0 until n) out.add(Cell(rr, cc))
            }
            out.addAll(cellsOf.getValue(regions[cell.row][cell.col]))
            return out
        }

        fun search(): Boolean {
            if (remaining.isEmpty()) return true

            // Kalan her satır ve sütunda hâlâ serbest hücre olmalı
            for (r in 0 until n) if (!rowUsed[r] && (0 until n).all { blocked[r][it] > 0 }) return false
            for (c in 0 until n) if (!colUsed[c] && (0 until n).all { blocked[it][c] > 0 }) return false

            // En az seçeneği olan bölge (MRV)
            var bestId = -1
            var bestOpts: List<Cell>? = null
            for (id in remaining) {
                val opts = cellsOf.getValue(id).filter { blocked[it.row][it.col] == 0 }
                if (opts.isEmpty()) return false
                if (bestOpts == null || opts.size < bestOpts.size) {
                    bestId = id
                    bestOpts = opts
                    if (opts.size == 1) break
                }
            }

            remaining.remove(bestId)
            for (cell in bestOpts!!) {
                val bl = blockList(cell)
                for (b in bl) blocked[b.row][b.col]++
                placed.add(cell)
                rowUsed[cell.row] = true
                colUsed[cell.col] = true
                if (search()) return true
                rowUsed[cell.row] = false
                colUsed[cell.col] = false
                placed.removeAt(placed.size - 1)
                for (b in bl) blocked[b.row][b.col]--
            }
            remaining.add(bestId)
            return false
        }

        return if (search()) placed.sortedWith(compareBy({ it.row }, { it.col })) else null
    }

    fun isValid(regions: Array<IntArray>, cats: List<Cell>): Boolean {
        val n = regions.size
        if (cats.size != n) return false
        if (cats.map { it.row }.toSet().size != n) return false
        if (cats.map { it.col }.toSet().size != n) return false
        if (cats.map { regions[it.row][it.col] }.toSet().size != n) return false
        for (i in cats.indices) for (j in i + 1 until cats.size) {
            if (Math.abs(cats[i].row - cats[j].row) <= 1 && Math.abs(cats[i].col - cats[j].col) <= 1) return false
        }
        return true
    }
}
