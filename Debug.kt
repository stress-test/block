package com.example.bbsolver

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/** Lưu 2 ảnh vào Thư viện > BBSolver: ảnh gốc app nhìn thấy, và ảnh có đánh dấu những gì app đọc được. */
object Debug {
    fun save(ctx: Context, f: Frame, sc: Scan): String {
        val px = IntArray(f.W * f.H)
        for (y in 0 until f.H) for (x in 0 until f.W) px[y * f.W + x] = 0xFF000000.toInt() or f.rgb(x, y)
        val raw = Bitmap.createBitmap(px, f.W, f.H, Bitmap.Config.ARGB_8888)
        val ann = raw.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(ann); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val g = sc.geo; val cell = g["bs"]!! * f.W / 8; val x0 = g["bx"]!! * f.W; val y0 = g["by"]!! * f.H
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.RED
        cv.drawRect(x0, y0, x0 + 8 * cell, y0 + 8 * cell, p)
        p.color = Color.YELLOW
        cv.drawRect(0f, g["ty"]!! * f.H, f.W.toFloat(), (g["ty"]!! + g["th"]!!) * f.H, p)
        p.style = Paint.Style.FILL
        for (r in 0 until 8) for (k in 0 until 8) {
            p.color = if (((sc.board[r] shr k) and 1) == 1) Color.GREEN else Color.RED
            cv.drawCircle(x0 + (k + .5f) * cell, y0 + (r + .5f) * cell, cell * 0.1f, p)
        }
        val q = 34f; val by = f.H - 260f
        for (i in 0 until 3) {
            val bx = f.W * (i + .5f) / 3 - 2.5f * q
            p.color = 0xCC000000.toInt(); cv.drawRect(bx - 6, by - 6, bx + 5 * q + 6, by + 5 * q + 6, p)
            p.color = Color.WHITE
            for (c in sc.pieces[i]) cv.drawRect(bx + c[1] * q + 2, by + c[0] * q + 2, bx + (c[1] + 1) * q - 2, by + (c[0] + 1) * q - 2, p)
        }
        p.color = Color.WHITE; p.textSize = 36f
        cv.drawText("bx=%.3f by=%.3f bs=%.3f ty=%.3f auto=%.0f".format(g["bx"], g["by"], g["bs"], g["ty"], g["auto"]), 20f, 40f, p)
        val t = System.currentTimeMillis()
        put(ctx, raw, "bb_raw_$t"); put(ctx, ann, "bb_debug_$t")
        return "Thư viện > BBSolver"
    }

    private fun put(ctx: Context, bmp: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BBSolver")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            val d = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!; d.mkdirs()
            java.io.File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
