package com.example.navswitcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShellUserService : Service() {

    companion object {
        private const val MSG_RUN_CMD = 1
        private const val KEY_CMD = "cmd"
        private const val KEY_CODE = "code"

        @Volatile private var keepConn: ServiceConnection? = null

        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("sh")
                .debuggable(false)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    onReady(Messenger(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    onReady(null)
                }
            }
            keepConn = conn
            Shizuku.bindUserService(args, conn)
        }
    }

    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_RUN_CMD) {
                val cmd = msg.data?.getString(KEY_CMD) ?: return
                val code = runShell(cmd)
                val reply = Message.obtain(null, MSG_RUN_CMD).apply {
                    data = Bundle().apply { putInt(KEY_CODE, code) }
                }
                try { msg.replyTo?.send(reply) } catch (_: Throwable) {}
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
}
