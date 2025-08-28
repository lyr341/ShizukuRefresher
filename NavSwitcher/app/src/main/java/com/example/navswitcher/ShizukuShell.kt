package com.example.navswitcher

import rikka.shizuku.Shizuku

object ShizukuShell {
  // 异步绑定并执行；执行完成通过回调返回 exit code
  fun exec(cmd: String, onDone: (Int) -> Unit) {
    if (!Shizuku.pingBinder()) { onDone(-3); return }
    if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      onDone(-2); return
    }
    ShellUserService.bind { svc ->
      if (svc == null) { onDone(-4); return@bind }
      try {
        val code = svc.runCmd(cmd)
        onDone(code)
      } catch (_: Throwable) {
        onDone(-1)
      }
    }
  }

  // 简单的串行动作：先 A 后 B
  fun execTwo(a: String, b: String, onDone: (Pair<Int,Int>) -> Unit) {
    exec(a) { ca ->
      exec(b) { cb -> onDone(ca to cb) }
    }
  }
}
