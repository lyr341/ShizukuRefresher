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
import android.widget.ImageView
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

        // 取屏与检测的节奏/阈值
        private const val CAPTURE_INTERVAL_MS = 800L
        private const val DETECT_REGION_RATIO = 0.22f     // 右下角取 22%×22% 区域
        private const val DETECT_HIT_RATIO = 0.06f        // 命中像素比例阈值 6%
        private const val COOLDOWN_MS = 2500L             // 自动切换冷却 2.5s
        private const val SAMPLE_STEP = 3                 // 像素采样步长（越大越省电）
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var captureExec: ScheduledExecutorService? = null

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: ImageView? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false

    @Volatile private var isCapturing = false
    @Volatile private var lastTriggerTs = 0L

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

        // 圆形灰色按钮（点击区域即圆）
        val sizePx = (56 * resources.displayMetrics.density).toInt() // ~56dp
        ball = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#808A8A8A")) // 半透明灰
            }
            // 中心放个小点，作为视觉反馈（可要可不要）
            setImageBitmap(makeDotBitmap((sizePx * 0.3f).toInt(), Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER

            isClickable = true
            setOnClickListener { onBallClickedToggleCapture() }

            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - lastRawX
                        val dy = ev.rawY - lastRawY
                        if (!isDragging && (abs(dx) > 6 || abs(dy) > 6)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params?.let { p ->
                                p.x += dx.toInt()
                                p.y += dy.toInt()
                                wm?.updateViewLayout(v, p)
                                lastRawX = ev.rawX
                                lastRawY = ev.rawY
                            }
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) return@setOnTouchListener true // 拖拽不当点击
                    }
                }
                false
            }
        }

        wm?.addView(ball, params)
    }

    private fun makeDotBitmap(diameter: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawCircle(diameter / 2f, diameter / 2f, diameter / 2f, p)
        return bmp
    }

    // 点击：开始/停止取屏识别
    private fun onBallClickedToggleCapture() {
        if (isDragging) return
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
            Color.parseColor(if (active) "#8096C8FF" else "#808A8A8A") // 激活时淡蓝，未激活灰
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
                    if (now - lastTriggerTs >= COOLDOWN_MS) {
                        lastTriggerTs = now
                        main.post {
                            // 命中：执行切换
                            doToggleOnce()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture/detect error", t)
            }
        }, 0, CAPTURE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopCaptureLoop() {
        captureExec?.shutdownNow()
        captureExec = null
    }

    /** 执行一次“手势/三键”切换（沿用你之前的逻辑） */
    private fun doToggleOnce() {
        Toast.makeText(this, "检测到按钮，正在切换…", Toast.LENGTH_SHORT).show()
        // 简化：尝试切到三键；若已是三键则切回手势
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
        val proc: Process? = try {
            if (newProcessMethod != null) {
                @Suppress("UNCHECKED_CAST")
                newProcessMethod!!.invoke(
                    null,
                    arrayOf("sh", "-c", "screencap -p"),
                    null,
                    null
                ) as Process
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p"))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "screencap spawn error", t)
            null
        }
        proc ?: return null

        return try {
            // 读 stdout 全部 PNG
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

    /** 在右下角区域检测“浅红色按钮” */
    private fun detectLightRedAtBottomRight(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false

        // 只取右下角一个子区域
        val rx = (w * (1f - DETECT_REGION_RATIO)).toInt()
        val ry = (h * (1f - DETECT_REGION_RATIO)).toInt()
        val rw = w - rx
        val rh = h - ry
        if (rw <= 0 || rh <= 0) return false

        var hit = 0
        var total = 0

        val hsv = FloatArray(3)

        var y = ry
        while (y < h) {
            var x = rx
            while (x < w) {
                val c = bmp.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val sat = hsv[1]
                val valv = hsv[2]
                val hue = hsv[0]            // 0..360

                // 认为是“红色附近”：hue 在 [0..20] 或 [340..360]
                val redHue = (hue <= 20f || hue >= 340f)
                // “浅”：亮度高 & 饱和度中等以上
                if (redHue && sat >= 0.35f && valv >= 0.75f) {
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
        Log.d(TAG, "detect red: hit=$hit total=$total ratio=${"%.3f".format(ratio)} matched=$matched")
        return matched
    }

    /** 通用 shell 执行 */
    private fun execShell(cmd: String, cb: (code: Int, stdout: String, stderr: String) -> Unit) {
        io.execute {
            var code = -1
            var out = ""
            var err = ""
            try {
                val proc: Process = if (newProcessMethod != null) {
                    @Suppress("UNCHECKED_CAST")
                    newProcessMethod!!.invoke(
                        null,
                        arrayOf("sh", "-c", cmd),
                        null,
                        null
                    ) as Process
                } else {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                }

                val sbOut = StringBuilder()
                val sbErr = StringBuilder()
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        sbOut.appendLine(line)
                    }
                }
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        sbErr.appendLine(line)
                    }
                }
                proc.waitFor()
                code = proc.exitValue()
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
