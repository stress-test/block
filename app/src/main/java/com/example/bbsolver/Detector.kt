package com.example.bbsolver

import android.content.Context

object Cfg {
    // bx,bs theo chiều rộng màn hình; by,ty,th theo chiều cao. ts: ô khay/ô bảng. vt: ngưỡng dự phòng. bias: độ nhạy.
    val DEF = linkedMapOf("bx" to 0.056f, "by" to 0.241f, "bs" to 0.889f, "ty" to 0.70f, "th" to 0.12f,
        "ts" to 0.45f, "vt" to 0.50f, "bias" to 0.0f, "auto" to 1.0f, "think" to 3.0f)
    fun load(c: Context): Map<String, Float> { val p = c.getSharedPreferences("cfg", 0); return DEF.mapValues { p.getFloat(it.key, it.value) } }
    fun calibrated(c: Context) = c.getSharedPreferences("cfg", 0).getBoolean("calib", false)
    fun reset(c: Context) = c.getSharedPreferences("cfg", 0).edit().clear().apply()
}

/** Ảnh màn hình dạng byte thô RGBA (không tạo Bitmap, không getPixel → nhanh, ít rác bộ nhớ). */
class Frame(val buf: ByteArray, val stride: Int, val W: Int, val H: Int) {
    fun rgb(x: Int, y: Int): Int {
        val i = y.coerceIn(0, H - 1) * stride + x.coerceIn(0, W - 1) * 4
        return ((buf[i].toInt() and 255) shl 16) or ((buf[i + 1].toInt() and 255) shl 8) or (buf[i + 2].toInt() and 255)
    }
}

class Scan(val board: IntArray, val pieces: List<List<IntArray>>, val geo: Map<String, Float>) {
    fun key() = board.joinToString(",") + "|" + pieces.joinToString("/") { p -> p.joinToString(";") { "${it[0]}${it[1]}" } }
}

object Detector {
    private fun score(c: Int): Float {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        return mx / 255f * (0.3f + s)
    }
    private fun avgScore(f: Frame, cx: Float, cy: Float, d: Float): Float {
        var t = 0f
        for (dy in -1..1) for (dx in -1..1) t += score(f.rgb((cx + dx * d).toInt(), (cy + dy * d).toInt()))
        return t / 9f
    }
    /** Tự tìm ngưỡng tách ô trống / ô có khối (Otsu) → chạy tốt với mọi theme. */
    private fun otsu(v: FloatArray, fallback: Float): Float {
        val s = v.sortedArray()
        if (s.last() - s.first() < 0.25f) return fallback
        val n = s.size; val tot = s.sum(); var acc = 0f; var bestV = -1f; var bestT = fallback
        for (i in 1 until n) {
            acc += s[i - 1]
            val m1 = acc / i; val m2 = (tot - acc) / (n - i)
            val vr = i.toFloat() * (n - i) * (m1 - m2) * (m1 - m2)
            if (vr > bestV) { bestV = vr; bestT = (s[i - 1] + s[i]) / 2 }
        }
        return bestT
    }

    /** Tự dò vị trí bảng 8×8: các ô trống + khe giữa các ô là màu tối, tạo thành khung vuông. Không được thì dùng căn chỉnh tay. */
    fun geometry(f: Frame, c: Map<String, Float>): Map<String, Float> {
        if (c["auto"]!! < 0.5f) return c
        fun dark(p: Int): Boolean { val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255; return maxOf(r, g, b) < 80 && b >= r }
        val cc = IntArray(f.W / 4 + 1); val rc = IntArray(f.H / 4 + 1)
        var y = (f.H * 0.10f).toInt(); val ye = (f.H * 0.80f).toInt()
        while (y < ye) { var x = 0; while (x < f.W) { if (dark(f.rgb(x, y))) { cc[x / 4]++; rc[y / 4]++ }; x += 4 }; y += 4 }
        val mc = cc.max(); val mr = rc.max()
        if (mc < 20 || mr < 20) return c
        val xs = cc.indices.filter { cc[it] >= mc * 0.5f }; val ys = rc.indices.filter { rc[it] >= mr * 0.5f }
        val left = xs.first() * 4; val right = xs.last() * 4 + 3; val top = ys.first() * 4; val bot = ys.last() * 4 + 3
        val w = right - left + 1; val h = bot - top + 1
        if (w < f.W * 0.5f || w > f.W || Math.abs(w - h) > w * 0.05f) return c
        return c + mapOf("bx" to left.toFloat() / f.W, "by" to top.toFloat() / f.H, "bs" to w.toFloat() / f.W,
            "ty" to (bot + 0.04f * f.H) / f.H, "th" to 0.26f)
    }

    fun board(f: Frame, c: Map<String, Float>): IntArray {
        val cell = c["bs"]!! * f.W / 8; val x0 = c["bx"]!! * f.W; val y0 = c["by"]!! * f.H; val d = cell * 0.18f
        val sc = FloatArray(64) { avgScore(f, x0 + (it % 8 + .5f) * cell, y0 + (it / 8 + .5f) * cell, d) }
        val thr = otsu(sc, c["vt"]!!) + c["bias"]!!
        return IntArray(8) { r -> var m = 0; for (k in 0 until 8) if (sc[r * 8 + k] > thr) m = m or (1 shl k); m }
    }

