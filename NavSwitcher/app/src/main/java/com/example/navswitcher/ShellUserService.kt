package com.example.navswitcher

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

// 运行在 Shizuku 提供的“高权限”进程里的服务实现
class ShellUserService : ISimpleShell.Stub() {

    override fun runCmd(cmd: String): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            // 读一遍输出，避免缓冲阻塞（可选）
            BufferedReader(InputStreamReader(p.inputStream)).use { r -> while (r.readLine() != null) {} }
            BufferedReader(InputStreamReader(p.errorStream)).use { r -> while (r.readLine() != null) {} }
            p.waitFor()
            p.exitValue()
        } catch (_: Throwable) {
            -1
        }
    }

    companion object {
        // 绑定到 UserService，拿到 ISimpleShell
        fun bind(context: Context, callback: (ISimpleShell?) -> Unit) {
            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn).daemon(false)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    callback(ISimpleShell.Stub.asInterface(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    callback(null)
                }
            }
            Shizuku.bindUserService(args, conn)
        }
    }
}
