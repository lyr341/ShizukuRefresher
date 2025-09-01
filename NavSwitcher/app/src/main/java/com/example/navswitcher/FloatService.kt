package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        Log.d(TAG, "ball clicked")
        Toast.makeText(this, "切换中…", Toast.LENGTH_SHORT).show()
        toggleNavMode()
    }

    // 一次脚本内完成“判断+切换”，减少延迟
    private fun toggleNavMode() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            return
        }

        val script = """
            if cmd overlay list | grep -q '\[x\] com.android.internal.systemui.navbar.threebutton'; then
              cmd overlay enable-exclusive com.android.internal.systemui.navbar.gestural >/dev/null 2>&1
            else
              cmd overlay enable-exclusive com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1
            fi
            # 如需强制刷新，可去掉下一行注释，但会增加切换时间
            # cmd statusbar restart >/dev/null 2>&1
        """.trimIndent()

        execShell(script) { code, _, err ->
            if (code == 0) {
                Toast.makeText(this, "已切换", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "切换失败($code)", Toast.LENGTH_LONG).show()
                if (err.isNotBlank()) Log.e(TAG, "toggleNavMode error: $err")
            }
        }
    }

    // 统一的 shell 执行：优先走 Shizuku.newProcess，失败回落到 Runtime.exec
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
