package com.example.navswitcher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 跑在 Shizuku（shell uid）进程中的 Service。
 * 接收一条命令字符串，执行后把 exit code + stdout + stderr 回传。
 */
class ShellUserService : Service() {

    companion object {
        private const val TAG = "ShellUserService"
        private const val MSG_RUN_CMD = 1
        private const val KEY_CMD = "cmd"
        private const val KEY_CODE = "code"
        private const val KEY_OUT = "out"
        private const val KEY_ERR = "err"

        @Volatile private var keepConn: ServiceConnection? = null

        fun bind(context: Context, onReady: (Messenger?) -> Unit) {
            Log.d(TAG, "bind() called")

            if (!Shizuku.pingBinder()) {
                Log.e(TAG, "Shizuku binder not alive")
                onReady(null); return
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Shizuku not granted")
                onReady(null); return
            }

            val cn = ComponentName(context.packageName, ShellUserService::class.java.name)
            val args = Shizuku.UserServiceArgs(cn)
                .daemon(false)
                .processNameSuffix("sh")      // 必须非空
                .debuggable(BuildConfig.DEBUG)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Log.d(TAG, "onServiceConnected: $name")
                    onReady(Messenger(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "onServiceDisconnected: $name")
                    onReady(null)
                }
            }
            keepConn = conn
            val ok = Shizuku.bindUserService(args, conn)
            Log.d(TAG, "bindUserService() returned: $ok")
        }
    }

    private val incoming = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_RUN_CMD) {
                val cmd = msg.data?.getString(KEY_CMD) ?: return
                Log.d(TAG, "handleMessage -> run: $cmd")
                val (code, out, err) = runShellWithOutput(cmd)
                val reply = Message.obtain(null, MSG_RUN_CMD).apply {
                    data = Bundle().apply {
                        putInt(KEY_CODE, code)
                        putString(KEY_OUT, out)
                        putString(KEY_ERR, err)
                    }
                }
                try {
                    msg.replyTo?.send(reply)
                    Log.d(TAG, "reply sent. code=$code")
                } catch (t: Throwable) {
                    Log.e(TAG, "reply send failed", t)
                }
            } else super.handleMessage(msg)
        }
    }
    private val messenger = Messenger(incoming)

    override fun onBind(intent: android.content.Intent?): IBinder = messenger.binder

    private fun runShellWithOutput(cmd: String): Triple<Int,String,String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        val err = BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() }
        val code = p.waitFor()
        Triple(code, out, err)
    } catch (t: Throwable) {
        Log.e(TAG, "exec error", t)
        Triple(-1, "", t.message ?: "")
    }
}
