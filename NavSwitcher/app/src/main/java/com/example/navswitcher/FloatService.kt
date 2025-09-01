package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: ImageView

    // 拖动用：记录上一次原始坐标，避免“漂移”
    private var lastRawX = 0f
    private var lastRawY = 0f

    // 点击防抖
    @Volatile private var lastTapTs = 0L

    // dp -> px
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

        // 56dp 的圆形小球（半透明蓝）
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xAA33B5E5.toInt())
        }

        ball = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(56), dp(56))
            background = bg
            // 如果想在圆球里放个小图标，可取消下一行注释
            // setImageResource(android.R.drawable.ic_media_play)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        wm.addView(ball, params)

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTs < 800) return true // 防抖 800ms
                lastTapTs = now
                Toast.makeText(applicationContext, "已触发：切手势→等待→切回三键", Toast.LENGTH_SHORT).show()
                doOnce()
                return true
            }
        })

        ball.setOnTouchListener { v, event ->
            val handledByGesture = gesture.onTouchEvent(event)

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

    // 最小可验证链路：先跑一次“切手势→等待→切回三键”
    private fun doOnce() {
        // 1) Shizuku 状态 & 授权检查（注意比较符号方向）
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            return
        }

        // 2) 执行测试命令
        ShizukuShell.execTwo(
            this,
            listOf(
                "settings put secure navigation_mode 2",
                "sleep 1",
                "settings put secure navigation_mode 0"
            )
        ) { code ->
            Toast.makeText(
                this,
                if (code == 0) "执行成功（code=0）" else "执行失败，code=$code",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(ball) } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