    private class Hyp(val s: Float, val cols: Int, val rows: Int)
    private class Blob(val x1: Int, val x2: Int, val y1: Int, val y2: Int) {
        val bw: Float get() = (x2 - x1 + 3).toFloat()
        val bh: Float get() = (y2 - y1 + 3).toFloat()
    }

    /**
     * Đọc 3 mảnh: gom khối liền nhau thành từng mảnh, rồi chọn MỘT kích thước ô chung cho cả khay sao cho
     * mọi ô đều "tròn trịa" (hoặc đặc hoặc trống), cuối cùng ép về hình hợp lệ của game nếu chỉ lệch 1 ô.
     */
    fun pieces(f: Frame, c: Map<String, Float>): List<List<IntArray>> {
        val ideal = c["bs"]!! * f.W / 8 * c["ts"]!!
        val ty = (c["ty"]!! * f.H).toInt(); val th = minOf((c["th"]!! * f.H).toInt(), f.H - ty - 1)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        var yy = ty
        while (yy < ty + th) { var xx = 0; while (xx < f.W) { val p = f.rgb(xx, yy); rs.add((p shr 16) and 255); gs.add((p shr 8) and 255); bs.add(p and 255); xx += 12 }; yy += 12 }
        rs.sort(); gs.sort(); bs.sort()
        val br = rs[rs.size / 2]; val bg = gs[gs.size / 2]; val bb = bs[bs.size / 2]   // màu nền khay = trung vị
        val fg = { p: Int ->
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val dr = r - br; val dg = g - bg; val db = b - bb
            dr * dr + dg * dg + db * db > 48 * 48 && maxOf(r, g, b) > 90
        }
        val colHas = BooleanArray(f.W)
        var x = 0
        while (x < f.W) { var y = ty; while (y < ty + th) { if (fg(f.rgb(x, y))) { colHas[x] = true; break }; y += 3 }; x += 3 }
        val gapMax = (ideal * 0.9f).toInt()
        val clusters = ArrayList<IntArray>(); var s = -1; var last = -1000000
        x = 0
        while (x < f.W) { if (colHas[x]) { if (s < 0 || x - last > gapMax) { if (s >= 0) clusters.add(intArrayOf(s, last)); s = x }; last = x }; x += 3 }
        if (s >= 0) clusters.add(intArrayOf(s, last))
        val blobs = ArrayList<Blob>()
        for (cl in clusters) {
            var minY = Int.MAX_VALUE; var maxY = -1; var y = ty
            while (y < ty + th) { var xx = cl[0]; while (xx <= cl[1]) { if (fg(f.rgb(xx, y))) { minY = minOf(minY, y); maxY = maxOf(maxY, y); break }; xx += 3 }; y += 3 }
            if (maxY >= 0) blobs.add(Blob(cl[0], cl[1], minY, maxY))
        }
        val hy = blobs.map { b -> (1..5).mapNotNull { k -> val sz = b.bw / k; val rr = Math.round(b.bh / sz); if (rr < 1 || rr > 5 || Math.abs(b.bh - rr * sz) > sz * 0.3f) null else Hyp(sz, k, rr) } }
        fun frac(b: Blob, h: Hyp, r: Int, k: Int): Float {
            var n = 0
            for (iy in 0 until 4) for (ix in 0 until 4) {
                val px = b.x1 + k * h.s + h.s * (0.15f + 0.7f * (ix + .5f) / 4); val py = b.y1 + r * h.s + h.s * (0.15f + 0.7f * (iy + .5f) / 4)
                if (fg(f.rgb(px.toInt(), py.toInt()))) n++
            }
            return n / 16f
        }
        fun purity(b: Blob, h: Hyp): Float {
            var cst = 0f
            for (r in 0 until h.rows) for (k in 0 until h.cols) { val p = frac(b, h, r, k); cst += minOf(p, 1 - p) }
            return cst / (h.cols * h.rows)
        }
        var bestS = ideal; var bt = Float.MAX_VALUE
        for (sc in hy.flatten().map { it.s }) {
            var t = 0.5f * Math.abs(sc - ideal) / ideal
            for (i in blobs.indices) { var m = 2f; for (h in hy[i]) if (Math.abs(h.s - sc) / sc <= 0.1f) m = minOf(m, purity(blobs[i], h)); t += m }
            if (t < bt) { bt = t; bestS = sc }
        }
        val out = MutableList<List<IntArray>>(3) { emptyList() }
        val cx = (c["bx"]!! + c["bs"]!! / 2) * f.W
        for (i in blobs.indices) {
            val b = blobs[i]
            val h = hy[i].filter { Math.abs(it.s - bestS) / bestS <= 0.15f }.minByOrNull { Math.abs(it.s - bestS) } ?: continue
            val cells = ArrayList<IntArray>()
            for (r in 0 until h.rows) for (k in 0 until h.cols) if (frac(b, h, r, k) > 0.5f) cells.add(intArrayOf(r, k))
            if (cells.isEmpty()) continue
            val mid = (b.x1 + b.x2) / 2f
            val slot = (0..2).minByOrNull { Math.abs(mid - (cx + (it - 1) * 0.328f * c["bs"]!! * f.W)) }!!
            val sn = Solver.snap(cells)
            if (sn.size > out[slot].size) out[slot] = sn
        }
        return out
    }
}
