package com.example.navswitcher
import dev.rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuShell {
  fun exec(cmd: String, timeoutMs: Long = 3000): Pair<Int,String> {
    if (!Shizuku.pingBinder()) return -1 to "Shizuku not running"
    return try {
      val p = Shizuku.newProcess(arrayOf("sh","-c",cmd), null, null)
      val out = StringBuilder()
      val r1 = BufferedReader(InputStreamReader(p.inputStream))
      val r2 = BufferedReader(InputStreamReader(p.errorStream))
      val t1 = Thread { r1.forEachLine { out.append(it).append('\n') } }
      val t2 = Thread { r2.forEachLine { out.append(it).append('\n') } }
      t1.start(); t2.start()
      val start = System.currentTimeMillis()
      while (p.isAlive && System.currentTimeMillis()-start < timeoutMs) Thread.sleep(20)
      if (p.isAlive) p.destroy()
      t1.join(150); t2.join(150)
      val code = runCatching { p.exitValue() }.getOrElse { -2 }
      code to out.toString()
    } catch (e: Throwable) { -3 to (e.message ?: "error") }
  }
}
