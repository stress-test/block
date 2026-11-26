package com.example.bbsolver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/** Lớp phủ: hiện những gì app đọc được, và hướng dẫn từng bước bằng hoạt ảnh (nhấc mảnh → bay theo đường cong → thả vào ô → hiệu ứng xóa hàng). */
class OverlayView(ctx: Context) : View(ctx) {
    var moves: List<Move>? = null
    var cfg: Map<String, Float>? = null
    var boxes: List<FloatArray?> = List(3) { null }
    var trayCell = 0f
    var title = ""
    var sub = ""
    var step = 0
    var showAll = false
    var progress = -1f
    private var body: List<String> = emptyList()
    private var head = ""
    private var phase = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cols = intArrayOf(0xFF3B8CFF.toInt(), 0xFF2ECC71.toInt(), 0xFFFF7A2F.toInt())
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedFraction; if (visibility == VISIBLE && !moves.isNullOrEmpty()) invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    fun refresh() {
        val mv = moves
        fun detail(m: Move) = "Kéo xuống hàng ${m.r + 1}, cột ${m.c + 1}" + (if (m.lines > 0) " · xóa ${m.lines} đường" else "")
        if (mv.isNullOrEmpty()) { head = title; body = listOfNotNull(sub.ifEmpty { null }) }
        else if (showAll) {
            head = title
            body = mv.mapIndexed { i, m -> "${i + 1}. Mảnh ${m.pi + 1} → hàng ${m.r + 1}, cột ${m.c + 1}" + (if (m.lines > 0) " · xóa ${m.lines}" else "") } + listOfNotNull(sub.ifEmpty { null })
        } else {
            val m = mv[step.coerceIn(0, mv.size - 1)]
            head = "Mảnh ${m.pi + 1}"
            body = listOf(detail(m)) + listOfNotNull(sub.ifEmpty { null })
        }
        invalidate()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun ease(t: Float) = if (t < 0.5f) 4 * t * t * t else { val u = -2 * t + 2; 1 - u * u * u / 2 }

    override fun onDraw(cv: Canvas) {
        val c = cfg ?: return
        val W = width.toFloat(); val H = height.toFloat()
        val cell = c["bs"]!! * W / 8; val x0 = c["bx"]!! * W; val y0 = c["by"]!! * H
        p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT; p.pathEffect = null
        drawDetected(cv, cell)
        val mv = moves
        if (!mv.isNullOrEmpty()) { if (showAll) drawAll(cv, mv, x0, y0, cell) else drawStep(cv, mv[step.coerceIn(0, mv.size - 1)], c, W, H, x0, y0, cell) }
        drawPanel(cv, x0, y0, cell)
    }

    /** Khung mảnh app đọc được trên khay: nếu thiếu mảnh nào sẽ thấy ngay khung và số bị thiếu. */
    private fun drawDetected(cv: Canvas, cell: Float) {
        for (i in 0 until 3) {
            val b = boxes.getOrNull(i) ?: continue
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = cols[i]; p.alpha = 150
            cv.drawRoundRect(b[0] - 10, b[1] - 10, b[2] + 10, b[3] + 10, 18f, 18f, p)
            p.style = Paint.Style.FILL; p.color = cols[i]; p.alpha = 255; p.textSize = cell * 0.36f; p.typeface = Typeface.DEFAULT_BOLD
            cv.drawText("${i + 1}", (b[0] + b[2]) / 2, b[1] - 20, p)
        }
    }

    private fun fancyCell(cv: Canvas, l: Float, t: Float, sz: Float, col: Int, a: Int) {
        val rad = sz * 0.18f
        p.style = Paint.Style.FILL; p.color = col; p.alpha = a
        cv.drawRoundRect(l + 2, t + 2, l + sz - 2, t + sz - 2, rad, rad, p)
        p.color = Color.WHITE; p.alpha = (a * 0.30f).toInt()
        cv.drawRoundRect(l + sz * 0.14f, t + sz * 0.12f, l + sz * 0.86f, t + sz * 0.46f, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = maxOf(2f, sz * 0.035f); p.color = Color.WHITE; p.alpha = (a * 0.6f).toInt()
        cv.drawRoundRect(l + 2, t + 2, l + sz - 2, t + sz - 2, rad, rad, p)
    }

    private fun piece(cv: Canvas, cells: List<IntArray>, cx: Float, cy: Float, sz: Float, col: Int, a: Int) {
        val pw = cells.maxOf { it[1] } + 1; val ph = cells.maxOf { it[0] } + 1
        for (q in cells) fancyCell(cv, cx + (q[1] - pw / 2f) * sz, cy + (q[0] - ph / 2f) * sz, sz, col, a)
    }

    private fun drawAll(cv: Canvas, mv: List<Move>, x0: Float, y0: Float, cell: Float) {
        mv.forEachIndexed { i, m ->
            for (q in m.cells) fancyCell(cv, x0 + (m.c + q[1]) * cell, y0 + (m.r + q[0]) * cell, cell, cols[m.pi % 3], 215)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = 255; p.textSize = cell * 0.55f; p.typeface = Typeface.DEFAULT_BOLD
            val f = m.cells[0]
            cv.drawText("${i + 1}", x0 + (m.c + f[1] + .5f) * cell, y0 + (m.r + f[0] + .5f) * cell + p.textSize / 3, p)
        }
    }

    private fun drawStep(cv: Canvas, m: Move, c: Map<String, Float>, W: Float, H: Float, x0: Float, y0: Float, cell: Float) {
        val col = cols[m.pi % 3]; val rad = cell * 0.18f
        val pw = m.cells.maxOf { it[1] } + 1; val ph = m.cells.maxOf { it[0] } + 1
        // mốc thời gian trong 1 vòng: nhấc → bay → thả → xóa hàng → mờ dần
        val travel = ((phase - 0.12f) / 0.50f).coerceIn(0f, 1f); val e = ease(travel)
        val landU = ((phase - 0.62f) / 0.12f).coerceIn(0f, 1f)
        val sweepU = ((phase - 0.74f) / 0.21f).coerceIn(0f, 1f)
        val fade = if (phase > 0.95f) 1f - (phase - 0.95f) / 0.05f else 1f
        val pulse = 0.5f + 0.5f * Math.sin(phase * 2 * Math.PI * 2).toFloat()
        val tx = x0 + (m.c + pw / 2f) * cell; val ty = y0 + (m.r + ph / 2f) * cell
        val bx = boxes.getOrNull(m.pi)
        val sx = if (bx != null) (bx[0] + bx[2]) / 2 else (c["bx"]!! + c["bs"]!! / 2) * W + (m.pi - 1) * 0.328f * c["bs"]!! * W
        val sy = if (bx != null) (bx[1] + bx[3]) / 2 else (c["ty"]!! + c["th"]!! * 0.3f) * H
        val s0 = if (trayCell > 0f) trayCell / cell else c["ts"]!!

        // 1) hàng / cột sẽ bị xóa: nền sáng + vệt sáng quét ngang
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = (30 + 40 * pulse).toInt()
        val strips = ArrayList<RectF>()
        for (r in 0 until 8) if (((m.rowsFull shr r) and 1) == 1) strips.add(RectF(x0, y0 + r * cell + 3, x0 + 8 * cell, y0 + (r + 1) * cell - 3))
        for (k in 0 until 8) if (((m.colsFull shr k) and 1) == 1) strips.add(RectF(x0 + k * cell + 3, y0, x0 + (k + 1) * cell - 3, y0 + 8 * cell))
        for (rc in strips) {
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = (30 + 40 * pulse).toInt(); cv.drawRoundRect(rc, rad, rad, p)
            if (sweepU > 0f && sweepU < 1f) {
                cv.save(); cv.clipRect(rc)
                val horiz = rc.width() > rc.height()
                val len = if (horiz) rc.width() else rc.height(); val pos = -cell + (len + 2 * cell) * sweepU
                p.color = Color.WHITE; p.alpha = (190 * Math.sin(sweepU * Math.PI).toFloat()).toInt()
                if (horiz) cv.drawRect(rc.left + pos, rc.top, rc.left + pos + cell * 0.9f, rc.bottom, p) else cv.drawRect(rc.left, rc.top + pos, rc.right, rc.top + pos + cell * 0.9f, p)
                cv.restore()
            }
        }
        // 2) ô đích: nền mờ + viền nét đứt chạy
        for (q in m.cells) {
            p.style = Paint.Style.FILL; p.color = col; p.alpha = (55 + 45 * pulse).toInt()
            cv.drawRoundRect(x0 + (m.c + q[1]) * cell + 3, y0 + (m.r + q[0]) * cell + 3, x0 + (m.c + q[1] + 1) * cell - 3, y0 + (m.r + q[0] + 1) * cell - 3, rad, rad, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.WHITE; p.alpha = 230; p.pathEffect = DashPathEffect(floatArrayOf(16f, 12f), phase * 112f)
        for (q in m.cells) cv.drawRoundRect(x0 + (m.c + q[1]) * cell + 3, y0 + (m.r + q[0]) * cell + 3, x0 + (m.c + q[1] + 1) * cell - 3, y0 + (m.r + q[0] + 1) * cell - 3, rad, rad, p)
        p.pathEffect = null
        // 3) đường bay cong (đường chấm)
        val dist = Math.hypot((tx - sx).toDouble(), (ty - sy).toDouble()).toFloat()
        val kx = (sx + tx) / 2; val ky = (sy + ty) / 2 - 0.25f * dist - cell
        fun bez(t: Float, isX: Boolean): Float { val u = 1 - t; return if (isX) u * u * sx + 2 * u * t * kx + t * t * tx else u * u * sy + 2 * u * t * ky + t * t * ty }
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        var d = 0.04f
        while (d < 0.97f) { p.alpha = (110 * fade).toInt(); cv.drawCircle(bez(d, true), bez(d, false), 4.5f, p); d += 0.045f }
        // 4) khoanh mảnh cần lấy
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = col; p.alpha = (140 + 100 * pulse).toInt()
        val sw = (if (bx != null) (bx[2] - bx[0]) / 2 else cell) + 16f + 6f * pulse; val sh = (if (bx != null) (bx[3] - bx[1]) / 2 else cell) + 16f + 6f * pulse
        if (phase < 0.45f) cv.drawRoundRect(sx - sw, sy - sh, sx + sw, sy + sh, 26f, 26f, p)
        // 5) mảnh "ma" bay: vệt mờ phía sau + bật nhẹ khi chạm đích
        if (phase >= 0.12f && phase < 0.95f) {
            if (travel < 1f) for (k in 3 downTo 1) {
                val te = ease((travel - 0.045f * k).coerceAtLeast(0f))
                piece(cv, m.cells, bez(te, true), bez(te, false), cell * lerp(s0, 1f, te), col, 80 / k)
            }
            val bounce = if (phase > 0.62f) 1f + 0.08f * Math.sin(landU * Math.PI).toFloat() else 1f
            piece(cv, m.cells, bez(e, true), bez(e, false), cell * lerp(s0, 1f, e) * bounce, col, (235 * fade).toInt())
        } else if (phase < 0.12f) {
            piece(cv, m.cells, sx, sy, cell * s0 * (1f + 0.07f * pulse), col, 235)
        }
        // 6) gợn sóng khi chạm đích
        if (landU > 0f && landU < 1f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = Color.WHITE; p.alpha = (200 * (1 - landU)).toInt()
            cv.drawCircle(tx, ty, cell * (0.5f + 2.2f * landU), p)
        }
    }

    private fun drawPanel(cv: Canvas, x0: Float, y0: Float, cell: Float) {
        val mv = moves; val n = mv?.size ?: 0
        val lh = cell * 0.36f; val hasProg = progress >= 0f
        val ph = cell * 0.34f + cell * 0.46f + lh * body.size + (if (hasProg) cell * 0.22f else 0f)
        val top = (y0 - ph - 14f).coerceAtLeast(16f); val left = x0; val right = x0 + 8 * cell
        val accent = if (!mv.isNullOrEmpty() && !showAll) cols[mv[step.coerceIn(0, n - 1)].pi % 3] else 0xFFFF7A2F.toInt()
        p.style = Paint.Style.FILL; p.color = 0xF2161620.toInt(); p.alpha = 242
        cv.drawRoundRect(left, top, right, top + ph, cell * 0.28f, cell * 0.28f, p)
        p.color = accent; p.alpha = 255
        cv.drawRoundRect(left, top + cell * 0.18f, left + 10f, top + ph - cell * 0.18f, 5f, 5f, p)
        p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = cell * 0.42f
        var ty = top + cell * 0.30f + cell * 0.32f
        cv.drawText(head, left + cell * 0.38f, ty, p)
        // chấm tiến trình các bước
        if (n > 0 && !showAll) for (i in 0 until n) {
            p.color = if (i == step.coerceIn(0, n - 1)) accent else 0xFF4A4A58.toInt()
            cv.drawCircle(right - cell * 0.35f - (n - 1 - i) * cell * 0.3f, ty - cell * 0.13f, cell * (if (i == step) 0.11f else 0.08f), p)
        }
        p.typeface = Typeface.DEFAULT; p.textSize = cell * 0.29f
        body.forEachIndexed { i, s ->
            ty += lh
            p.color = if (i == body.size - 1 && s == sub) 0xFFA8A8B8.toInt() else 0xFFEDEDF2.toInt()
            cv.drawText(s, left + cell * 0.38f, ty, p)
        }
        if (hasProg) {
            val by = top + ph - cell * 0.2f; val l = left + cell * 0.38f; val r = right - cell * 0.38f
            p.color = 0xFF3A3A46.toInt(); cv.drawRoundRect(l, by, r, by + 9f, 5f, 5f, p)
            p.color = accent; cv.drawRoundRect(l, by, l + (r - l) * progress.coerceIn(0f, 1f), by + 9f, 5f, 5f, p)
        }
    }
}
