package com.example.navswitcher

import android.app.*
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatService : Service() {
  private lateinit var wm: WindowManager
  private var view: View? = null

  override fun onCreate() {
    super.onCreate()
    startFg()
    wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    addBall()
  }
  override fun onDestroy() {
    super.onDestroy()
    view?.let { wm.removeView(it) }
  }
  override fun onBind(intent: android.content.Intent?): IBinder? = null

  private fun startFg() {
    val chId = "navswitcher"
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(NotificationChannel(chId, "NavSwitcher", NotificationManager.IMPORTANCE_MIN))
    }
    val notif = NotificationCompat.Builder(this, chId)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle("悬浮球已启用")
      .setContentText("点一下执行导航切换（失败则字体兜底）")
      .build()
    startForeground(1, notif)
  }

  private fun addBall() {
    val lp = WindowManager.LayoutParams().apply {
      width = WindowManager.LayoutParams.WRAP_CONTENT
      height = WindowManager.LayoutParams.WRAP_CONTENT
      flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
              WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
              WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
      format = PixelFormat.TRANSLUCENT
      type = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else WindowManager.LayoutParams.TYPE_PHONE
      gravity = Gravity.TOP or Gravity.START
      x = 40; y = 300
    }
    val iv = ImageView(this).apply {
      background = resources.getDrawable(R.drawable.float_bg, theme)
      setOnTouchListener(object: View.OnTouchListener {
        var downX=0f; var downY=0f; var moved=false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
          when(e.action){
            MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; moved=false }
            MotionEvent.ACTION_MOVE -> {
              val dx = e.rawX - downX; val dy = e.rawY - downY
              if (abs(dx)>3 || abs(dy)>3) {
                lp.x += dx.toInt(); lp.y += dy.toInt(); wm.updateViewLayout(v, lp)
                downX = e.rawX; downY = e.rawY; moved = true
              }
            }
            MotionEvent.ACTION_UP -> { if (!moved) doOnce() }
          }
          return true
        }
      })
    }
    view = iv
    wm.addView(iv, lp)
    Toast.makeText(this, "悬浮球已创建：点一下执行切换", Toast.LENGTH_SHORT).show()
  }

  private fun doOnce() {
    // 1) 导航模式切换 0 -> 2
    val (c1, _) = ShizukuShell.exec("settings put secure navigation_mode 0")
    Thread.sleep(180)
    val (c2, _) = ShizukuShell.exec("settings put secure navigation_mode 2")
    if (c1 == 0 && c2 == 0) {
      Toast.makeText(this, "导航切换完成", Toast.LENGTH_SHORT).show()
      return
    }
    // 2) 兜底：字体缩放 1.01 -> 1.00
    ShizukuShell.exec("settings put secure font_scale 1.01")
    Thread.sleep(160)
    ShizukuShell.exec("settings put secure font_scale 1.00")
    Toast.makeText(this, "已用字体轻抖作为兜底", Toast.LENGTH_SHORT).show()
  }
}
