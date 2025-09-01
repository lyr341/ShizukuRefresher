package com.example.navswitcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
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
        private const val KEY_OUT = "out"
        private const val KEY_ERR = "err"

        @Volatile private var keepConn: ServiceConnection? = null

        // 绑定 user service，拿到一个与之通信的 Messenger
        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            // 基本可用性检查
            if (!Shizuku.pingBinder()) { onReady(null); return }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onReady(null); return
            }

            // 用 Context 判断是否 debuggable，避免使用 BuildConfig
            val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("sh")   // 必填：非空
                .debuggable(isDebug)       // 不再依赖 BuildConfig
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
                try { msg.replyTo?.send(reply) } catch (_: Throwable) { }
            } else {
                super.handleMessage(msg)
            }
        }
    }
    private val messenger = Messenger(incoming)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun runShellWithOutput(cmd: String): Triple<Int, String, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    outSb.append(line).append('\n')
                }
            }
            BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    errSb.append(line).append('\n')
                }
            }
            p.waitFor()
            Triple(p.exitValue(), outSb.toString(), errSb.toString())
        } catch (t: Throwable) {
            Triple(-1, "", t.message ?: "")
        }
    }
}
