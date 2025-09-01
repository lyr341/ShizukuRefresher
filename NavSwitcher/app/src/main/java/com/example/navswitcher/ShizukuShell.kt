package com.example.navswitcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * 客户端：负责与跑在 Shizuku 里的 ShellUserService 通信
 */
object ShizukuShell {

    private const val MSG_RUN_CMD = 1
    private const val KEY_CMD = "cmd"
    private const val KEY_CODE = "code"
    private const val KEY_OUT = "out"
    private const val KEY_ERR = "err"

    /**
     * 发送一组命令（会用 `&&` 串起来），回调提供 exitCode、stdout、stderr
     */
    fun execTwo(
        context: Context,
        cmds: List<String>,
        onDone: (code: Int, out: String, err: String) -> Unit
    ) {
        // 基本可用性检查
        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            onDone(-3, "", "shizuku not running"); return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            onDone(-2, "", "no permission"); return
        }

        // 绑定到 shell 进程中的 service
        ShellUserService.bind(context) { remote ->
            if (remote == null) {
                Toast.makeText(context, "绑定 Shizuku 服务失败", Toast.LENGTH_SHORT).show()
                onDone(-4, "", "bind failed"); return@bind
            }

            // 客户端回信的 Messenger（主线程 Handler）
            val client = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what == MSG_RUN_CMD) {
                        val data = msg.data ?: Bundle.EMPTY
                        val code = data.getInt(KEY_CODE, -1)
                        val out = data.getString(KEY_OUT).orEmpty()
                        val err = data.getString(KEY_ERR).orEmpty()
                        onDone(code, out, err)
                    } else {
                        super.handleMessage(msg)
                    }
                }
            })

            // 发送命令（用 && 串起来）
            val m = Message.obtain(null, MSG_RUN_CMD).apply {
                replyTo = client
                data = Bundle().apply { putString(KEY_CMD, cmds.joinToString(" && ")) }
            }

            try {
                remote.send(m)
            } catch (t: Throwable) {
                Toast.makeText(context, "发送命令失败: ${t.message}", Toast.LENGTH_SHORT).show()
                onDone(-1, "", t.toString())
            }
        }
    }
}
