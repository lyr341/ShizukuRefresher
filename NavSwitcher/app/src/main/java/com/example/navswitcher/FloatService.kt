// FloatService.kt 关键片段
package com.example.navswitcher

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: ImageView

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
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.START or Gravity.TOP
        params.x = 200
        params.y = 600

        ball = ImageView(this).apply {
            setImageResource(android.R.drawable.btn_star_big_on)
        }
        wm.addView(ball, params)

        // 用 GestureDetector 精准识别单击，避免拖拽误判
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTs < 800) return true // 防抖 800ms
                lastTapTs = now
                Toast.makeText(applicationContext, "点击触发：开始执行测试命令", Toast.LENGTH_SHORT).show()
                doOnce() // ★ 点击回调
                return true
            }
        })

        ball.setOnTouchListener { v, event ->
            // 先把事件交给手势识别
            val handledByGesture = gd.onTouchEvent(event)

            // 再处理拖动
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    params.x = (params.x + event.rawX - v.width / 2).toInt()
                    params.y = (params.y + event.rawY - v.height / 2).toInt()
                    wm.updateViewLayout(v, params)
                }
            }
            // 如果手势识别成了单击，这里直接消费；否则允许继续拖动
            handledByGesture || event.actionMasked == MotionEvent.ACTION_MOVE
        }
    }

    // 这里先跑“最小可验证”的命令，确认点击 → Shizuku 执行链路通
    private fun doOnce() {
        // 1) 基本状态检查：Shizuku 是否在线且已授权
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }
        if (rikka.shizuku.Shizuku.checkSelfPermission()
            != packageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku 未授权本应用", Toast.LENGTH_SHORT).show()
            return
        }

        // 2) 执行一条最简单命令，拿到退出码，给出可见提示
        ShizukuShell.execTwo(this, listOf("whoami")) { code ->
            Toast.makeText(this, "命令退出码：$code", Toast.LENGTH_SHORT).show()
            // 0 代表成功；确认无误后把下面换成“切导航”的真实命令
            // replaceCommandsForNavSwitch()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(ball) } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?) = null
}
