package com.example.navswitcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShellUserService : Service() {

    companion object {
        private const val MSG_RUN_CMD = 1
        private const val KEY_CMD = "cmd"
        private const val KEY_CODE = "code"

        // 强引用，避免 GC 导致断连
        @Volatile private var keepConn: ServiceConnection? = null

        // 绑定 Shizuku 用户服务，回调拿到 Messenger
        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            // 基本可用性检查：Shizuku 在线 & 已授权
            if (!Shizuku.pingBinder()) { onReady(null); return }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { onReady(null); return }

            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("sh")  // 必填：非空
                .debuggable(isDebug)      // 不再依赖 BuildConfig
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    onReady(if (service != null) Messenger(service) else null)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    onReady(null)
                }
            }
            keepConn = conn
            Shizuku.bindUserService(args, conn)
        }
    }

    // Service 端接收消息并执行命令
    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_RUN_CMD) {
                val cmd = msg.data?.getString(KEY_CMD) ?: return
                val code = runShell(cmd)
                val reply = Message.obtain(null, MSG_RUN_CMD).apply {
                    data = Bundle().apply { putInt(KEY_CODE, code) }
                }
                try { msg.replyTo?.send(reply) } catch (_: Throwable) { }
            } else {
                super.handleMessage(msg)
            }
        }
    }
    private val messenger = Messenger(incoming)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun runShell(cmd: String): Int = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (r.readLine() != null) {} }
        BufferedReader(InputStreamReader(p.errorStream)).use { r -> while (r.readLine() != null) {} }
        p.waitFor()
        p.exitValue()
    } catch (_: Throwable) { -1 }

    override fun onDestroy() {
        super.onDestroy()
        // 允许连接对象被回收
        keepConn = null
    }
}
