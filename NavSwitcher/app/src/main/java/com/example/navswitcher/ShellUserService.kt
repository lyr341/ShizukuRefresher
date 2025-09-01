package com.example.navswitcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ShellUserService : Service() {

    companion object {
        private const val TAG = "ShellUserService"

        // IPC 协议
        private const val MSG_RUN_CMD = 1
        private const val MSG_RUN_CMDS = 2
        private const val KEY_CMD = "cmd"
        private const val KEY_CMDS = "cmds"
        private const val KEY_CODE = "code"

        @Volatile private var keepConn: ServiceConnection? = null

        /**
         * 绑定 Shizuku UserService：
         * - 需要 Shizuku 运行且授权通过
         * - 失败时 onReady(null)
         */
        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not running")
                onReady(null); return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku permission not granted")
                onReady(null); return
            }

            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("sh")  // 重要：非空
                .debuggable(false)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    onReady(Messenger(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    onReady(null)
                }
            }
            keepConn = conn
            Shizuku.bindUserService(args, conn)
        }

        fun unbind(context: Context) {
            keepConn?.let {
                try { Shizuku.unbindUserService(it) } catch (_: Throwable) {}
            }
            keepConn = null
        }
    }

    // 用后台线程执行命令，避免阻塞主线程
    private val execPool = Executors.newCachedThreadPool()

    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_RUN_CMD -> {
                    val cmd = msg.data?.getString(KEY_CMD)
                    if (cmd.isNullOrBlank()) return
                    runAsync(listOf(cmd)) { code ->
                        reply(msg, code)
                    }
                }
                MSG_RUN_CMDS -> {
                    val cmds = msg.data?.getStringArrayList(KEY_CMDS)?.filter { it.isNotBlank() } ?: emptyList()
                    if (cmds.isEmpty()) return
                    runAsync(cmds) { code ->
                        reply(msg, code)
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }
    private val messenger = Messenger(incoming)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        super.onDestroy()
        execPool.shutdownNow()
    }

    private fun reply(src: Message, code: Int) {
        val reply = Message.obtain(null, src.what).apply {
            data = Bundle().apply { putInt(KEY_CODE, code) }
        }
        try { src.replyTo?.send(reply) } catch (_: Throwable) {}
    }

    /**
     * 依次执行多条命令，返回最后一条的退出码。
     * 全程使用 Shizuku.newProcess 以 shell 权限运行。
     */
    private fun runAsync(cmds: List<String>, onDone: (Int) -> Unit) {
        execPool.submit {
            var last = -1
            for (c in cmds) {
                last = runOne(c)
                if (last != 0) break
            }
            onDone(last)
        }
    }

    /**
     * 执行单条命令并吞掉 stdout/stderr，避免阻塞。
     */
    private fun runOne(cmd: String): Int {
        return try {
            // 使用 /system/bin/sh -c "cmd"
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)

            // 异步吞 stdout/stderr，防止缓冲区填满阻塞 waitFor
            val f1: Future<*> = execPool.submit {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    while (r.readLine() != null) { /* ignore */ }
                }
            }
            val f2: Future<*> = execPool.submit {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    while (r.readLine() != null) { /* ignore */ }
                }
            }

            val code = proc.waitFor()
            // 确保线程回收
            try { f1.get() } catch (_: Throwable) {}
            try { f2.get() } catch (_: Throwable) {}

            code
        } catch (t: Throwable) {
            Log.e(TAG, "runOne failed: $cmd", t)
            -1
        }
    }
}
