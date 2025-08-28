package com.example.navswitcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager   // ★ 关键：补这个
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
  private val REQ = 10086

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val tv = findViewById<TextView>(R.id.tv)

    findViewById<Button>(R.id.btnPerm).setOnClickListener {
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        Shizuku.requestPermission(REQ)
      } else {
        tv.append("\nShizuku 权限：已具备")
      }
    }

    findViewById<Button>(R.id.btnFloat).setOnClickListener {
      if (!Settings.canDrawOverlays(this)) {
        val it = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(it)
        return@setOnClickListener
      }
      startForegroundService(Intent(this, FloatService::class.java))
      tv.append("\n悬浮球已启动（通知栏可保活）")
    }

    Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
      override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQ) {
          tv.append("\nShizuku 权限结果：" + if (grantResult == PackageManager.PERMISSION_GRANTED) "已授予" else "被拒绝")
        }
      }
    })
  }
}
