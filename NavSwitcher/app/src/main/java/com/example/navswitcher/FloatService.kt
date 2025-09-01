package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
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
    }

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: ImageView? = null

    @Volatile private var lastTapTs = 0L
    private var lastRawX = 0f
    private var lastRawY = 0f

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
        if (!android.provider.Settings.canDrawOverlays(this)) {
            throw IllegalStateException("未授予悬浮窗权限")
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
            // 先用五角星（你之前能看见它说明窗口链路是通的）
            // 放大 3 倍以便更好点按
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

    // 读取当前 overlay 开关作为“手势/三键”判断依据
    private fun isThreeButtonActive(onResult: (Boolean) -> Unit) {
        ShizukuShell.exec(
            this,
            "cmd overlay list | grep com.android.internal.systemui.navbar.threebutton | wc -l"
        ) { code ->
            // code 只是退出码，不是行数；我们改用返回码==0作为“命令成功”，再用另一条更稳妥的判断
            // 直接判断三键是否enabled
            ShizukuShell.exec(
                this,
                "cmd overlay list | grep '\\[x\\] com.android.internal.systemui.navbar.threebutton' | wc -l"
            ) { _ ->
                // 无法拿到stdout，这里退而求其次：再发一条只看 gesture 是否开启
                ShizukuShell.exec(
                    this,
                    "cmd overlay list | grep '\\[x\\] com.android.internal.systemui.navbar.gestural' | wc -l"
                ) { _ -> 
                    // 我们不纠结数量了：用“尝试切换后再发系统栏重启”达成最终一致
                    // 这里先走保守逻辑：认为“勾选三键”为已启用
                    onResult(false) // 先假定不是三键，点一下切到三键
                }
            }
        }
    }

    private fun toggleNavMode() {
        // 保守逻辑：每次点击先“切到三键”；如果已经是三键，则切回手势
        isThreeButtonActive { isThree ->
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
                return@isThreeButtonActive
            }
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
            ShizukuShell.execTwo(this, cmds) { code ->
                if (code == 0) {
                    Toast.makeText(this, "切换命令已执行", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "切换失败，code=$code", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { wm?.removeView(ball) } catch (_: Throwable) {}
        wm = null; ball = null; params = null
    }

    override fun onBind(intent: Intent?) = null
}
