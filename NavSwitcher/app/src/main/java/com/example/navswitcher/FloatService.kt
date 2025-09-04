package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
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

        // 外部控制 Action/Extra（MainActivity 会用到）
        const val ACTION_SET_INTERVAL = "com.example.navswitcher.SET_INTERVAL"
        const val ACTION_HIDE_BALL   = "com.example.navswitcher.HIDE_BALL"
        const val ACTION_SHOW_BALL   = "com.example.navswitcher.SHOW_BALL"
        const val EXTRA_INTERVAL_MS  = "interval_ms"

        // 取屏与检测参数（可通过 ACTION_SET_INTERVAL 动态改频率）
        private const val DEFAULT_CAPTURE_INTERVAL_MS = 800L
        private const val DETECT_REGION_RATIO = 0.22f   // 右下角 22% × 22%
        private const val DETECT_HIT_RATIO = 0.06f      // 命中像素比例阈值 6%
        private const val SAMPLE_STEP = 3               // 采样步长
        private const val AUTO_COOLDOWN_MS = 2500L      // 自动切换冷却
        private const val CLICK_COOLDOWN_MS = 350L      // 点击防抖
    }

    // 主/IO 线程
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var captureExec: ScheduledExecutorService? = null

    // 悬浮按钮相关
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: FrameLayout? = null
    private var touchSlop: Int = 12
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var lastClickTs = 0L

    // 识别状态
    @Volatile private var isCapturing = false
    @Volatile private var lastTriggerTs = 0L
    private var captureIntervalMs: Long = DEFAULT_CAPTURE_INTERVAL_MS

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
            ACTION_SET_INTERVAL -> {
                val ms = intent.getLongExtra(EXTRA_INTERVAL_MS, captureIntervalMs)
                captureIntervalMs = ms.coerceIn(50L, 5000L)
                if (isCapturing) {
                    stopCaptureLoop()
                    startCaptureLoop()
                }
                Toast.makeText(this, "截图频率已设为 ${captureIntervalMs}ms", Toast.LENGTH_SHORT).show()
            }
            ACTION_HIDE_BALL -> {
                ball?.visibility = View.GONE
                Toast.makeText(this, "已隐藏悬浮球", Toast.LENGTH_SHORT).show()
            }
            ACTION_SHOW_BALL -> {
                ball?.visibility = View.VISIBLE
                Toast.makeText(this, "已显示悬浮球", Toast.LENGTH_SHORT).show()
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
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sizePx = (56 * resources.displayMetrics.density).toInt() // ≈56dp 的大圆
        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
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

        // 纯圆形灰色按钮，整个圆即点击区域
        ball = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#808A8A8A")) // 半透明灰
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
                            lastRawX = ev.rawX
                            lastRawY = ev.rawY
                        }
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

    // 点击：开始/停止取屏识别
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
            Color.parseColor(if (active) "#8096C8FF" else "#808A8A8A") // 激活淡蓝，未激活灰
        )
    }

    private fun startCaptureLoop() {
        if (captureExec != null) return
        captureExec = Executors.newSingleThreadScheduledExecutor()
        captureExec?.scheduleAtFixedRate({
            try {
                val png = captureScreenPng() ?: return@scheduleAtFixedRate
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@scheduleAtFixedRate
                val hit = detectLightRedAtBottomRight(bmp)
                bmp.recycle()
                if (hit) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTriggerTs >= AUTO_COOLDOWN_MS) {
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

    /** 执行一次“手势/三键”切换 */
    private fun doToggleOnce() {
        Toast.makeText(this, "检测到浅红按钮，正在切换…", Toast.LENGTH_SHORT).show()
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

    /** screencap -p 获取整屏 PNG 字节 */
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
            try { proc.waitFor(1500, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            out.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "screencap read error", t)
            null
        } finally {
            try { proc.destroy() } catch (_: Throwable) {}
        }
    }

    /** 在右下角区域检测“浅红色按钮” —— 大红色排除，浅红色命中 */
    private fun detectLightRedAtBottomRight(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false

        // 只取右下角一个子区域
        val rx = (w * (1f - DETECT_REGION_RATIO)).toInt()
        val ry = (h * (1f - DETECT_REGION_RATIO)).toInt()
        val hsv = FloatArray(3)

        var hit = 0
        var total = 0

        var y = ry
        while (y < h) {
            var x = rx
            while (x < w) {
                val c = bmp.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]       // 0..360
                val sat = hsv[1]       // 0..1
                val valv = hsv[2]      // 0..1

                val redHue = (hue <= 20f || hue >= 340f)
                val isLight = valv >= 0.78f
                val isSaturated = sat >= 0.35f

                // 排除“深红（很暗/很饱和）”
                val isDarkRed = valv <= 0.55f
                val isVerySaturated = sat >= 0.80f

                if (redHue && isLight && isSaturated && !isDarkRed && !isVerySaturated) {
                    hit++
                }
                total++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        if (total == 0) return false
        val ratio = hit.toFloat() / total
        val matched = ratio >= DETECT_HIT_RATIO
        Log.d(TAG, "detect light-red: hit=$hit total=$total ratio=${"%.3f".format(ratio)} matched=$matched")
        return matched
    }

    // 统一的 shell 执行（Shizuku 优先，失败回退 Runtime）
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
            val stdout = out
            val stderr = err
            val exit = code
            main.post { cb(exit, stdout, stderr) }
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
