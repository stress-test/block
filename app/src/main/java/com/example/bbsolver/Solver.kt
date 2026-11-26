package com.example.bbsolver

class Move(val pi: Int, val r: Int, val c: Int, val cells: List<IntArray>, val lines: Int,
           val pre: IntArray, val after: IntArray, val rowsFull: Int, val colsFull: Int)
class Solution(val moves: List<Move>, val complete: Boolean, val total: Int)

/**
 * 1) Beam search: thử mọi thứ tự + vị trí của 3 mảnh hiện tại.
 * 2) "AI": với các phương án tốt nhất, mô phỏng hàng trăm bộ 3 mảnh NGẪU NHIÊN của lượt sau, xem bảng còn xoay xở
 *    được không (Monte Carlo lookahead + successive halving), tận dụng toàn bộ thời gian suy nghĩ cho phép.
 */
object Solver {
    private class St(val rows: IntArray, val used: Int, val moves: List<Move>, val lines: Int, var score: Double)
    private class NS(val rows: IntArray, val used: Int, val lines: Int, val score: Double)
    private class Pl(val pre: IntArray, val after: IntArray, val rf: Int, val cf: Int, val nl: Int)
    private class Lib(val m: IntArray, val w: Int, val wt: Float)

    private fun rot(g: List<String>): List<String> { val h = g.size; val w = g[0].length; return List(w) { c -> String(CharArray(h) { r -> g[h - 1 - r][c] }) } }
    private fun masks(g: List<String>) = IntArray(g.size) { r -> g[r].foldIndexed(0) { i, a, ch -> if (ch == '1') a or (1 shl i) else a } }
    private fun width(m: IntArray) = 32 - Integer.numberOfLeadingZeros(m.fold(0) { a, b -> a or b })

    /** Mọi hình có thể xuất hiện trong game: xoay 4 hướng + lật gương. */
    private val ALL: List<Pair<List<String>, Float>> by lazy {
        val base = listOf(
            listOf("1") to 0.5f, listOf("11") to 1f, listOf("111") to 2f, listOf("1111") to 3f, listOf("11111") to 4f,
            listOf("11", "11") to 2f, listOf("111", "111", "111") to 4f, listOf("111", "111") to 3f,
            listOf("10", "11") to 2f, listOf("100", "100", "111") to 4f, listOf("100", "111") to 3f,
            listOf("111", "010") to 3f, listOf("011", "110") to 3f)
        val seen = HashSet<String>(); val out = ArrayList<Pair<List<String>, Float>>()
        for ((s, wt) in base) for (g0 in listOf(s, s.map { it.reversed() })) {
            var g = g0
            repeat(4) { if (seen.add(g.joinToString("/"))) out.add(g to wt); g = rot(g) }
        }
        out
    }
    val SHAPES: List<List<IntArray>> by lazy { ALL.map { (g, _) -> g.flatMapIndexed { r, row -> row.mapIndexedNotNull { c, ch -> if (ch == '1') intArrayOf(r, c) else null } } } }
    private val LIB: List<Lib> by lazy { ALL.map { (g, wt) -> val m = masks(g); Lib(m, width(m), wt) } }

    /** Nếu hình đọc được không hợp lệ nhưng lệch đúng 1 ô so với một hình duy nhất trong game thì sửa lại. */
    fun snap(cells: List<IntArray>): List<IntArray> {
        if (cells.isEmpty()) return cells
        fun key(l: List<IntArray>) = l.map { it[0] * 8 + it[1] }.sorted()
        val k = key(cells)
        if (SHAPES.any { key(it) == k }) return cells
        val h = cells.maxOf { it[0] } + 1; val w = cells.maxOf { it[1] } + 1
        val set = k.toSet(); var best: List<IntArray>? = null; var bd = 2; var uniq = true
        for (s in SHAPES) {
            if (s.maxOf { it[0] } + 1 != h || s.maxOf { it[1] } + 1 != w) continue
            val ks = key(s).toSet(); val d = (ks - set).size + (set - ks).size
            if (d < bd) { bd = d; best = s; uniq = true } else if (d == bd && d < 2 && best != null) uniq = false
        }
        return if (best != null && uniq && bd <= 1) best else cells
    }

    private fun toMasks(cells: List<IntArray>): IntArray {
        val m = IntArray(cells.maxOf { it[0] } + 1)
        for (p in cells) m[p[0]] = m[p[0]] or (1 shl p[1])
        return m
    }

    private fun cheap(rows: IntArray, lines: Int): Double {
        fun g(r: Int, c: Int) = if (r < 0 || c < 0 || r >= 8 || c >= 8) 1 else (rows[r] shr c) and 1
        var empty = 0; var holes = 0; var tr = 0
        for (r in 0 until 8) for (c in 0 until 8) {
            if (g(r, c) == 0) { empty++; if (g(r - 1, c) + g(r + 1, c) + g(r, c - 1) + g(r, c + 1) == 4) holes++ }
            if (g(r, c) != g(r, c + 1)) tr++
            if (g(r, c) != g(r + 1, c)) tr++
        }
        return lines * lines * 10.0 + empty * 0.8 - holes * 6 - tr * 0.5 + (if (empty == 64) 80 else 0)
    }

    private fun count(rows: IntArray, l: Lib): Int {
        val h = l.m.size; var n = 0
        for (r in 0..8 - h) for (c in 0..8 - l.w) {
            var ok = true
            for (i in 0 until h) if ((rows[r + i] and (l.m[i] shl c)) != 0) { ok = false; break }
            if (ok) { n++; if (n >= 6) return n }
        }
        return n
    }

    private fun survival(rows: IntArray): Double {
        var s = 0.0; var tw = 0.0
        for (l in LIB) { s += l.wt * count(rows, l) / 6.0; tw += l.wt }
        return 40.0 * s / tw
    }

