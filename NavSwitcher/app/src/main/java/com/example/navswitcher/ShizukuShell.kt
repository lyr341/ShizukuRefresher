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
    private const val KEY_CMD = "cmd"
    private const val KEY_CODE = "code"
    private const val KEY_OUT = "out"
    private const val KEY_ERR = "err"

    data class Result(val code: Int, val out: String, val err: String)

    fun exec(context: Context, cmd: String, onDone: (Result) -> Unit) {
        execTwo(context, listOf(cmd), onDone)
    }

    // 支持多条命令 && 串行
    fun execTwo(context: Context, cmds: List<String>, onDone: (Result) -> Unit) {
        Log.d(TAG, "execTwo: start, cmds=${cmds.joinToString(" && ")}")

        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku not running")
            Toast.makeText(context, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            onDone(Result(-3, "", "shizuku not running")); return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Shizuku not granted")
            Toast.makeText(context, "未授予 Shizuku 权限", Toast.LENGTH_SHORT).show()
            onDone(Result(-2, "", "no permission")); return
        }

        ShellUserService.bind(context) { remote ->
            if (remote == null) {
                Log.e(TAG, "bindUserService: remote is null")
                Toast.makeText(context, "绑定 Shizuku 服务失败", Toast.LENGTH_SHORT).show()
                onDone(Result(-4, "", "bind failed")); return@bind
            }
            Log.d(TAG, "bindUserService: connected")

            val client = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (msg.what == MSG_RUN_CMD) {
                        val code = msg.data?.getInt(KEY_CODE, -1) ?: -1
                        val out  = msg.data?.getString(KEY_OUT).orEmpty()
                        val err  = msg.data?.getString(KEY_ERR).orEmpty()
                        Log.d(TAG, "callback: EXIT code=$code; out=${out.trim()}; err=${err.trim()}")
                        onDone(Result(code, out, err))
                    } else {
                        super.handleMessage(msg)
                    }
                }
            })

            val m = Message.obtain(null, MSG_RUN_CMD).apply {
                replyTo = client
                data = Bundle().apply { putString(KEY_CMD, cmds.joinToString(" && ")) }
            }
            try {
                Log.d(TAG, "send cmd: ${cmds.joinToString(" && ")}")
                remote.send(m)
            } catch (t: Throwable) {
                Log.e(TAG, "send failed: ${t.message}")
                Toast.makeText(context, "发送命令失败: ${t.message}", Toast.LENGTH_SHORT).show()
                onDone(Result(-1, "", t.message ?: "send failed"))
            }
        }
    }
}
