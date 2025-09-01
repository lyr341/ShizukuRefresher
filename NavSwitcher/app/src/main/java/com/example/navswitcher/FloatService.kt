package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.Executors

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"
    }

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: ImageView? = null

    @Volatile private var lastTapTs = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f

    // 用单线程池跑所有 shell，避免并发
    private val shellExec = Executors.newSingleThreadExecutor()

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
            val ch = NotificationChannel(
                CH_ID, "NavSwitcher", NotificationManager.IMPORTANCE_MIN
            )
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
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku 未运行或未授权")
        }

        val wmgr = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = wmgr
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
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
        params = lp

        val iv = ImageView(this).apply {
            // 先用系统大星星，放大一点方便点按
            setImageResource(android.R.drawable.btn_star_big_on)
            scaleX = 3f
            scaleY = 3f
        }
        ball = iv
        wm?.addView(iv, lp)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTs < 500) return true
                lastTapTs = now
                Toast.makeText(applicationContext, "切换中…", Toast.LENGTH_SHORT).show()
                toggleNavMode()
                return true
            }
        })

        iv.setOnTouchListener { v, event ->
            val handledTap = gd.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params?.let { p ->
                        val dx = (event.rawX - lastRawX).toInt()
                        val dy = (event.rawY - lastRawY).toInt()
                        p.x += dx; p.y += dy
                        wm?.updateViewLayout(v, p)
                        lastRawX = event.rawX; lastRawY = event.rawY
                    }
                }
            }
            handledTap || event.actionMasked == MotionEvent.ACTION_MOVE
        }
    }

    /**
     * 通过 Shizuku 执行 shell，拿到 exitCode / stdout / stderr
     */
    private fun sh(
        cmd: String,
        onDone: (code: Int, out: String, err: String) -> Unit
    ) {
        shellExec.execute {
            var code = -1
            var outStr = ""
            var errStr = ""
            try {
                val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val out = BufferedReader(InputStreamReader(proc.inputStream, Charset.forName("UTF-8")))
                val err = BufferedReader(InputStreamReader(proc.errorStream, Charset.forName("UTF-8")))
                val sbOut = StringBuilder()
                val sbErr = StringBuilder()
                var line: String?
                while (out.readLine().also { line = it } != null) sbOut.appendLine(line)
                while (err.readLine().also { line = it } != null) sbErr.appendLine(line)
                code = proc.waitFor()
                outStr = sbOut.toString()
                errStr = sbErr.toString()
            } catch (t: Throwable) {
                errStr = t.message ?: t.toString()
                Log.e(TAG, "sh error: $cmd", t)
            }
            Handler(Looper.getMainLooper()).post {
                onDone(code, outStr, errStr)
            }
        }
    }

    /**
     * 判断是否处于“三键导航”
     * 通过 overlay 列表中已启用项判断
     */
    private fun isThreeButtonActive(onResult: (Boolean) -> Unit) {
        val grep = "cmd overlay list"
        sh(grep) { code, out, _ ->
            if (code != 0) {
                onResult(false)
                return@sh
            }
            // 例：已启用项通常形如 "[x] com.android.internal.systemui.navbar.threebutton"
            val enabledThree = out.lines().any {
                it.contains("[x]") && it.contains("com.android.internal.systemui.navbar.threebutton")
            }
            onResult(enabledThree)
        }
    }

    private fun toggleNavMode() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }
        isThreeButtonActive { isThree ->
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
            runBatch(cmds) { ok, logs ->
                if (ok) {
                    Toast.makeText(this, "切换完成", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "切换失败\n$logs", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runBatch(cmds: List<String>, onDone: (ok: Boolean, logs: String) -> Unit) {
        val logSb = StringBuilder()
        fun runAt(i: Int) {
            if (i >= cmds.size) {
                onDone(true, logSb.toString())
                return
            }
            val cmd = cmds[i]
            sh(cmd) { code, out, err ->
                logSb.appendLine("[$code] $cmd")
                if (out.isNotBlank()) logSb.appendLine(out.trim())
                if (err.isNotBlank()) logSb.appendLine(err.trim())
                if (code == 0) {
                    runAt(i + 1)
                } else {
                    onDone(false, logSb.toString())
                }
            }
        }
        runAt(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm?.removeView(ball) } catch (_: Throwable) {}
        wm = null; ball = null; params = null
        shellExec.shutdownNow()
    }

    override fun onBind(intent: Intent?) = null
}
