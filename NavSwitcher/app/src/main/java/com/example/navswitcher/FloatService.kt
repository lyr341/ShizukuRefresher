package com.example.navswitcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

class FloatService : Service() {

    companion object {
        private const val CH_ID = "float_foreground"
        private const val NOTI_ID = 1001
        private const val TAG = "FloatService"

        // ---- 目标匹配配置（按你日志）----
        private const val GAME_PACKAGE = "cn.damai"
        // 商场页 Activity 关键字（完全类名）
        private const val SHOP_ACTIVITY = "cn.damai.commonbusiness.seatbiz.sku.qilin.ui.NcovSkuActivity"

        // 只监听这些 tag（减少日志量）；也可以改成空，用全量 logcat 在代码中过滤
        private val LOG_TAGS = listOf(
            "Transition",                // 有 state=RESUMED
            "OplusScrollToTopManager",   // 有 focus to true
            "OplusAppSwitchManagerService",
            "WindowManager"              // mCurrentFocus
        )

        // 匹配“进入/前台”强信号；也可放宽为只匹配 NcovSkuActivity，再靠冷却+前台包限制
        private val REGEX_ENTER_SHOP = Regex(
            "NcovSkuActivity.*(RESUMED|focus to true|handleAppVisible|mCurrentFocus)|(RESUMED|handleAppVisible).*NcovSkuActivity",
            RegexOption.IGNORE_CASE
        )

        // 触发去抖冷却时间
        private const val COOL_DOWN_MS = 1500L
        // 拖拽判定最小位移(px)
        private const val DRAG_SLOP = 6
        // 悬浮按钮直径(dp)
        private const val BALL_DP = 72f
        // 颜色（灰色）
        private const val BALL_COLOR = 0xFF808080.toInt()
    }

    // 主线程/IO线程
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val bg: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // 悬浮球
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: ImageView? = null
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var moved = false

    // 切换保护
    @Volatile private var isSwitching = false
    @Volatile private var lastSwitchTs = 0L

    // logcat 监控进程
    @Volatile private var logcatProc: Process? = null

