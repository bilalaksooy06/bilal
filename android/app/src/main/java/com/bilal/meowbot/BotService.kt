package com.bilal.meowbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bilal.meowbot.core.Board
import com.bilal.meowbot.core.PixelImage
import com.bilal.meowbot.core.Solver
import com.bilal.meowbot.core.Vision
import com.bilal.meowbot.core.VisionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Erişilebilirlik servisi: ekranda yüzen bir panel gösterir.
 *   🐱  dokun: bu seviyeyi çöz   |  basılı tut: ekranın tespit görüntüsünü Galeri'ye kaydet
 *   🔁  otomatik oyna (seviye bitince 🎯 konumuna dokunup sonrakine geçer) — tekrar dokun: dur
 *   🎯  "Sonraki" butonunun yerini ayarla (hedefi butonun üstüne sürükle, 🎯'a tekrar dokun)
 * Paneli herhangi bir butonundan tutup sürükleyerek taşıyabilirsin.
 */
class BotService : AccessibilityService() {

    companion object {
        private const val TAG = "MeowBot"

        /** Görüntü işleme bu genişliğe küçültülmüş görselde yapılır (hız için). */
        private const val WORK_WIDTH = 720

        @Volatile
        var instance: BotService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var loopButton: TextView? = null
    private var target: TextView? = null
    private var targetParams: WindowManager.LayoutParams? = null

    @Volatile private var busy = false
    @Volatile private var looping = false

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        showPanel()
        toast("MeowBot hazır 🐱 — Meowdoku'yu aç ve 🐱'a dokun")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        looping = false
        removeTarget()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ panel

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun overlayParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun roundButton(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        gravity = Gravity.CENTER
        val size = dp(52)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, dp(3), 0, dp(3)) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(230, 255, 255, 255))
            setStroke(dp(2), Color.rgb(255, 150, 80))
        }
    }

    private fun showPanel() {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.argb(120, 0, 0, 0))
            }
        }
        val params = overlayParams(prefs.panelX, prefs.panelY)

        val cat = roundButton("🐱")
        val loop = roundButton("🔁")
        val aim = roundButton("🎯")
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            maxWidth = dp(64)
            text = "hazır"
        }
        listOf(cat, loop, aim).forEach { p.addView(it) }
        p.addView(status)

        draggable(cat, p, params, onClick = { solveOnceClicked() }, onLongClick = { saveDebugClicked() })
        draggable(loop, p, params, onClick = { loopClicked() })
        draggable(aim, p, params, onClick = { targetClicked() })

        wm.addView(p, params)
        panel = p
        panelParams = params
        statusView = status
        loopButton = loop
    }

    /** Dokununca tıklama, sürükleyince pencereyi taşıma. */
    @SuppressLint("ClickableViewAccessibility")
    private fun draggable(
        handle: View, window: View, params: WindowManager.LayoutParams,
        onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
        onMoved: (() -> Unit)? = null,
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(8) || abs(dy) > dp(8)) {
                        moved = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(window, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        if (window === panel) {
                            prefs.panelX = params.x; prefs.panelY = params.y
                        }
                        onMoved?.invoke()
                    } else if (onLongClick != null && System.currentTimeMillis() - downTime > 600) {
                        onLongClick()
                    } else {
                        onClick()
                    }
                }
            }
            true
        }
    }

    private fun status(text: String) = main.post { statusView?.text = text }

    private fun toast(text: String) = main.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    /** Panel ve hedefi gizle/göster (ekran görüntüsüne girmesin, dokunuşları engellemesin). */
    private fun setOverlaysVisible(visible: Boolean) {
        val latch = CountDownLatch(1)
        main.post {
            val v = if (visible) View.VISIBLE else View.INVISIBLE
            fun apply(view: View?, params: WindowManager.LayoutParams?) {
                if (view == null || params == null) return
                view.visibility = v
                // Gizliyken dokunuşları alta geçir (oyuna gitsin)
                params.flags = if (visible) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(view, params) }
            }
            apply(panel, panelParams)
            apply(target, targetParams)
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
        if (!visible) Thread.sleep(150) // ekranın yeniden çizilmesini bekle
    }

    // ------------------------------------------------------------------ butonlar

    private fun solveOnceClicked() {
        if (busy) return
        if (looping) {
            toast("Önce otomatik modu durdur (🔁)")
            return
        }
        thread(name = "meowbot") {
            busy = true
            try {
                playLevel(null)
            } finally {
                busy = false
            }
        }
    }

    private fun saveDebugClicked() {
        if (busy) return
        thread(name = "meowbot-debug") {
            busy = true
            try {
                setOverlaysVisible(false)
                val shot = capture()
                setOverlaysVisible(true)
                if (shot == null) {
                    toast("Ekran görüntüsü alınamadı"); return@thread
                }
                val board = runCatching { Vision.readBoard(shot.image) }.getOrNull()
                val cats = board?.let { b -> runCatching { Solver.solve(b.regions) }.getOrNull() }
                saveDebugImage(shot.bitmap, board, cats)
            } finally {
                busy = false
            }
        }
    }

    private fun loopClicked() {
        if (looping) {
            looping = false
            status("duruyor…")
            main.post { loopButton?.text = "🔁" }
            return
        }
        if (busy) return
        if (!prefs.hasNext) {
            toast("Önce 🎯 ile 'Sonraki' butonunun yerini ayarla")
            return
        }
        looping = true
        main.post { loopButton?.text = "⏹" }
        thread(name = "meowbot-loop") {
            busy = true
            try {
                runLoop()
            } finally {
                busy = false
                looping = false
                main.post { loopButton?.text = "🔁" }
            }
        }
    }

    private fun targetClicked() {
        if (target != null) {
            // Kaydet: hedefin merkezi
            val t = target!!
            val loc = IntArray(2)
            t.getLocationOnScreen(loc)
            prefs.nextX = loc[0] + t.width / 2
            prefs.nextY = loc[1] + t.height / 2
            removeTarget()
            toast("Sonraki butonu kaydedildi (${prefs.nextX}, ${prefs.nextY})")
            return
        }
        val t = TextView(this).apply {
            text = "⊕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
            val size = dp(64)
            layoutParams = LinearLayout.LayoutParams(size, size)
            minWidth = size
            minHeight = size
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(60, 255, 0, 0))
                setStroke(dp(2), Color.RED)
            }
        }
        val dm = resources.displayMetrics
        val half = dp(32)
        val x = if (prefs.hasNext) prefs.nextX - half else dm.widthPixels / 2 - half
        val y = if (prefs.hasNext) prefs.nextY - half else (dm.heightPixels * 0.8).toInt() - half
        val params = overlayParams(x, y)
        draggable(t, t, params, onClick = { targetClicked() })
        wm.addView(t, params)
        target = t
        targetParams = params
        toast("Kırmızı hedefi seviye sonundaki 'Sonraki' butonunun üstüne sürükle, sonra 🎯'a dokun")
    }

    private fun removeTarget() {
        target?.let { runCatching { wm.removeView(it) } }
        target = null
        targetParams = null
    }

    // ------------------------------------------------------------------ oyun

    private class Shot(val bitmap: Bitmap, val image: PixelImage, val scale: Float)

    /** Ekran görüntüsü al; işleme için küçült. Arka plan iş parçacığından çağrılmalı. */
    private fun capture(): Shot? {
        repeat(3) { attempt ->
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            var error = 0
            main.post {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(res: AccessibilityService.ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                            result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                        } finally {
                            res.hardwareBuffer.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(code: Int) {
                        error = code
                        latch.countDown()
                    }
                })
            }
            latch.await(5, TimeUnit.SECONDS)
            val full = result
            if (full != null) {
                val scale = if (full.width > WORK_WIDTH) WORK_WIDTH.toFloat() / full.width else 1f
                val small = if (scale < 1f) {
                    Bitmap.createScaledBitmap(full, WORK_WIDTH, (full.height * scale).toInt(), true)
                } else full
                if (small !== full) full.recycle()
                val px = IntArray(small.width * small.height)
                small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                return Shot(small, PixelImage(small.width, small.height, px), scale)
            }
            Log.w(TAG, "takeScreenshot hata kodu $error (deneme ${attempt + 1})")
            Thread.sleep(400)
        }
        return null
    }

    private fun gesture(g: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            val sent = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    ok = true; latch.countDown()
                }

                override fun onCancelled(d: GestureDescription?) {
                    latch.countDown()
                }
            }, main)
            if (!sent) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    private fun stroke(x: Float, y: Float, start: Long, duration: Long): GestureDescription.StrokeDescription {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        return GestureDescription.StrokeDescription(path, start, duration)
    }

    private fun tap(x: Float, y: Float, duration: Long = 40) =
        gesture(GestureDescription.Builder().addStroke(stroke(x, y, 0, duration)).build())

    private fun placeCat(x: Float, y: Float) {
        when (prefs.tapMode) {
            "single" -> tap(x, y)
            "long" -> tap(x, y, 650)
            "twice" -> {
                tap(x, y); Thread.sleep(180); tap(x, y)
            }
            else -> gesture( // gerçek çift dokunma: tek hareket içinde iki dokunuş
                GestureDescription.Builder()
                    .addStroke(stroke(x, y, 0, 40))
                    .addStroke(stroke(x, y, 130, 40))
                    .build()
            )
        }
    }

    /**
     * Bir seviyeyi oynar. Dönüş: çözülen tahta; okunamadı/çözülemedi/aynı tahta ise null.
     */
    private fun playLevel(previous: Board?): Board? {
        status("bakıyor…")
        setOverlaysVisible(false)
        try {
            val shot = capture() ?: run {
                status("ekran alınamadı"); return null
            }
            val board = try {
                Vision.readBoard(shot.image)
            } catch (e: VisionException) {
                status("tahta yok")
                if (!looping) {
                    toast("Tahta bulunamadı. Görüntü Galeri > Pictures/MeowBot'a kaydedildi")
                    saveDebugImage(shot.bitmap, null, null)
                }
                return null
            }
            if (board.sameRegionsAs(previous)) {
                status("yeni seviye bekleniyor")
                return null
            }
            val cats = runCatching { Solver.solve(board.regions) }.getOrNull()
            if (cats == null) {
                status("çözülemedi")
                toast("Tahta yanlış okundu (${board.size}x${board.size}). Görüntü Galeri'ye kaydedildi")
                saveDebugImage(shot.bitmap, board, null)
                return null
            }
            status("${board.size}x${board.size} 🐾")
            for (cat in cats) {
                if (previous != null && !looping) break
                val (x, y) = board.cellCenter(cat.row, cat.col)
                placeCat(x / shot.scale, y / shot.scale)
                Thread.sleep(prefs.moveDelayMs)
            }
            shot.bitmap.recycle()
            status("✓ çözüldü")
            return board
        } finally {
            setOverlaysVisible(true)
        }
    }

    private fun runLoop() {
        var last: Board? = null
        var solved = 0
        var fails = 0
        while (looping) {
            val board = playLevel(last)
            if (!looping) break
            if (board != null) {
                last = board
                solved++
                fails = 0
                status("✓ $solved seviye")
                Thread.sleep(prefs.afterSolveMs)
            } else {
                fails++
                if (fails >= 8) {
                    toast("Üst üste 8 kez tahta okunamadı, otomatik mod durdu")
                    break
                }
            }
            if (!looping) break
            setOverlaysVisible(false)
            tap(prefs.nextX.toFloat(), prefs.nextY.toFloat())
            setOverlaysVisible(true)
            Thread.sleep(2000)
        }
        status("durdu ($solved)")
    }

    // ------------------------------------------------------------------ hata ayıklama

    /** Tespit sonucunu ekran görüntüsünün üstüne çizip Galeri'ye (Pictures/MeowBot) kaydeder. */
    private fun saveDebugImage(src: Bitmap, board: Board?, cats: List<Solver.Cell>?) {
        try {
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            if (board != null) {
                val c = Canvas(bmp)
                val red = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
                val thin = Paint().apply { color = Color.RED; strokeWidth = 1f }
                val text = Paint().apply { color = Color.BLACK; textSize = 14f; isAntiAlias = true }
                val b = board.box
                c.drawRect(b.x1.toFloat(), b.y1.toFloat(), b.x2.toFloat(), b.y2.toFloat(), red)
                val cw = b.width.toFloat() / board.size
                val ch = b.height.toFloat() / board.size
                for (k in 1 until board.size) {
                    c.drawLine(b.x1 + k * cw, b.y1.toFloat(), b.x1 + k * cw, b.y2.toFloat(), thin)
                    c.drawLine(b.x1.toFloat(), b.y1 + k * ch, b.x2.toFloat(), b.y1 + k * ch, thin)
                }
                for (r in 0 until board.size) for (col in 0 until board.size) {
                    val (x, y) = board.cellCenter(r, col)
                    c.drawText(board.regions[r][col].toString(), x - 4, y + 5, text)
                }
                cats?.forEach { cat ->
                    val (x, y) = board.cellCenter(cat.row, cat.col)
                    c.drawCircle(x, y, minOf(cw, ch) * 0.3f, red)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "meowbot_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MeowBot")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            toast("Kaydedildi: Galeri > Pictures/MeowBot")
        } catch (e: Exception) {
            Log.e(TAG, "Görüntü kaydedilemedi", e)
        }
    }
}
