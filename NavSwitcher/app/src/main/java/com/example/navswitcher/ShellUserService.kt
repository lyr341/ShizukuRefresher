package com.example.navswitcher

import android.os.IBinder
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

// 这是在“shell（2000）或 root（0）身份”的独立进程里运行的 Service
class ShellUserService : ISimpleShell.Stub() {
    override fun runCmd(cmd: String): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh","-c", cmd))
            // 读一读输出，避免缓冲阻塞（可选）
            BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (r.readLine() != null) {} }
            BufferedReader(InputStreamReader(p.errorStream)).use { r -> while (r.readLine() != null) {} }
            p.waitFor()
            p.exitValue()
        } catch (e: Throwable) {
            -1
        }
    }

    companion object {
        // 绑定 UserService，拿到 IBinder（ISimpleShell）
        fun bind(callback: (ISimpleShell?) -> Unit) {
            val args = Shizuku.UserServiceArgs(
                ShellUserService::class.java.name
            ).daemon(false) // 需要时再拉起
            Shizuku.bindUserService(args, object : Shizuku.UserServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
                    callback(ISimpleShell.Stub.asInterface(binder))
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    callback(null)
                }
            })
        }
    }
}
