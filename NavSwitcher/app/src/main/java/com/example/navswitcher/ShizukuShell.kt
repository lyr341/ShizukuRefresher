package com.example.navswitcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import rikka.shizuku.Shizuku

object ShizukuShell {

    private const val MSG_RUN_CMD = 1
    private const val KEY_CMD = "cmd"
    private const val KEY_CODE = "code"

    // 发一条命令，回调给出 exit code
    fun exec(context: Context, cmd: String, onDone: (Int) -> Unit) {
        if (!Shizuku.pingBinder()) { onDone(-3); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            onDone(-2); return
        }
        ShellUserService.bind(context) { remote ->
            if (remote == null) { onDone(-4); return@bind }

            val client = Messenger(
                Handler(context.mainLooper, Handler.Callback { msg ->
                    if (msg.what == MSG_RUN_CMD) {
                        val code = msg.data.getInt(KEY_CODE, -1)
                        onDone(code)
                        return@Callback true
                    }
                    false
                })
            )

            val m = Message.obtain(null, MSG_RUN_CMD).apply {
                replyTo = client
                data = Bundle().apply { putString(KEY_CMD, cmd) }
            }
            try { remote.send(m) } catch (_: Throwable) { onDone(-1) }
        }
    }

    // 连发多条（用 && 串起来），回调给出 exit code
    fun execTwo(context: Context, cmds: List<String>, onDone: (Int) -> Unit) {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            onDone(-3); return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            onDone(-2); return
        }

        ShellUserService.bind(context) { messenger ->
            if (messenger == null) {
                Toast.makeText(context, "绑定 Shizuku 服务失败", Toast.LENGTH_SHORT).show()
                onDone(-4); return@bind
            }

            val msg = Message.obtain(null, MSG_RUN_CMD).apply {
                data = Bundle().apply { putString(KEY_CMD, cmds.joinToString(" && ")) }
                replyTo = Messenger(
                    Handler(Looper.getMainLooper(), Handler.Callback { r ->
                        if (r.what == MSG_RUN_CMD) {
                            val code = r.data?.getInt(KEY_CODE, -1) ?: -1
                            onDone(code)
                            return@Callback true
                        }
                        false
                    })
                )
            }

            try {
                messenger.send(msg)
            } catch (t: Throwable) {
                Toast.makeText(context, "发送命令失败: ${t.message}", Toast.LENGTH_SHORT).show()
                onDone(-1)
            }
        }
    }
}
