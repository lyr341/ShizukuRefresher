package com.example.navswitcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import rikka.shizuku.Shizuku

object ShizukuShell {

    private const val TAG = "ShizukuShell"
    private const val MSG_RUN_CMD = 1

    /**
     * 执行一组命令（用 && 连接），回调返回 (exitCode, stdout, stderr)
     */
    fun execTwo(context: Context, cmds: List<String>, onDone: (Int, String, String) -> Unit) {
        val joined = cmds.joinToString(" && ")
        Log.d(TAG, "execTwo: $joined")

        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            onDone(-3, "", "Shizuku binder dead"); return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            onDone(-2, "", "Permission denied"); return
        }

        var timedOut = false
        val timeout = Handler(Looper.getMainLooper())
        val to = Runnable {
            timedOut = true
            Log.e(TAG, "bind timeout")
            Toast.makeText(context, "绑定 Shizuku 服务超时", Toast.LENGTH_SHORT).show()
            onDone(-5, "", "bind timeout")
        }
        timeout.postDelayed(to, 5000)

        ShellUserService.bind(context) { messenger ->
            if (timedOut) return@bind
            timeout.removeCallbacks(to)

            if (messenger == null) {
                Log.e(TAG, "bind returned null messenger")
                Toast.makeText(context, "绑定 Shizuku 服务失败", Toast.LENGTH_SHORT).show()
                onDone(-1, "", "bind failed"); return@bind
            }

            val msg = Message.obtain(null, MSG_RUN_CMD).apply {
                data = Bundle().apply { putString("cmd", joined) }
                replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        val code = msg.data?.getInt("code", -1) ?: -1
                        val out = msg.data?.getString("out").orEmpty()
                        val err = msg.data?.getString("err").orEmpty()
                        Log.d(TAG, "onReply code=$code, out.len=${out.length}, err.len=${err.length}")
                        onDone(code, out, err)
                    }
                })
            }

            try {
                messenger.send(msg)
                Log.d(TAG, "send ok")
            } catch (t: Throwable) {
                Log.e(TAG, "send failed", t)
                Toast.makeText(context, "发送命令失败: ${t.message}", Toast.LENGTH_SHORT).show()
                onDone(-1, "", t.message ?: "send failed")
            }
        }
    }
}
