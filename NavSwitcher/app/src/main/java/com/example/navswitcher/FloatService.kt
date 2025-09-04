package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.lang.Math.abs
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"

        // 取屏与检测参数
        private const val DEFAULT_CAPTURE_INTERVAL_MS = 200L  // 默认 200ms
        private const val DETECT_REGION_RATIO = 0.22f
        private const val DETECT_HIT_RATIO = 0.06f
        private const val COOLDOWN_MS = 2500L
        private const val SAMPLE_STEP = 3

        private const val CLICK_COOLDOWN_MS = 700L

        // === 提供给 Activity 的指令常量 ===
        const val ACTION_HIDE_BALL = "com.example.navswitcher.action.HIDE_BALL"
        const val ACTION_SHOW_BALL = "com.example.navswitcher.action.SHOW_BALL"
        const val ACTION_SET_INTERVAL = "com.example.navswitcher.action.SET_INTERVAL"
        const val EXTRA_INTERVAL_MS = "interval_ms"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var captureExec: ScheduledExecutorService? = null

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: FrameLayout? = null

    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var lastClickTs = 0L
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    @Volatile private var isCapturing = false
    @Volatile private var lastTriggerTs = 0L
    @Volatile private var captureIntervalMs: Long = DEFAULT_CAPTURE_INTERVAL_MS

    // 反射拿 Shizuku.newProcess(String[], String[], String)
    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
        } catch (_: Throwable) {
            try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.w(TAG, "Shizuku.newProcess not found via reflection: $t")
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundX()
            addBallX()
            Log.d(TAG, "onCreate OK, ball added")
            Toast.makeText(this, "悬浮球服务已启动", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate error", t)
            Toast.makeText(this, "悬浮球启动失败: ${t.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_BALL -> {
                ball?.visibility = View.GONE
                Toast.makeText(this, "已隐藏悬浮球", Toast.LENGTH_SHORT).show()
            }
            ACTION_SHOW_BALL -> {
                ball?.visibility = View.VISIBLE
                Toast.makeText(this, "已显示悬浮球", Toast.LENGTH_SHORT).show()
            }
            ACTION_SET_INTERVAL -> {
                val v = intent.getLongExtra(EXTRA_INTERVAL_MS, captureIntervalMs)
                if (v > 0) {
                    captureIntervalMs = v
                    if (isCapturing) {
                        // 正在采集：重启采集循环以应用新频率
                        stopCaptureLoop()
                        startCaptureLoop()
                    }
                    Toast.makeText(this, "已设置截屏频率 ${captureIntervalMs}ms", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundX() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "NavSwitcher", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val noti = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("悬浮球已启用")
            .setContentText("点击开始/停止取屏识别")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun addBallX() {
        if (!Settings.canDrawOverlays(this)) {
            throw IllegalStateException("未授予悬浮窗权限")
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 180
            y = 520
        }

        val sizePx = (56 * resources.displayMetrics.density).toInt()  // ≈56dp 的大圆
        ball = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.GRAY)
            }
            isClickable = true
            isFocusable = false

            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                        isDragging = false
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - lastRawX).toInt()
                        val dy = (ev.rawY - lastRawY).toInt()
                        if (!isDragging && (dx * dx + dy * dy) > touchSlop * touchSlop) {
                            isDragging = true
                        }
                        params?.let { p ->
                            p.x += dx
                            p.y += dy
                            wm?.updateViewLayout(v, p)
                        }
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastClickTs >= CLICK_COOLDOWN_MS) {
                                lastClickTs = now
                                onBallClickedToggleCapture()
                            }
                        }
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }

        wm?.addView(ball, params)
    }

    private fun onBallClickedToggleCapture() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            return
        }
        isCapturing = !isCapturing
        if (isCapturing) {
            startCaptureLoop()
            Toast.makeText(this, "开始取屏识别", Toast.LENGTH_SHORT).show()
            setBallActive(true)
        } else {
            stopCaptureLoop()
            Toast.makeText(this, "已停止取屏", Toast.LENGTH_SHORT).show()
            setBallActive(false)
        }
    }

    private fun setBallActive(active: Boolean) {
        (ball?.background as? GradientDrawable)?.setColor(
            if (active) Color.parseColor("#8096C8FF") else Color.GRAY
        )
    }

    private fun startCaptureLoop() {
        if (captureExec != null) return
        captureExec = Executors.newSingleThreadScheduledExecutor()
        captureExec?.scheduleAtFixedRate({
            try {
                val png = captureScreenPng() ?: return@scheduleAtFixedRate
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@scheduleAtFixedRate

                val pinkBR = detectPinkAtBottomRight(bmp)
                val blueTR = detectBlueAtTopRight(bmp)
                bmp.recycle()

                if (pinkBR && blueTR) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTriggerTs >= COOLDOWN_MS) {
                        lastTriggerTs = now
                        main.post { doToggleOnce() }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture/detect error", t)
            }
        }, 0, captureIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun stopCaptureLoop() {
        captureExec?.shutdownNow()
        captureExec = null
    }

    private fun doToggleOnce() {
        Toast.makeText(this, "检测到目标，正在切换…", Toast.LENGTH_SHORT).show()
        isThreeButtonEnabled { isThree ->
            val cmds = if (!isThree) {
                listOf(
                    "cmd overlay enable com.android.internal.systemui.navbar.threebutton",
                    "cmd overlay disable com.android.internal.systemui.navbar.gestural",
                    "cmd statusbar restart"
                )
            } else {
                listOf(
                    "cmd overlay enable com.android.internal.systemui.navbar.gestural",
                    "cmd overlay disable com.android.internal.systemui.navbar.threebutton",
                    "cmd statusbar restart"
                )
            }
            execShell(cmds.joinToString(" && ")) { code, out, err ->
                Log.d(TAG, "toggle exit=$code\nstdout=$out\nstderr=$err")
                if (code == 0) {
                    Toast.makeText(this, "切换完成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "切换失败($code)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isThreeButtonEnabled(cb: (Boolean) -> Unit) {
        val cmd = "cmd overlay list | grep '\\[x\\] com.android.internal.systemui.navbar.threebutton' | wc -l"
        execShell(cmd) { code, out, _ ->
            val enabled = (code == 0) && (out.trim().toIntOrNull() ?: 0) > 0
            Log.d(TAG, "threebutton enabled? $enabled, raw=$out, code=$code")
            cb(enabled)
        }
    }

    /** screencap -p 获取整屏 PNG 字节（使用 java.lang.Process） */
    private fun captureScreenPng(): ByteArray? {
        if (!isCapturing) return null
        val proc: java.lang.Process? = try {
            if (newProcessMethod != null) {
                @Suppress("UNCHECKED_CAST")
                newProcessMethod!!.invoke(
                    null,
                    arrayOf("sh", "-c", "screencap -p"),
                    null,
                    null
                ) as java.lang.Process
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "screencap spawn error", t)
            null
        }
        proc ?: return null

        return try {
            val out = ByteArrayOutputStream(2 * 1024 * 1024)
            val buf = ByteArray(32 * 1024)
            val ins = proc.inputStream
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            try { proc.waitFor() } catch (_: Throwable) {}
            out.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "screencap read error", t)
            null
        } finally {
            try { proc.destroy() } catch (_: Throwable) {}
        }
    }

    // 右下：粉红（浅红/粉，排除大红）
    private fun detectPinkAtBottomRight(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false

        val rx = (w * (1f - DETECT_REGION_RATIO)).toInt()
        val ry = (h * (1f - DETECT_REGION_RATIO)).toInt()
        val rw = w - rx
        val rh = h - ry
        if (rw <= 0 || rh <= 0) return false

        var hit = 0
        var total = 0
        val hsv = FloatArray(3)

        val PINK_HUE_LOW1 = 330f
        val PINK_HUE_HIGH1 = 360f
        val PINK_HUE_LOW2 = 0f
        val PINK_HUE_HIGH2 = 10f
        val PINK_SAT_MIN = 0.12f
        val PINK_SAT_MAX = 0.55f
        val PINK_VAL_MIN = 0.82f

        var y = ry
        while (y < h) {
            var x = rx
            while (x < w) {
                val c = bmp.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valv = hsv[2]

                val isHuePinkBand = ((hue >= PINK_HUE_LOW1 && hue <= PINK_HUE_HIGH1) ||
                        (hue >= PINK_HUE_LOW2 && hue <= PINK_HUE_HIGH2))

                var isPink = false
                if (isHuePinkBand && sat in PINK_SAT_MIN..PINK_SAT_MAX && valv >= PINK_VAL_MIN) {
                    isPink = true
                }

                if (isPink) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val looksStrongRed = (r >= 200 && g <= 80 && b <= 80)      // 大红，排除
                    val looksPinkish = (r >= 180 && g >= 100 && b >= 120)      // 有粉感
                    if (looksStrongRed || !looksPinkish) isPink = false
                }

                if (isPink) hit++
                total++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        if (total == 0) return false
        val ratio = hit.toFloat() / total
        val matched = ratio >= DETECT_HIT_RATIO
        Log.d(TAG, "detect pink BR: hit=$hit total=$total ratio=${"%.3f".format(ratio)} matched=$matched")
        return matched
    }

    // 右上：蓝色
    private fun detectBlueAtTopRight(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false

        val rx = (w * (1f - DETECT_REGION_RATIO)).toInt()
        val ry = 0
        val rw = w - rx
        val rh = (h * DETECT_REGION_RATIO).toInt()
        if (rw <= 0 || rh <= 0) return false

        var hit = 0
        var total = 0
        val hsv = FloatArray(3)

        val BLUE_H_MIN = 190f
        val BLUE_H_MAX = 250f
        val BLUE_S_MIN = 0.35f
        val BLUE_V_MIN = 0.70f

        val yEnd = ry + rh
        var y = ry
        while (y < yEnd) {
            var x = rx
            while (x < w) {
                val c = bmp.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val h1 = hsv[0]
                val s = hsv[1]
                val v = hsv[2]

                var isBlue = (h1 in BLUE_H_MIN..BLUE_H_MAX && s >= BLUE_S_MIN && v >= BLUE_V_MIN)
                if (isBlue) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val blueDominant = b > r + 25 && b > g + 10
                    if (!blueDominant) isBlue = false
                }

                if (isBlue) hit++
                total++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        if (total == 0) return false
        val ratio = hit.toFloat() / total
        val matched = ratio >= DETECT_HIT_RATIO
        Log.d(TAG, "detect blue TR: hit=$hit total=$total ratio=${"%.3f".format(ratio)} matched=$matched")
        return matched
    }

    private fun execShell(cmd: String, cb: (code: Int, stdout: String, stderr: String) -> Unit) {
        io.execute {
            var code = -1
            var out = ""
            var err = ""
            try {
                val jproc: java.lang.Process =
                    if (newProcessMethod != null) {
                        @Suppress("UNCHECKED_CAST")
                        newProcessMethod!!.invoke(
                            null,
                            arrayOf("sh", "-c", cmd),
                            null,
                            null
                        ) as java.lang.Process
                    } else {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    }

                val sbOut = StringBuilder()
                val sbErr = StringBuilder()
                BufferedReader(InputStreamReader(jproc.inputStream)).use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        sbOut.appendLine(line)
                    }
                }
                BufferedReader(InputStreamReader(jproc.errorStream)).use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        sbErr.appendLine(line)
                    }
                }

                jproc.waitFor()
                code = jproc.exitValue()
                out = sbOut.toString()
                err = sbErr.toString()
            } catch (t: Throwable) {
                err = t.message ?: t.toString()
                Log.e(TAG, "execShell error", t)
            }
            main.post { cb(code, out, err) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm?.removeView(ball) } catch (_: Throwable) {}
        wm = null; ball = null; params = null
        stopCaptureLoop()
        io.shutdownNow()
    }

    override fun onBind(intent: Intent?) = null
}
