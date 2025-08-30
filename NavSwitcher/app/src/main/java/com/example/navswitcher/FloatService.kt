package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: ImageView

    // 拖拽用：记录按下点，避免“飘移”
    private var lastRawX = 0f
    private var lastRawY = 0f

    // 点击防抖
    @Volatile private var lastTapTs = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground()
        addBall()
    }

    private fun startForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "NavSwitcher", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val noti = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("悬浮球已启用")
            .setContentText("点击悬浮球触发一次命令")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun addBall() {
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
        }
        wm.addView(ball, params)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTs < 800) return true // 防抖 800ms
                lastTapTs = now
                Toast.makeText(applicationContext, "点击触发：开始执行测试命令", Toast.LENGTH_SHORT).show()
                doOnce()
                return true
            }
        })

        ball.setOnTouchListener { v, event ->
            // 交给手势识别（单击）
            val handledByGesture = gd.onTouchEvent(event)

            // 再处理拖动
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    params.x += dx
                    params.y += dy
                    wm.updateViewLayout(v, params)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
            }
            handledByGesture || event.actionMasked == MotionEvent.ACTION_MOVE
        }
    }

    // 最小可验证链路：先跑 whoami
    private fun doOnce() {
        // 1) Shizuku 状态 & 授权检查（原来这段有个“等号方向”写反了）
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            return
        }

        // 2) 执行测试命令
        ShizukuShell.execTwo(this, listOf("whoami")) { code ->
            Toast.makeText(this, "命令退出码：$code", Toast.LENGTH_SHORT).show()
            // 0 代表执行成功。确认成功后，把这里换成真正的“切换导航”命令组合：
            // ShizukuShell.execTwo(this, listOf(
            //     "settings put secure navigation_mode 2",
            //     "cmd statusbar collapse"
            // )) { exit -> ... }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(ball) } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
