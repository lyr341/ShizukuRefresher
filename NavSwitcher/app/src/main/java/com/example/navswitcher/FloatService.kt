package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.Executors

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"
    }

    // 冷却与状态
    private val COOLDOWN_MS = 600L
    @Volatile private var isToggling = false
    @Volatile private var lastToggleAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: ImageView? = null
    private var lastRawX = 0f
    private var lastRawY = 0f

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
            .setContentText("点击可切换导航模式")
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
            x = 200
            y = 600
        }

        ball = ImageView(this).apply {
            setImageResource(android.R.drawable.btn_star_big_on)
            scaleX = 2.0f
            scaleY = 2.0f

            isClickable = true
            setOnClickListener { onBallClicked() }

            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.let { p ->
                            val dx = (ev.rawX - lastRawX).toInt()
                            val dy = (ev.rawY - lastRawY).toInt()
                            p.x += dx
                            p.y += dy
                            wm?.updateViewLayout(v, p)
                            lastRawX = ev.rawX
                            lastRawY = ev.rawY
                        }
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }

        wm?.addView(ball, params)
    }

    private fun onBallClicked() {
        val now = SystemClock.elapsedRealtime()
        if (isToggling || now - lastToggleAt < COOLDOWN_MS) {
            // 防抖中，直接忽略
            return
        }
        isToggling = true
        lastToggleAt = now

        // 点击期间视觉反馈与禁点
        ball?.isEnabled = false
        ball?.alpha = 0.6f

        Log.d(TAG, "ball clicked")
        Toast.makeText(this, "准备切换…", Toast.LENGTH_SHORT).show()

        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Shizuku not running")
            resetToggleUiState()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            Log.w(TAG, "No Shizuku permission")
            resetToggleUiState()
            return
        }

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
                // 不论成功失败，都要复位冷却与UI状态（从完成时刻起算冷却）
                resetToggleUiState()
                lastToggleAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun resetToggleUiState() {
        isToggling = false
        ball?.isEnabled = true
        ball?.alpha = 1f
    }

    private fun isThreeButtonEnabled(cb: (Boolean) -> Unit) {
        val cmd = "cmd overlay list | grep '\\[x\\] com.android.internal.systemui.navbar.threebutton' | wc -l"
        execShell(cmd) { code, out, _ ->
            val enabled = (code == 0) && (out.trim().toIntOrNull() ?: 0) > 0
            Log.d(TAG, "threebutton enabled? $enabled, raw=$out, code=$code")
            cb(enabled)
        }
    }

    // 执行 shell：优先用 Shizuku.newProcess，失败则回退 Runtime.exec
    private fun execShell(cmd: String, cb: (code: Int, stdout: String, stderr: String) -> Unit) {
        io.execute {
            var code = -1
            var out = ""
            var err = ""
            try {
                val proc: java.lang.Process = if (newProcessMethod != null) {
                    @Suppress("UNCHECKED_CAST")
                    newProcessMethod!!.invoke(
                        null,
                        arrayOf("sh", "-c", cmd),
                        null,
                        null
                    ) as java.lang.Process
                } else {
                    // 回退（非 Shizuku shell）
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
                code = proc.waitFor()
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
        io.shutdownNow()
    }

    override fun onBind(intent: Intent?) = null
}
