package com.example.bbsolver

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var overlay: OverlayView? = null
    private var bar: LinearLayout? = null
    private var calib: CalibView? = null
    private var editor: EditorView? = null
    private lateinit var goBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var stepLbl: TextView
    private val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val barLp = WindowManager.LayoutParams(-2, -2, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
    private val h = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var W = 0; private var H = 0
    private var lastFrame: Frame? = null
    private var lastScan: Scan? = null
    private var sol: Solution? = null
    private val lock = Any()
    private var held: android.media.Image? = null
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val frameThread = HandlerThread("bb-frames").apply { start() }

    override fun onBind(i: Intent?): IBinder? = null

    private fun fullLp(touch: Boolean) = WindowManager.LayoutParams(-1, -1, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT).apply {
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("bb", "BB Solver", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bb").setContentTitle("Block Blast Solver đang chạy").setContentText("Mở game và bấm GIẢI").setSmallIcon(android.R.drawable.ic_menu_search).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
        val data = i?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(i!!.getIntExtra("code", 0), data)
        projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, h)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(dm)
        W = dm.widthPixels; H = dm.heightPixels
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) synchronized(lock) { held?.close(); held = img; seq.incrementAndGet() }
        }, Handler(frameThread.looper))
        vd = projection!!.createVirtualDisplay("bb", W, H, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        overlay = OverlayView(this).also { wm.addView(it, fullLp(false)) }
        buildBar()
        getSharedPreferences("cfg", 0).edit().putBoolean("running", true).apply()
        toast("Sẵn sàng · mở game và bấm GIẢI")
        return START_NOT_STICKY
    }

    private fun pill(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r }

    private fun btn(t: String, col: Int, act: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.WHITE); setPadding(28, 16, 28, 16); background = pill(col, 60f)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(5, 4, 5, 4) }; setOnClickListener { act() }
    }

    private fun buildBar() {
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = pill(0xE0181820.toInt(), 90f); setPadding(10, 8, 10, 8) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        goBtn = btn("▶ GIẢI", 0xFFFF6B2C.toInt()) { scan() }
        prevBtn = btn("‹", 0xFF3A3A46.toInt()) { moveStep(-1) }
        nextBtn = btn("›", 0xFF3A3A46.toInt()) { moveStep(1) }
        stepLbl = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(8, 0, 8, 0) }
        listOf(prevBtn, stepLbl, nextBtn).forEach { it.visibility = View.GONE }
        row1.addView(goBtn); row1.addView(prevBtn); row1.addView(stepLbl); row1.addView(nextBtn)
        row1.addView(btn("⋯", 0xFF3A3A46.toInt()) { row2.visibility = if (row2.visibility == View.GONE) View.VISIBLE else View.GONE })
        row1.addView(btn("✕", 0xFF3A3A46.toInt()) { stopSelf() })
        row2.addView(btn("≡ Cả 3", 0xFF3A3A46.toInt()) { overlay?.let { it.showAll = !it.showAll; it.refresh() } })
        row2.addView(btn("✎ Sửa", 0xFF3A3A46.toInt()) { edit() })
        row2.addView(btn("⌖", 0xFF3A3A46.toInt()) { calibrate() })
        row2.addView(btn("🐞", 0xFF3A3A46.toInt()) { saveDebug() })
        col.addView(row1); col.addView(row2)
        val handle = TextView(this).apply { text = "BB"; textSize = 14f; setTextColor(0xFFFF6B2C.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(22, 12, 22, 12) }
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = barLp.x; oy = barLp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - sx) + Math.abs(e.rawY - sy) > 14) moved = true
                        if (moved) { barLp.x = ox + (e.rawX - sx).toInt(); barLp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(b, barLp) }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) col.visibility = if (col.visibility == View.VISIBLE) View.GONE else View.VISIBLE   // chạm BB để thu gọn
                }
                return true
            }
        })
        b.addView(handle); b.addView(col)
        barLp.gravity = Gravity.TOP or Gravity.START; barLp.x = 16; barLp.y = H / 2
        bar = b; wm.addView(b, barLp)
    }

    private fun moveStep(d: Int) {
        val ov = overlay ?: return; val n = ov.moves?.size ?: return
        ov.step = (ov.step + d).coerceIn(0, n - 1); ov.showAll = false; ov.refresh(); updateStepper()
    }

    private fun updateStepper() {
        val ov = overlay; val n = ov?.moves?.size ?: 0
        val v = if (n > 0) View.VISIBLE else View.GONE
        prevBtn.visibility = v; nextBtn.visibility = v; stepLbl.visibility = v
        if (n > 0) { stepLbl.text = "${ov!!.step + 1}/$n"; prevBtn.alpha = if (ov.step > 0) 1f else 0.35f; nextBtn.alpha = if (ov.step < n - 1) 1f else 0.35f }
    }

    private fun idle() { busy.set(false); goBtn.text = "▶ GIẢI"; overlay?.visibility = View.VISIBLE; bar?.visibility = View.VISIBLE }

    private fun grabFrame(): Frame? = synchronized(lock) {
        val img = held ?: return@synchronized lastFrame
        val pl = img.planes[0]; val stride = pl.rowStride; val bb = pl.buffer.duplicate()
        val arr = ByteArray(stride * H); bb.rewind(); bb.get(arr, 0, minOf(bb.remaining(), arr.size))
        Frame(arr, stride, W, H).also { lastFrame = it }
    }

    /** Chờ khung hình MỚI sau khi ẩn lớp phủ; quét tối đa 3 lần, dừng khi hai lần liên tiếp giống nhau. */
    private fun capture(cfg: Map<String, Float>, s0: Long): Scan? {
        val end = SystemClock.elapsedRealtime() + 700
        while (seq.get() <= s0 && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
        Thread.sleep(100)
        var prev: Scan? = null
        repeat(3) {
            val f = grabFrame()
            if (f != null) {
                val g = Detector.geometry(f, cfg)
                val pr = Detector.pieces(f, g)
                val cur = Scan(Detector.board(f, g), pr.pieces, g, pr.boxes, pr.cell)
                if (prev != null && prev!!.key() == cur.key()) return cur
                prev = cur
            }
            Thread.sleep(130)
        }
        return prev
    }

    private fun scan() {
        if (!busy.compareAndSet(false, true)) return
        val s0 = seq.get()
        goBtn.text = "…"; overlay?.visibility = View.INVISIBLE; bar?.visibility = View.INVISIBLE
        val cfg = Cfg.load(this)
        worker.execute {
            try {
                val sc = capture(cfg, s0)
                if (sc == null) { h.post { toast("Chưa chụp được màn hình, thử lại"); idle() }; return@execute }
                solveFlow(sc, cfg)
            } catch (e: Throwable) { h.post { toast("Lỗi: ${e.message}"); idle() } }
        }
    }

    /** Chạy trên worker: hiện ngay gợi ý nhanh, rồi AI suy nghĩ thêm vài giây và thay bằng phương án tốt hơn. */
    private fun solveFlow(sc: Scan, cfg: Map<String, Float>) {
        val budget = (cfg["think"]!! * 1000).toLong()
        val fast = Solver.solve(sc.board, sc.pieces)
        val thinking = fast != null && fast.complete && budget > 0
        h.post { lastScan = sc; show(sc, fast, if (thinking) "AI đang suy nghĩ…" else "", if (thinking) 0f else -1f); if (thinking) goBtn.text = "…" }
        if (!thinking) { h.post { idle() }; return }
        val t0 = SystemClock.elapsedRealtime()
        val ai = Solver.solveAI(sc.board, sc.pieces, budget) { p -> h.post { overlay?.let { it.progress = p; it.refresh() } } }
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        h.post { show(sc, ai ?: fast, "AI đã tối ưu · %.1fs".format(sec), -1f); idle() }
    }

    private fun show(sc: Scan, s: Solution?, note: String, progress: Float) {
        val ov = overlay ?: return
        val seen = sc.pieces.count { it.isNotEmpty() }
        sol = s; ov.cfg = sc.geo; ov.boxes = sc.boxes; ov.trayCell = sc.cell; ov.moves = s?.moves; ov.step = 0; ov.progress = progress
        ov.sub = (if (seen < 3) "Thấy $seen/3 mảnh · " else "") + note
        ov.title = when {
            s == null -> "Không có nước đi"
            !s.complete -> "Chỉ đặt được ${s.moves.size}/${s.total} mảnh"
            else -> "Thứ tự: " + s.moves.joinToString(" → ") { "mảnh ${it.pi + 1}" }
        }
        if (s == null) ov.sub = "Chưa đọc được mảnh · bấm ⋯ rồi ✎ để sửa"
        ov.refresh(); updateStepper()
        ov.visibility = View.VISIBLE; bar?.visibility = View.VISIBLE
    }

    private fun edit() {
        val s = lastScan
        if (s == null) { toast("Hãy bấm GIẢI trước"); return }
        if (busy.get() || editor != null) return
        overlay?.visibility = View.INVISIBLE; bar?.visibility = View.GONE
        val v = EditorView(this, s.board, s.pieces, { rows, ps ->
            h.post {
                closeEditor(); busy.set(true); goBtn.text = "…"; val cfg = Cfg.load(this)
                bar?.visibility = View.VISIBLE
                worker.execute { try { solveFlow(Scan(rows, ps, s.geo, s.boxes, s.cell), cfg) } catch (e: Throwable) { h.post { toast("Lỗi: ${e.message}"); idle() } } }
            }
        }, { h.post { closeEditor(); idle() } })
        editor = v; wm.addView(v, fullLp(true))
    }

    private fun closeEditor() { editor?.let { runCatching { wm.removeView(it) } }; editor = null }

    private fun calibrate() {
        if (busy.get() || calib != null) return
        bar?.visibility = View.GONE; overlay?.visibility = View.INVISIBLE
        val v = CalibView(this) { pts ->
            h.post {
                calib?.let { runCatching { wm.removeView(it) } }; calib = null; idle()
                val a = pts[0]; val b = pts[1]; val c = pts[2]; val d = pts[3]
                if (b.x <= a.x || b.y <= a.y || d.y <= c.y) toast("Căn chỉnh sai thứ tự, thử lại")
                else {
                    getSharedPreferences("cfg", 0).edit().putFloat("bx", a.x / W).putFloat("by", a.y / H).putFloat("bs", (b.x - a.x) / W)
                        .putFloat("auto", 0f).putFloat("ty", c.y / H).putFloat("th", (d.y - c.y) / H).putBoolean("calib", true).apply()
                    toast("Đã lưu căn chỉnh tay ✓ (tắt tự dò bảng)")
                }
            }
        }
        calib = v; wm.addView(v, fullLp(true))
    }

    private fun saveDebug() {
        val f = lastFrame; val sc = lastScan
        if (f == null || sc == null) { toast("Hãy bấm GIẢI trước"); return }
        worker.execute {
            try { val n = Debug.save(this, f, sc); h.post { toast("Đã lưu ảnh: $n") } }
            catch (e: Throwable) { h.post { toast("Lỗi lưu ảnh: ${e.message}") } }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        getSharedPreferences("cfg", 0).edit().putBoolean("running", false).apply()
        worker.shutdownNow(); synchronized(lock) { held?.close(); held = null }; frameThread.quitSafely()
        listOfNotNull(overlay, bar, calib, editor).forEach { runCatching { wm.removeView(it) } }
        vd?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
