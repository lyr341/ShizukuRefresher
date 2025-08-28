package com.example.navswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager          // ★ 必须有
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
  private val REQ = 10086

  // Android 13+ 通知权限
  private val requestNotif = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { /* 忽略结果，前台服务如果没权限会提示失败，不崩溃 */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val tv = findViewById<TextView>(R.id.tv)

    // 申请 Shizuku 权限
    findViewById<Button>(R.id.btnPerm).setOnClickListener {
      try {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
          Shizuku.requestPermission(REQ)
        } else {
          tv.append("\nShizuku 权限：已具备")
        }
      } catch (e: Throwable) {
        tv.append("\nShizuku 不可用：" + (e.message ?: ""))
      }
    }

    // 开启悬浮球
    findViewById<Button>(R.id.btnFloat).setOnClickListener {
      // Android 13+ 通知权限（用于前台服务通知）
      if (Build.VERSION.SDK_INT >= 33) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
          requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
      // 悬浮窗权限
      if (!Settings.canDrawOverlays(this)) {
        val it = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(it)
        return@setOnClickListener
      }
      // 启动前台服务（有 try 防崩）
      try {
        startForegroundService(Intent(this, FloatService::class.java))
        tv.append("\n悬浮球已启动（通知栏可保活）")
      } catch (e: Throwable) {
        tv.append("\n启动服务失败：" + (e.message ?: ""))
      }
    }

    // Shizuku 权限回调（防御式，避免机型异常导致崩溃）
    try {
      Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
          if (requestCode == REQ) {
            tv.append("\nShizuku 权限结果：" +
              if (grantResult == PackageManager.PERMISSION_GRANTED) "已授予" else "被拒绝")
          }
        }
      })
    } catch (_: Throwable) {
      tv.append("\nShizuku 回调注册失败（忽略）")
    }
  }
}
