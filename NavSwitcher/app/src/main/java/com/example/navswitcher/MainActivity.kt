package com.example.navswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val reqPostNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val reqOverlay = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            maybeRequestShizukuAndStart()
        } else {
            Toast.makeText(this, "仍未授予悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    private val shizukuPermReqCode = 10086
    
    // ✅ 用 lazy 初始化，避免“must be initialized”报错
    private val shizukuPermListener by lazy {
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startFloatService()
            } else {
                Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            }
            // 用完及时移除监听
            Shizuku.removeRequestPermissionResultListener(this.shizukuPermListener)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply {
            text = "开启悬浮球"
            setOnClickListener { ensureAndStart() }
        }
        setContentView(btn)

        if (Build.VERSION.SDK_INT >= 33) {
            reqPostNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            reqOverlay.launch(intent)
            return
        }
        maybeRequestShizukuAndStart()
    }

    private fun maybeRequestShizukuAndStart() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            startFloatService()
        } else {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener)
            Shizuku.requestPermission(shizukuPermReqCode)
        }
    }

    private fun startFloatService() {
        try {
            val it = Intent(this, FloatService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it) else startService(it)
            Toast.makeText(this, "已请求启动悬浮球服务", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "启动服务异常: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
