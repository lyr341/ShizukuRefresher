package com.example.navswitcher

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
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

        private const val PKG_THREE = "com.android.internal.systemui.navbar.threebutton"
        private const val PKG_GEST  = "com.android.internal.systemui.navbar.gestural"
    }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var ball: ImageView

    @Volatile private var lastTapTs = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
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
            .setContentText("点击悬浮球切换导航方式")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

        // 画一个大圆（≈ 200dp），边框+阴影效果
        val size = dp(200)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC2196F3"))  // 半透明蓝
            setStroke(dp(2), Color.WHITE)
        }

        ball = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            background = circle
            elevation = dp(6).toFloat()
        }
        wm.addView(ball, params)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapTs < 600) return true
                lastTapTs = now
                Log.d(TAG, "onClick")
                Toast.makeText(applicationContext, "切换中…", Toast.LENGTH_SHORT).show()
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

    private fun toggleNav() {
        // 先探测是否是三键
        val probe = "cmd overlay list | grep -E '\\[x\\].*${PKG_THREE}' >/dev/null && echo THREE || echo OTHER"
        ShizukuShell.exec(this, probe) { r ->
            Log.d(TAG, "probe exit=${r.code}, out=${r.out.trim()}, err=${r.err.trim()}")
            val isThree = r.out.contains("THREE")
            if (isThree) {
                // 三键 -> 切手势
                toGestural()
            } else {
                // 其它 -> 切三键
                toThree()
            }
        }
    }

    private fun toThree() {
        val cmds = listOf(
            "cmd overlay enable $PKG_THREE",
            "cmd overlay disable $PKG_GEST",
            // ColorOS/OOS 这句等价于 SystemUI 重启，权限要求低
            "cmd statusbar restart"
        )
        ShizukuShell.execTwo(this, cmds) { r ->
            Log.d(TAG, "toThree EXIT=${r.code}\nOUT=${r.out}\nERR=${r.err}")
            Toast.makeText(
                this,
                if (r.code == 0) "已切到三键（若未刷新，锁屏/息屏一下）" else "切三键失败 code=${r.code}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toGestural() {
        val cmds = listOf(
            "cmd overlay disable $PKG_THREE",
            "cmd overlay enable $PKG_GEST",
            "cmd statusbar restart"
        )
        ShizukuShell.execTwo(this, cmds) { r ->
            Log.d(TAG, "toGestural EXIT=${r.code}\nOUT=${r.out}\nERR=${r.err}")
            Toast.makeText(
                this,
                if (r.code == 0) "已切到手势（若未刷新，锁屏/息屏一下）" else "切手势失败 code=${r.code}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm.removeView(ball) } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?) = null
}
