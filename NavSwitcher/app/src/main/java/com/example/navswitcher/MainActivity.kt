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
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var shizukuPermListener: Shizuku.OnRequestPermissionResultListener
    private val shizukuPermReqCode: Int = 10086

    private val reqPostNotification: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean ->
            // 不论是否同意通知权限，都不拦主流程
        }

    private val reqOverlay: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _: ActivityResult ->
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "已授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                maybeRequestShizukuAndStart()
            } else {
                Toast.makeText(this, "仍未授予悬浮窗权限", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 按钮：开启/重启悬浮球
        val btn = Button(this).apply {
            text = "开启悬浮球"
            setOnClickListener { ensureAndStart() }
        }
        setContentView(btn)

        // 显式初始化监听器（注意：这里不会自我递归）
        shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _: Int, grantResult: Int ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startFloatService()
            } else {
                Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            }
            // 用完移除监听
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        }

        // Android 13+ 请求通知权限（前台服务通知用）
        if (Build.VERSION.SDK_INT >= 33) {
            reqPostNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureAndStart() {
        // 1) 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            reqOverlay.launch(intent)
            return
        }
        // 2) Shizuku 权限
        maybeRequestShizukuAndStart()
    }

    private fun maybeRequestShizukuAndStart() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行，请先在 Shizuku App 中启动", Toast.LENGTH_LONG).show()
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
