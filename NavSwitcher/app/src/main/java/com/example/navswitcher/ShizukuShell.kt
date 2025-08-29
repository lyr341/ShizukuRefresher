package com.example.navswitcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import rikka.shizuku.Shizuku
import android.os.Handler
import android.os.Looper
import android.widget.Toast

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
      // 客户端的临时回信信使
      val client = Messenger(android.os.Handler(context.mainLooper) { msg ->
        if (msg.what == MSG_RUN_CMD) {
          val code = msg.data.getInt(KEY_CODE, -1)
          onDone(code)
          true
        } else false
      })
      val m = Message.obtain(null, MSG_RUN_CMD).apply {
        replyTo = client
        data = Bundle().apply { putString(KEY_CMD, cmd) }
      }
      try { remote.send(m) } catch (_: Throwable) { onDone(-1) }
    }
  }


    fun execTwo(context: Context, cmds: List<String>, onDone: (Int) -> Unit) {
        ShellUserService.bind(context) { messenger ->
            if (messenger == null) {
                Toast.makeText(context, "绑定 Shizuku 服务失败", Toast.LENGTH_SHORT).show()
                onDone(-1); return@bind
            }
            val msg = Message.obtain(null, 1)
            msg.data = Bundle().apply { putString("cmd", cmds.joinToString(" && ")) }
            msg.replyTo = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    val code = msg.data?.getInt("code", -1) ?: -1
                    onDone(code)
                }
            })
            try {
                messenger.send(msg)
            } catch (t: Throwable) {
                Toast.makeText(context, "发送命令失败: ${t.message}", Toast.LENGTH_SHORT).show()
                onDone(-1)
            }
        }
    }
}