    // 反射拿 Shizuku.newProcess(String[], String[], String)
    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
        } catch (_: Throwable) {
            try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
            } catch (t: Throwable) {
                Log.w(TAG, "Shizuku.newProcess not found via reflection: $t")
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundX()
            addBallX()
            startAutoMonitorIfPossible()
            Log.d(TAG, "onCreate OK, ball added & monitor started")
            Toast.makeText(this, "悬浮球服务已启动", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate error", t)
            Toast.makeText(this, "悬浮球启动失败: ${t.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun startForegroundX() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "NavSwitcher", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
        val noti = NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("悬浮球已启用")
            .setContentText("点击切换 / 自动识别界面切换")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun dp2px(dp: Float): Int {
        val d = resources.displayMetrics.density
        return max(1f, dp * d).roundToInt()
    }

    private fun addBallX() {
        if (!Settings.canDrawOverlays(this)) {
            throw IllegalStateException("未授予悬浮窗权限")
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val size = dp2px(BALL_DP)

        params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 200
            y = 600
        }

        // 圆形灰色背景
        val circle = ShapeDrawable(OvalShape()).apply {
            paint.color = BALL_COLOR
            // 让可视区域即为点击区域
            setPadding(0, 0, 0, 0)
            intrinsicWidth = size
            intrinsicHeight = size
        }

        ball = ImageView(this).apply {
            background = circle
            scaleType = ImageView.ScaleType.CENTER
            // 可按需加一个很淡的内图标；这里先不放，整块灰色都是点击区

            isClickable = true
            setOnClickListener { onBallClicked() }

            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dxF = ev.rawX - lastRawX
                        val dyF = ev.rawY - lastRawY
                        val mdx = ev.rawX - touchStartX
                        val mdy = ev.rawY - touchStartY
                        if (!moved && (kotlin.math.abs(mdx) > DRAG_SLOP || kotlin.math.abs(mdy) > DRAG_SLOP)) {
                            moved = true
                        }
                        params?.let { p ->
                            p.x += dxF.toInt()
                            p.y += dyF.toInt()
                            wm?.updateViewLayout(v, p)
                            lastRawX = ev.rawX
                            lastRawY = ev.rawY
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        // 如果发生了拖拽，则拦截点击，不触发切换
                        if (moved) return@setOnTouchListener true
                    }
                }
                false
            }
        }

        wm?.addView(ball, params)
    }

    private fun onBallClicked() {
        tryToggleWithCooldown(reason = "manual_click")
    }

    /** 统一的切换入口（带冷却&排他保护） */
    private fun tryToggleWithCooldown(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (isSwitching || now - lastSwitchTs < COOL_DOWN_MS) {
            Log.d(TAG, "skip toggle: cooling/switching, reason=$reason")
            return
        }
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予 Shizuku 权限", Toast.LENGTH_LONG).show()
            return
        }

        isSwitching = true
        Toast.makeText(this, "准备切换…", Toast.LENGTH_SHORT).show()

        isThreeButtonEnabled { isThree ->
            val cmds = if (!isThree) {
                listOf(
                    "cmd overlay enable com.android.internal.systemui.navbar.threebutton",
                    "cmd overlay disable com.android.internal.systemui.navbar.gestural",
                    "cmd statusbar restart"
                )
            } else {
                listOf(
                    "cmd overlay enable com.android.internal.systemui.navbar.gestural",
                    "cmd overlay disable com.android.internal.systemui.navbar.threebutton",
                    "cmd statusbar restart"
                )
            }

            execShell(cmds.joinToString(" && ")) { code, out, err ->
                Log.d(TAG, "toggle($reason) exit=$code\nstdout=$out\nstderr=$err")
                main.post {
                    isSwitching = false
                    lastSwitchTs = SystemClock.uptimeMillis()
                    if (code == 0) {
                        Toast.makeText(this, "切换完成", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "切换失败($code)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun isThreeButtonEnabled(cb: (Boolean) -> Unit) {
        val cmd = "cmd overlay list | grep '\\[x\\] com.android.internal.systemui.navbar.threebutton' | wc -l"
        execShell(cmd) { code, out, _ ->
            val enabled = (code == 0) && (out.trim().toIntOrNull() ?: 0) > 0
            Log.d(TAG, "threebutton enabled? $enabled, raw=$out, code=$code")
            cb(enabled)
        }
    }

    // ---------- 自动识别并触发 ----------
    private fun startAutoMonitorIfPossible() {
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "auto monitor disabled: Shizuku not running")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "auto monitor disabled: no Shizuku permission")
            return
        }
        // 起一个后台任务跑 logcat 解析
        bg.execute { runLogcatWatcher() }
    }

    private fun runLogcatWatcher() {
        try {
            // 构造 logcat 命令：只订阅关心的 tag，降低吞吐
            // 例：logcat -v brief -T 1 Transition:I OplusScrollToTopManager:I OplusAppSwitchManagerService:I WindowManager:I *:S
            val args = mutableListOf("logcat", "-v", "brief", "-T", "1")
            LOG_TAGS.forEach { t -> args += "$t:I" }
            args += "*:S"

            val proc = startProcess(args.toTypedArray())
            logcatProc = proc

            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    val l = line!!
                    // 先包含 Activity 关键字，再用正则判定（减少 regex 次数）
                    if (!l.contains("NcovSkuActivity", ignoreCase = true)) continue
                    if (!REGEX_ENTER_SHOP.containsMatchIn(l)) continue

                    // 二次确认：当前前台包是目标包
                    val fg = getTopPackageSync()
                    val ok = (fg == GAME_PACKAGE)
                    Log.d(TAG, "logcat hit, line='$l', top=$fg, ok=$ok")
                    if (ok) {
                        tryToggleWithCooldown(reason = "logcat:$SHOP_ACTIVITY")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runLogcatWatcher error", t)
        } finally {
            logcatProc?.destroyForcibly()
            logcatProc = null
        }
    }

    /** 同步拿前台包名（简化实现） */
    private fun getTopPackageSync(): String? {
        val cmd = "dumpsys activity top | grep 'ACTIVITY' | head -n 1"
        val (code, out, _) = execShellSync(cmd)
        if (code != 0) return null
        // 典型行： ACTIVITY cn.damai/.commonbusiness... pid ...
        val parts = out.lineSequence().firstOrNull()?.trim() ?: return null
        // 粗提取：第2段为包/类
        val tokens = parts.split(Regex("\\s+"))
        // 找到类似 cn.damai/xxx 的段
        val seg = tokens.firstOrNull { it.contains("/") } ?: return null
        return seg.substringBefore("/")
    }

    // ---------- Shell 执行（Shizuku 反射优先） ----------
    private fun startProcess(argv: Array<String>): Process {
        val proc: Process? = try {
            newProcessMethod?.invoke(null, argv, null, null) as? Process
        } catch (t: Throwable) {
            Log.w(TAG, "startProcess: reflection failed, fallback Runtime.exec", t)
            null
        }
        return proc ?: Runtime.getRuntime().exec(argv)
    }

    private fun execShell(cmd: String, cb: (code: Int, stdout: String, stderr: String) -> Unit) {
        io.execute {
            val (code, out, err) = execShellSync(cmd)
            main.post { cb(code, out, err) }
        }
    }

    private fun execShellSync(cmd: String): Triple<Int, String, String> {
        var code = -1
        var out = ""
        var err = ""
        try {
            val proc = startProcess(arrayOf("sh", "-c", cmd))
            val sbOut = StringBuilder()
            val sbErr = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    sbOut.appendLine(line)
                }
            }
            BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                var line: String?
                while (true) {
                    line = r.readLine() ?: break
                    sbErr.appendLine(line)
                }
            }
            code = proc.waitFor()
            out = sbOut.toString()
            err = sbErr.toString()
        } catch (t: Throwable) {
            err = t.message ?: t.toString()
            Log.e(TAG, "execShellSync error", t)
        }
        return Triple(code, out, err)
    }

    // ---------- 生命周期 ----------
    override fun onDestroy() {
        super.onDestroy()
        try { wm?.removeView(ball) } catch (_: Throwable) {}
        wm = null; ball = null; params = null

        try { logcatProc?.destroyForcibly() } catch (_: Throwable) {}
        logcatProc = null

        io.shutdownNow()
        bg.shutdownNow()
    }

    override fun onBind(intent: Intent?) = null
}
