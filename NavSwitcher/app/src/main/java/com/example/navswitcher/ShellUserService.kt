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

/**
 * 跑在 Shizuku（shell uid）进程中的 Service。
 * 接收一条命令字符串，执行后把 exit code / stdout / stderr 回传。
 */
class ShellUserService : Service() {

    companion object {
        private const val MSG_RUN_CMD = 1
        private const val KEY_CMD = "cmd"
        private const val KEY_CODE = "code"
        private const val KEY_OUT = "out"
        private const val KEY_ERR = "err"

        @Volatile private var keepConn: ServiceConnection? = null

        /** 绑定 user service，拿到一个与之通信的 Messenger */
        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("sh")        // 必填：非空
                .debuggable(BuildConfig.DEBUG)  // 可选
                .version(1)                     // 可选

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

    // Service 端接收消息并执行命令
    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_RUN_CMD) {
                val cmd = msg.data?.getString(KEY_CMD) ?: return
                val (code, out, err) = runShellWithOutput(cmd)

                // 回传给客户端
                val reply = Message.obtain(null, MSG_RUN_CMD).apply {
                    data = Bundle().apply {
                        putInt(KEY_CODE, code)
                        putString(KEY_OUT, out)
                        putString(KEY_ERR, err)
                    }
                }
                try { msg.replyTo?.send(reply) } catch (_: Throwable) {}
            } else {
                super.handleMessage(msg)
            }
        }
    }
    private val messenger = Messenger(incoming)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun runShellWithOutput(cmd: String): Triple<Int, String, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { r ->
            buildString {
                var line = r.readLine()
                var read = 0
                while (line != null && read < 64 * 1024) { // 限个 64KB，避免超大
                    append(line).append('\n')
                    read += line.length + 1
                    line = r.readLine()
                }
            }
        }
        val err = BufferedReader(InputStreamReader(p.errorStream)).use { r ->
            buildString {
                var line = r.readLine()
                var read = 0
                while (line != null && read < 64 * 1024) {
                    append(line).append('\n')
                    read += line.length + 1
                    line = r.readLine()
                }
            }
        }
        p.waitFor()
        Triple(p.exitValue(), out, err)
    } catch (t: Throwable) {
        Triple(-1, "", t.toString())
    }
}
