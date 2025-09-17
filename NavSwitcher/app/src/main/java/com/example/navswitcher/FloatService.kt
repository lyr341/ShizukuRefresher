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

        // 取屏与检测参数
        private const val DEFAULT_CAPTURE_INTERVAL_MS = 800L
        private const val DETECT_REGION_RATIO = 0.22f   // 右下角 22% × 22%
        private const val DETECT_HIT_RATIO = 0.06f      // 命中像素比例阈值 6%
        private const val SAMPLE_STEP = 3               // 采样步长
        private const val AUTO_COOLDOWN_MS = 2500L      // 自动切换冷却
        private const val CLICK_COOLDOWN_MS = 350L      // 点击防抖

        // ★ 要翻转的一条“无害 overlay”（图标/字体/形状类，视觉基本无感）
        const val OVERLAY_PKG = "com.android.theme.font.notoserifsource"
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
                val hit = detectPinkAtBottomRight(bmp)
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

    /** 执行一次“overlay 翻转（单边变化，只刷新一次）” */
    private fun doToggleOnce() {
        Toast.makeText(this, "检测到浅红按钮，刷新中…", Toast.LENGTH_SHORT).show()

        isOverlayEnabled(OVERLAY_PKG) { enabled ->
            // 只做一次变化：启用或禁用（二选一），不追加第二步，也不重启 statusbar
            val cmd = if (enabled) {
                "cmd overlay disable --user 0 $OVERLAY_PKG"
            } else {
                "cmd overlay enable --user 0 $OVERLAY_PKG"
            }
            execShell(cmd) { code, out, err ->
                Log.d(TAG, "overlay flip exit=$code\nstdout=$out\nstderr=$err")
                if (code == 0) {
                    Toast.makeText(this, "已触发一次刷新", Toast.LENGTH_SHORT).show()
                    // 如极个别 ROM 偶发不触发，可取消注释下一行做一次显式广播（通常不需要）：
                    // execShell("am broadcast -a android.intent.action.CONFIGURATION_CHANGED") {_,_,_->}
                } else {
                    Toast.makeText(this, "刷新失败($code)：$err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 读取 overlay 列表并判断某个 overlay 是否启用（不依赖 grep/wc） */
    private fun isOverlayEnabled(pkg: String, cb: (Boolean) -> Unit) {
        execShell("cmd overlay list") { code, out, err ->
            if (code != 0) {
                Log.w(TAG, "overlay list failed: code=$code, err=$err")
                cb(false)
                return@execShell
            }
            val enabled = out
                .lineSequence()
                .map { it.trim() }
                .any { it.startsWith("[x]") && it.endsWith(pkg) }
            Log.d(TAG, "isOverlayEnabled($pkg) = $enabled")
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
        } fina
