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

        // 简单的纵向布局
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        fun makeBtn(textStr: String, onClick: () -> Unit) = Button(this).apply {
            text = textStr
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams = lp
            setOnClickListener { onClick() }
        }

        // 1) 开启/重启悬浮球
        root.addView(makeBtn("开启悬浮球") {
            ensureAndStart()
        })

        // 2) 截图频率 100ms
        root.addView(makeBtn("截图频率 100ms") {
            ensureAndStart()
            sendSetInterval(100L)
        })

        // 3) 截图频率 200ms
        root.addView(makeBtn("截图频率 200ms") {
            ensureAndStart()
            sendSetInterval(200L)
        })

        // 4) 截图频率 300ms
        root.addView(makeBtn("截图频率 300ms") {
            ensureAndStart()
            sendSetInterval(300L)
        })

        // 5) 隐藏悬浮球
        root.addView(makeBtn("隐藏悬浮球") {
            ensureAndStart()
            val it = Intent(this, FloatService::class.java).apply {
                action = FloatService.ACTION_HIDE_BALL
            }
            startServiceCompat(it)
        })

        // 6) 显示悬浮球
        root.addView(makeBtn("显示悬浮球") {
            ensureAndStart()
            val it = Intent(this, FloatService::class.java).apply {
                action = FloatService.ACTION_SHOW_BALL
            }
            startServiceCompat(it)
        })

        setContentView(root)

        // 显式初始化监听器
        shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _: Int, grantResult: Int ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                startFloatService() // 权限到手后拉起服务
            } else {
                Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            }
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

    private fun startFloatService() 
    {
        val it = Intent(this, FloatService::class.java)
        startServiceCompat(it)
    }

    private fun sendSetInterval(ms: Long) {
        val it = Intent(this, FloatService::class.java).apply {
            action = FloatService.ACTION_SET_INTERVAL
            putExtra(FloatService.EXTRA_INTERVAL_MS, ms)
        }
        startServiceCompat(it)
        Toast.makeText(this, "已设置截图频率为 ${ms}ms", Toast.LENGTH_SHORT).show()
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