    private fun place(rows: IntArray, m: IntArray, r: Int, c: Int): Pl? {
        val h = m.size
        for (i in 0 until h) if ((rows[r + i] and (m[i] shl c)) != 0) return null
        val pre = rows.copyOf()
        for (i in 0 until h) pre[r + i] = pre[r + i] or (m[i] shl c)
        var cf = 255
        for (x in pre) cf = cf and x
        var rf = 0
        for (i in 0 until 8) if (pre[i] == 255) rf = rf or (1 shl i)
        val after = IntArray(8) { i -> if (((rf shr i) and 1) == 1) 0 else pre[i] and cf.inv() and 255 }
        return Pl(pre, after, rf, cf, Integer.bitCount(rf) + Integer.bitCount(cf))
    }

    private fun beam(board: IntArray, pieces: List<List<IntArray>>, keep: Int, finalKeep: Int): List<St>? {
        val ids = pieces.indices.filter { pieces[it].isNotEmpty() }
        if (ids.isEmpty()) return null
        val ms = pieces.map { if (it.isEmpty()) IntArray(0) else toMasks(it) }
        var b = listOf(St(board.copyOf(), 0, emptyList(), 0, 0.0))
        for (level in ids.indices) {
            val next = HashMap<Int, St>()
            for (s in b) for (pi in ids) {
                if (((s.used shr pi) and 1) == 1) continue
                val m = ms[pi]; val h = m.size; val w = width(m)
                for (r in 0..8 - h) for (c in 0..8 - w) {
                    val pl = place(s.rows, m, r, c) ?: continue
                    val st = St(pl.after, s.used or (1 shl pi), s.moves + Move(pi, r, c, pieces[pi], pl.nl, pl.pre, pl.after, pl.rf, pl.cf), s.lines + pl.nl, 0.0)
                    st.score = cheap(pl.after, st.lines)
                    val key = pl.after.contentHashCode() * 31 + st.used
                    val old = next[key]
                    if (old == null || old.score < st.score) next[key] = st
                }
            }
            if (next.isEmpty()) { if (level == 0) return null; break }
            b = next.values.sortedByDescending { it.score }.take(if (level < ids.size - 1) keep else finalKeep)
        }
        return b
    }

    private fun toSolution(s: St, total: Int) = Solution(s.moves, s.moves.size == total, total)

    /** Giải nhanh (tức thì). */
    fun solve(board: IntArray, pieces: List<List<IntArray>>): Solution? {
        val b = beam(board, pieces, 90, 400) ?: return null
        val best = b.maxByOrNull { it.score + survival(it.rows) }!!
        return toSolution(best, pieces.count { it.isNotEmpty() })
    }

    /** Giá trị tốt nhất có thể đạt ở lượt sau với bộ 3 mảnh t (chỉ số trong SHAPES); -300 nếu thua. */
    private fun nextBest(rows: IntArray, t: IntArray): Double {
        var bm = listOf(NS(rows, 0, 0, 0.0))
        for (level in 0 until 3) {
            val nxt = ArrayList<NS>()
            for (s in bm) for (j in 0 until 3) {
                if (((s.used shr j) and 1) == 1) continue
                val m = LIB[t[j]].m; val w = LIB[t[j]].w
                for (r in 0..8 - m.size) for (c in 0..8 - w) {
                    val pl = place(s.rows, m, r, c) ?: continue
                    val nl = s.lines + pl.nl
                    nxt.add(NS(pl.after, s.used or (1 shl j), nl, cheap(pl.after, nl)))
                }
            }
            if (nxt.isEmpty()) return -300.0 + 60.0 * level
            bm = nxt.sortedByDescending { it.score }.take(6)
        }
        return bm[0].score
    }

    /** AI: dùng tối đa budgetMs mili-giây để chọn phương án sống sót tốt nhất ở các lượt sau. */
    fun solveAI(board: IntArray, pieces: List<List<IntArray>>, budgetMs: Long, progress: (Float) -> Unit): Solution? {
        val total = pieces.count { it.isNotEmpty() }
        val fin = beam(board, pieces, 120, 600) ?: return null
        if (fin[0].moves.size != total) return toSolution(fin[0], total)          // không đặt hết được: không cần nhìn xa
        val ranked = fin.sortedByDescending { it.score + survival(it.rows) }.take(40)
        val n = ranked.size
        val stat = DoubleArray(n) { ranked[it].score + survival(ranked[it].rows) }
        val imm = DoubleArray(n) { ranked[it].lines * ranked[it].lines * 10.0 }
        val sum = DoubleArray(n); val cnt = IntArray(n)
        val t0 = System.nanoTime(); val deadline = t0 + budgetMs * 1_000_000L
        val rnd = java.util.Random(7)
        var alive = (0 until n).toList(); var round = 0
        outer@ while (alive.isNotEmpty() && round < 12) {
            val triples = List(6 shl round) { IntArray(3) { rnd.nextInt(LIB.size) } }
            for (i in alive) for (t in triples) {
                if (System.nanoTime() > deadline) break@outer
                sum[i] += nextBest(ranked[i].rows, t); cnt[i]++
            }
            progress(minOf(1f, (System.nanoTime() - t0).toFloat() / (budgetMs * 1_000_000f)))
            if (alive.size == 1) break
            alive = alive.sortedByDescending { imm[it] + sum[it] / cnt[it] }.take(maxOf(1, alive.size / 2))
            round++
        }
        var bi = 0; var bv = -1e18
        for (i in 0 until n) if (cnt[i] > 0) { val v = imm[i] + sum[i] / cnt[i] + 0.2 * stat[i]; if (v > bv) { bv = v; bi = i } }
        return toSolution(ranked[bi], total)
    }
}
