package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.util.Log
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
        private const val OVERLAY_THREE = "com.android.internal.systemui.navbar.threebutton"
        private const val OVERLAY_GESTURE = "com.android.internal.systemui.navbar.gestural"
        private const val TAG = "FloatService"
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: ImageView
    private var lastRawX = 0f
    private var lastRawY = 0f
    @Volatile private var lastTapTs = 0L
    private var lastToThree: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        addBall()
        Log.d(TAG, "onCreate")
    }

    private fun startForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "NavSwitcher", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val noti = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("悬浮球已启用")
            .setContentText("点击在“三键/手势”之间切换")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun addBall() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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

        val sizePx = dpToPx(56 * 5)   // 放大 5 倍
        ball = ImageView(this).apply {
            setImageResource(android.R.drawable.btn_star_big_on) // 先用大五角星，后面你想换圆球就换成自定义 drawable
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
        }
        wm.addView(ball, params)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d(TAG, "onSingleTapConfirmed")
                handleTap()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                Log.d(TAG, "onLongPress as tap fallback")
                handleTap()
            }
        })

        ball.setOnTouchListener { v, event ->
            val handledByGesture = gd.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastRawX = event.rawX; lastRawY = event.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    params.x += dx; params.y += dy
                    wm.updateViewLayout(v, params)
                    lastRawX = event.rawX; lastRawY = event.rawY
                }
            }
            handledByGesture || event.actionMasked == MotionEvent.ACTION_MOVE
        }
    }

    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTs < 500) return
        lastTapTs = now
        Toast.makeText(this, "切换中…", Toast.LENGTH_SHORT).show()
        toggleNav()
    }

    private fun currentNavMode(): Int =
        try { Settings.Secure.getInt(contentResolver, "navigation_mode", -1) }
        catch (_: Throwable) { -1 }

    private fun toggleNav() {
        if (!Shizuku.pingBinder()) { toast("Shizuku 未运行"); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { toast("未授予 Shizuku 权限"); return }

        val cur = currentNavMode()
        val toThree = lastToThree ?: (cur == 2) // 首次：手势(2) -> 三键
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
        Log.d(TAG, "exec: ${cmds.joinToString(" && ")}")

        ShizukuShell.execTwo(this, cmds) { code, out, err ->
            Log.d(TAG, "exec done code=$code\nstdout=${out}\nstderr=${err}")
            if (code == 0) {
                lastToThree = !toThree
                toast(if (toThree) "已切到：三键导航" else "已切到：手势导航")
            } else {
                toast("执行失败 code=$code")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(ball) } catch (_: Throwable) {}
        Log.d(TAG, "onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
