package com.example.navswitcher

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuShell {

  // 异步执行一条命令
  fun exec(context: Context, cmd: String, onDone: (Int) -> Unit) {
    if (!Shizuku.pingBinder()) { onDone(-3); return }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      onDone(-2); return
    }
    ShellUserService.bind(context) { svc ->
      if (svc == null) { onDone(-4); return@bind }
      try {
        onDone(svc.runCmd(cmd))
      } catch (_: Throwable) {
        onDone(-1)
      }
    }
  }

  // 顺序执行两条命令
  fun execTwo(context: Context, a: String, b: String, onDone: (Pair<Int, Int>) -> Unit) {
    exec(context, a) { ca ->
      exec(context, b) { cb ->
        onDone(ca to cb)
      }
    }
  }
}
