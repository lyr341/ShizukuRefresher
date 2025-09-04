package com.example.navswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> }

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

        // 垂直布局容器
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        fun addBtn(textStr: String, onClick: () -> Unit) {
            val b = Button(this).apply {
                text = textStr
                setOnClickListener { onClick() }
            }
            root.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 })
        }

        // 1) 开启/重启悬浮球（并不强制显示）
        addBtn("开启悬浮球") { ensureAndStart() }

        // 2) 频率按钮：100ms / 200ms / 300ms
        addBtn("截图频率 100ms") { sendSetInterval(100L) }
        addBtn("截图频率 200ms") { sendSetInterval(200L) }
        addBtn("截图频率 300ms") { sendSetInterval(300L) }

        // 3) 显示/隐藏悬浮球
        addBtn("隐藏悬浮球") { sendAction(FloatService.ACTION_HIDE_BALL) }
        addBtn("显示悬浮球") { sendAction(FloatService.ACTION_SHOW_BALL) }

        setContentView(root)

        // 显式初始化监听器
        shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _: Int, grantResult: Int ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startFloatService()
            } else {
                Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            }
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        }

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

    private fun sendAction(action: String) {
        val it = Intent(this, FloatService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it) else startService(it)
        } catch (_: Throwable) { startService(it) }
    }

    private fun sendSetInterval(ms: Long) {
        val it = Intent(this, FloatService::class.java)
            .setAction(FloatService.ACTION_SET_INTERVAL)
            .putExtra(FloatService.EXTRA_INTERVAL_MS, ms)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(it) else startService(it)
            Toast.makeText(this, "已发送频率 ${ms}ms", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "设置频率失败: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
