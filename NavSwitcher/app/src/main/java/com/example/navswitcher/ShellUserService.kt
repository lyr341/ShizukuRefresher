package com.example.navswitcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 跑在 Shizuku（shell uid）进程中的 Service。
 * 通过 Messenger 接收一条命令字符串，执行后把 exit code 回传。
 */
class ShellUserService : Service() {

    companion object {
        private const val MSG_RUN_CMD = 1
        private const val KEY_CMD = "cmd"
        private const val KEY_CODE = "code"

        // 绑定 user service，拿到一个与之通信的 Messenger
        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn).daemon(false)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    onReady(Messenger(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    onReady(null)
                }
            }
            Shizuku.bindUserService(args, conn)
        }
    }

    // Service 端接收消息并执行命令
    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_RUN_CMD) {
                val cmd = msg.data?.getString(KEY_CMD) ?: return
                val code = runShell(cmd)
                // 回传给客户端
                val reply = Message.obtain(null, MSG_RUN_CMD)
                val b = Bundle().apply { putInt(KEY_CODE, code) }
                reply.data = b
                try { msg.replyTo?.send(reply) } catch (_: Throwable) { }
            } else {
                super.handleMessage(msg)
            }
        }
    }
    private val messenger = Messenger(incoming)

    override fun onBind(intent: android.content.Intent?): IBinder = messenger.binder

    private fun runShell(cmd: String): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (r.readLine()!=null){} }
            BufferedReader(InputStreamReader(p.errorStream)).use { r -> while (r.readLine()!=null){} }
            p.waitFor()
            p.exitValue()
        } catch (_: Throwable) { -1 }
    }
}
