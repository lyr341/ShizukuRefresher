package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"

        // 节奏/阈值（更敏感地识别“浅红”）
        private const val CAPTURE_INTERVAL_MS = 700L
        private const val DETECT_REGION_RATIO = 0.22f
        private const val DETECT_HIT_RATIO = 0.035f     // 3.5% 命中像素比例即可触发
        private const val COOLDOWN_MS = 2500L
        private const val SAMPLE_STEP = 2               // 降低步长：提升灵敏度
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
            x = 160
            y = 480
        }

        // —— 真·大圆形按钮（~80dp），半透明灰 + 浅白描边，点击区域=整圆 ——
        val sizePx = (80 * resources.displayMetrics.density).toInt()
        val strokePx = (2 * resources.displayMetrics.density).toInt()
        ball = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC808080")) // 更实一点的灰（80%不透明）
                setStroke(strokePx, Color.parseColor("#66FFFFFF")) // 浅白描边更显眼
            }
            // 不再放中心小点，避免看起来像“小点”
            setImageDrawable(null)

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
                        if (isDragging) return@setOnTouchListener true
                    }
                }
                false
            }
        }

        wm?.addView(ball, params)
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
            Color.parseColor(if (active) "#CC96C8FF" else "#CC808080") // 激活淡蓝 / 未激活灰
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
                        main.post { doToggleOnce() }
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

        val jproc: java.lang.Process? = try {
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
        jproc ?: return null

        return try {
            val out = ByteArrayOutputStream(2 * 1024 * 1024)
            val buf = ByteArray(32 * 1024)

            jproc.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            try { jproc.waitFor(1500, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            out.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "screencap read error", t)
            null
        } finally {
            try { jproc.destroy() } catch (_: Throwable) {}
        }
    }

    /** 只匹配“浅红”，排除“大红” */
    private fun detectLightRedAtBottomRight(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false

        // 只取右下角区域
        val rx = (w * (1f - DETECT_REGION_RATIO)).toInt()
        val ry = (h * (1f - DETECT_REGION_RATIO)).toInt()
        if (w - rx <= 0 || h - ry <= 0) return false

        var hit = 0
        var total = 0
        val hsv = FloatArray(3)

        var y = ry
        while (y < h) {
            var x = rx
            while (x < w) {
                val c = bmp.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                val hue = hsv[0]    // 0..360
                val sat = hsv[1]    // 0..1
                val valv = hsv[2]   // 0..1

                val isRedHue = (hue <= 20f || hue >= 340f)
                // “浅红”：亮度高、饱和度中低，避免深红/大红（高饱和 + 低/中亮度）
                val isLightRed =
                    isRedHue &&
                    valv >= 0.88f &&            // 很亮
                    sat in 0.18f..0.55f         // 中低饱和
                val isDeepRed =
                    isRedHue &&
                    (sat >= 0.60f || valv <= 0.80f) // 大红：高饱或偏暗

                if (isLightRed && !isDeepRed) hit++

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

    // Shell 执行
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
