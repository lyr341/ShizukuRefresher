package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001

        // ColorOS/一加系常见 overlay 包名（如有差异，可按你机器实际替换）
        private const val OVERLAY_THREE = "com.android.internal.systemui.navbar.threebutton"
        private const val OVERLAY_GESTURE = "com.android.internal.systemui.navbar.gestural"
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: ImageView

    // 拖拽用
    private var lastRawX = 0f
    private var lastRawY = 0f

    // 点击防抖
    @Volatile private var lastTapTs = 0L

    // 记忆上一次切到的模式（true=三键，false=手势，null=未知）
    private var lastToThree: Boolean? = null

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
            .setContentText("点击在“三键/手势”间切换导航方式")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

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

        // 放大约 5 倍（取 56dp*5 ≈ 280dp）
        val sizePx = dpToPx(56 * 5)

        ball = ImageView(this).apply {
            setImageResource(android.R.drawable.btn_star_big_on)
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        wm.addView(ball, params)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTs < 600) return true // 防抖
                lastTapTs = now
                toggleNav()
                return true
            }
        })

        ball.setOnTouchListener { v, event ->
            val handledByGesture = gd.onTouchEvent(event)
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

    /** 读取当前系统导航模式（2=手势；0/1=三键/两键等），读不到返回 -1 */
    private fun currentNavMode(): Int =
        try { Settings.Secure.getInt(contentResolver, "navigation_mode", -1) }
        catch (_: Throwable) { -1 }

    /** 点击切换逻辑：根据当前/上次状态在三键与手势间互切 */
    private fun toggleNav() {
        // 基本 Shizuku 状态检查
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            return
        }

        // 判定目标：优先用上次记忆；第一次则看系统当前值
        val cur = currentNavMode()
        val toThree = lastToThree ?: (cur == 2)  // 当前是手势(2) → 切三键；否则切手势

        val cmds = if (toThree) {
            listOf(
                "cmd overlay enable $OVERLAY_THREE",
                "cmd overlay disable $OVERLAY_GESTURE",
                "cmd statusbar restart"
            )
        } else {
            listOf(
                "cmd overlay enable $OVERLAY_GESTURE",
                "cmd overlay disable $OVERLAY_THREE",
                "cmd statusbar restart"
            )
        }

        ShizukuShell.execTwo(this, cmds) { code ->
            if (code == 0) {
                lastToThree = !toThree  // 下一次反向切
                Toast.makeText(
                    this,
                    if (toThree) "已切到：三键导航" else "已切到：手势导航",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "执行失败，code=$code", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(ball) } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
