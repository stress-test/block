package com.example.bbsolver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

/** Lớp phủ trên game: chế độ TỪNG BƯỚC (có hoạt ảnh kéo mảnh từ khay vào ô đích) hoặc xem cả 3 nước cùng lúc. */
class OverlayView(ctx: Context) : View(ctx) {
    var moves: List<Move>? = null
    var cfg: Map<String, Float>? = null
    var title = ""
    var sub = ""
    var step = 0
    var showAll = false
    var progress = -1f
    private var body: List<String> = emptyList()
    private var head = ""
    private var phase = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cols = intArrayOf(0xFF2F80ED.toInt(), 0xFF27AE60.toInt(), 0xFFFF6B2C.toInt())
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedFraction; if (visibility == VISIBLE && !moves.isNullOrEmpty()) invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    /** Gọi sau khi đổi moves / step / showAll / title / sub / progress. */
    fun refresh() {
        val mv = moves
        fun detail(m: Move) = "Kéo xuống hàng ${m.r + 1}, cột ${m.c + 1}" + (if (m.lines > 0) " · xóa ${m.lines} đường" else "")
        if (mv.isNullOrEmpty()) { head = title; body = listOfNotNull(sub.ifEmpty { null }) }
        else if (showAll) {
            head = title
            body = mv.mapIndexed { i, m -> "${i + 1}. Mảnh ${m.pi + 1} → hàng ${m.r + 1}, cột ${m.c + 1}" + (if (m.lines > 0) " · xóa ${m.lines}" else "") } + listOfNotNull(sub.ifEmpty { null })
        } else {
            val m = mv[step.coerceIn(0, mv.size - 1)]
            head = "Bước ${step.coerceIn(0, mv.size - 1) + 1}/${mv.size} · Mảnh ${m.pi + 1}"
            body = listOf(detail(m)) + listOfNotNull(sub.ifEmpty { null })
        }
        invalidate()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(cv: Canvas) {
        val c = cfg ?: return
        val W = width.toFloat(); val H = height.toFloat()
        val bw = c["bs"]!! * W; val cell = bw / 8; val x0 = c["bx"]!! * W; val y0 = c["by"]!! * H
        p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT
        val mv = moves
        if (!mv.isNullOrEmpty()) { if (showAll) drawAll(cv, mv, x0, y0, cell) else drawStep(cv, mv[step.coerceIn(0, mv.size - 1)], c, W, H, x0, y0, cell) }
        drawPanel(cv, W, x0, y0, cell)
    }

    private fun roundCell(cv: Canvas, l: Float, t: Float, cell: Float, col: Int, alpha: Int) {
        val rad = cell * 0.18f
        p.style = Paint.Style.FILL; p.color = col; p.alpha = alpha
        cv.drawRoundRect(l + 3, t + 3, l + cell - 3, t + cell - 3, rad, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.WHITE; p.alpha = minOf(255, alpha + 30)
        cv.drawRoundRect(l + 3, t + 3, l + cell - 3, t + cell - 3, rad, rad, p)
    }

    private fun drawAll(cv: Canvas, mv: List<Move>, x0: Float, y0: Float, cell: Float) {
        mv.forEachIndexed { i, m ->
            for (q in m.cells) roundCell(cv, x0 + (m.c + q[1]) * cell, y0 + (m.r + q[0]) * cell, cell, cols[m.pi % 3], 215)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = cell * 0.55f; p.typeface = Typeface.DEFAULT_BOLD
            val f = m.cells[0]
            cv.drawText("${i + 1}", x0 + (m.c + f[1] + .5f) * cell, y0 + (m.r + f[0] + .5f) * cell + p.textSize / 3, p)
        }
    }

    private fun drawStep(cv: Canvas, m: Move, c: Map<String, Float>, W: Float, H: Float, x0: Float, y0: Float, cell: Float) {
        val pulse = 0.5f + 0.5f * Math.sin(phase * 2 * Math.PI).toFloat()
        val col = cols[m.pi % 3]; val rad = cell * 0.18f
        // hàng / cột sẽ được xóa: nhấp nháy trắng
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = (35 + 90 * pulse).toInt()
        for (r in 0 until 8) if (((m.rowsFull shr r) and 1) == 1) cv.drawRoundRect(x0, y0 + r * cell + 4, x0 + 8 * cell, y0 + (r + 1) * cell - 4, rad, rad, p)
        for (k in 0 until 8) if (((m.colsFull shr k) and 1) == 1) cv.drawRoundRect(x0 + k * cell + 4, y0, x0 + (k + 1) * cell - 4, y0 + 8 * cell, rad, rad, p)
        // ô đích: nhịp thở
        for (q in m.cells) roundCell(cv, x0 + (m.c + q[1]) * cell, y0 + (m.r + q[0]) * cell, cell, col, (110 + 100 * pulse).toInt())
        // mảnh "bay" từ khay vào ô đích
        val pw = m.cells.maxOf { it[1] } + 1; val ph = m.cells.maxOf { it[0] } + 1
        val slotX = (c["bx"]!! + c["bs"]!! / 2) * W + (m.pi - 1) * 0.328f * c["bs"]!! * W
        val slotY = (c["ty"]!! + c["th"]!! * 0.3f) * H
        val tx = x0 + (m.c + pw / 2f) * cell; val ty = y0 + (m.r + ph / 2f) * cell
        val t = minOf(1f, phase / 0.65f); val e = t * t * (3 - 2 * t)
        val cx = lerp(slotX, tx, e); val cy = lerp(slotY, ty, e); val sc = lerp(c["ts"]!!, 1f, e)
        // vòng khoanh mảnh cần lấy trên khay
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = col; p.alpha = (120 + 120 * pulse).toInt()
        cv.drawCircle(slotX, slotY, cell * (1.15f + 0.1f * pulse), p)
        if (phase < 0.85f) {
            for (q in m.cells) {
                val l = cx + (q[1] - pw / 2f) * cell * sc; val tp = cy + (q[0] - ph / 2f) * cell * sc
                p.style = Paint.Style.FILL; p.color = col; p.alpha = 235
                cv.drawRoundRect(l + 2, tp + 2, l + cell * sc - 2, tp + cell * sc - 2, rad * sc, rad * sc, p)
            }
            if (phase < 0.65f) { p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = 210; cv.drawCircle(cx, cy, cell * 0.17f, p) }
        }
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.alpha = 255; p.textSize = cell * 0.5f; p.typeface = Typeface.DEFAULT_BOLD
        cv.drawText("${m.pi + 1}", slotX, slotY + p.textSize / 3, p)
    }

    private fun drawPanel(cv: Canvas, W: Float, x0: Float, y0: Float, cell: Float) {
        val lh = cell * 0.4f; val ph = cell * 0.3f + lh * (body.size + 1.2f) + (if (progress >= 0f) cell * 0.15f else 0f)
        val top = (y0 - ph - 14f).coerceAtLeast(16f)
        p.style = Paint.Style.FILL; p.color = 0xEB181820.toInt(); p.alpha = 235
        cv.drawRoundRect(x0, top, x0 + 8 * cell, top + ph, cell * 0.25f, cell * 0.25f, p)
        p.color = Color.WHITE; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = cell * 0.38f; p.textAlign = Paint.Align.CENTER
        var ty = top + cell * 0.1f + lh
        cv.drawText(head, x0 + 4 * cell, ty, p)
        p.typeface = Typeface.DEFAULT; p.textSize = cell * 0.3f
        body.forEachIndexed { i, s ->
            ty += lh
            p.color = if (i == body.size - 1 && s == sub) 0xFFB0B0BC.toInt() else Color.WHITE
            cv.drawText(s, x0 + 4 * cell, ty, p)
        }
        if (progress >= 0f) {
            val by = top + ph - cell * 0.16f; val l = x0 + cell * 0.4f; val r = x0 + 8 * cell - cell * 0.4f
            p.color = 0xFF3A3A46.toInt(); cv.drawRoundRect(l, by, r, by + 8f, 4f, 4f, p)
            p.color = 0xFFFF6B2C.toInt(); cv.drawRoundRect(l, by, l + (r - l) * progress.coerceIn(0f, 1f), by + 8f, 4f, 4f, p)
        }
    }
}
