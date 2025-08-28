package com.example.navswitcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Message
import android.os.Messenger
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

  // 顺序执行两条命令
  fun execTwo(context: Context, a: String, b: String, onDone: (Pair<Int,Int>) -> Unit) {
    exec(context, a) { ca -> exec(context, b) { cb -> onDone(ca to cb) } }
  }
}
