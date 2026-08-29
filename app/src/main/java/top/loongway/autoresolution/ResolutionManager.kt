package top.loongway.autoresolution

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import top.loongway.autoresolution.shizuku.IDisplayControl
import top.loongway.autoresolution.shizuku.IShellService
import top.loongway.autoresolution.shizuku.service.DisplayControlService

/** 全局协调中心：管理 Shizuku UserService 绑定、显示器监听与屏幕电源控制。 */
object ResolutionManager {

    private const val TAG = "ResolutionManager"
    private const val PREFS_NAME = "trackpad_settings"
    private const val KEY_AUTO_BLACK_SCREEN = "auto_black_screen"

    /** 检测到外接显示器时自动黑屏（仅外接显示器输出） */
    var autoBlackScreen = false
        private set

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    // Shell service（权限授予 / input 注入降级）
    private var shellService: IShellService? = null
    private var shellConn: ServiceConnection? = null
    private var shellArgs: UserServiceArgs? = null

    // Display control（SurfaceControl 电源控制）
    private var displayControl: IDisplayControl? = null
    private var dcConn: ServiceConnection? = null
    private var dcArgs: UserServiceArgs? = null

    private var displayManager: DisplayManager? = null

    @Volatile var isStarted = false
    @Volatile var connectedDisplayName: String? = null
    var onStateChanged: (() -> Unit)? = null

    // ---- lifecycle ----

    fun init(application: Application) {
        appContext = application.applicationContext
        loadSettings()
        Shizuku.addBinderDeadListener {
            shellService = null
            displayControl = null
        }
    }

    /** 切换「检测到显示器自动黑屏」并持久化；显示器已连接时立即生效 */
    fun setAutoBlackScreen(enabled: Boolean) {
        autoBlackScreen = enabled
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_BLACK_SCREEN, enabled)
            .apply()
        refresh()
    }

    private fun loadSettings() {
        autoBlackScreen = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_BLACK_SCREEN, false)
    }

    fun start() {
        if (isStarted) return
        if (!PermissionChecker.check(appContext).allGranted) {
            Log.w(TAG, "permissions not ready, monitor not started")
            return
        }
        isStarted = true
        bindShellService()
        bindDisplayControl()
        registerDisplayListener()
    }

    fun stop() {
        isStarted = false
        unregisterDisplayListener()
        unbindShellService()
        unbindDisplayControl()
    }

    fun rebind() {
        if (isStarted) {
            bindShellService()
            bindDisplayControl()
        }
    }

    /** 通过 Shizuku 自动授予通知/电池优化权限（幂等），完成后主线程回调 [onDone] */
    fun ensureShizukuPermissions(onDone: (() -> Unit)? = null) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
        val existing = shellService
        if (existing != null) {
            // exec 为同步阻塞，放到后台线程执行
            Thread {
                grantPermissions(existing)
                handler.post { onDone?.invoke() }
            }.start()
        } else {
            bindShellService(onReady = onDone) // 绑定成功后执行授予并回调
        }
    }

    // ---- Shell Service ----

    /**
     * 后台线程执行 shell 命令（触控板事件注入等低频提权操作）。
     * ShellService 未绑定时先尝试懒绑定（后台监听未启动也能用触控板）；
     * 冷启动首轮命令可能因连接未建立而丢弃，属预期行为。
     */
    fun execShellAsync(command: String, onResult: ((String) -> Unit)? = null) {
        if (shellService == null) bindShellService()
        val shell = shellService
        if (shell == null) {
            Log.w(TAG, "shell service not ready, drop command: $command")
            // 必须回调失败，让调用方（如应用启动器）能走自己的降级路线
            onResult?.let { cb -> handler.post { cb("error: shell service not ready") } }
            return
        }
        Thread {
            val result = shell.exec(command)
            if (onResult != null) handler.post { onResult(result) }
        }.start()
    }

    private fun bindShellService(onReady: (() -> Unit)? = null) {
        if (shellService != null) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        shellArgs = serviceArgs("top.loongway.autoresolution.shizuku.service.ShellService", "shell")
        shellConn = ServiceConn(
            onConnected = { binder ->
                shellService = IShellService.Stub.asInterface(binder)
                grantPermissions(shellService!!)
                handler.post {
                    refresh()
                    onReady?.invoke()
                }
            },
            onDisconnected = { shellService = null }
        )
        Shizuku.bindUserService(shellArgs!!, shellConn!!)
    }

    /** 静默授予通知权限（pm grant 才能联动通知开关）与电池优化白名单，幂等 */
    private fun grantPermissions(shell: IShellService) {
        val pkg = appContext.packageName
        Log.d(TAG, "granting system permissions for $pkg via Shizuku")
        Log.d(TAG, shell.exec("pm grant $pkg android.permission.POST_NOTIFICATIONS"))
        Log.d(TAG, shell.exec("cmd deviceidle whitelist +$pkg"))
    }

    private fun unbindShellService() {
        val a = shellArgs ?: return; val c = shellConn ?: return
        Shizuku.unbindUserService(a, c, true)
        shellArgs = null; shellConn = null; shellService = null
    }

    // ---- Display Control ----

    private fun bindDisplayControl() {
        if (displayControl != null) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        dcArgs = serviceArgs(
            "top.loongway.autoresolution.shizuku.service.DisplayControlService",
            DisplayControlService.TAG
        )
        dcConn = ServiceConn(
            onConnected = { binder -> displayControl = IDisplayControl.Stub.asInterface(binder) },
            onDisconnected = { displayControl = null }
        )
        Shizuku.bindUserService(dcArgs!!, dcConn!!)
    }

    private fun unbindDisplayControl() {
        val a = dcArgs ?: return; val c = dcConn ?: return
        Shizuku.unbindUserService(a, c, true)
        dcArgs = null; dcConn = null; displayControl = null
    }

    // ---- Screen control ----

    fun turnScreenOff() {
        displayControl?.setPowerModeToSurfaceControl(DisplayControlService.POWER_MODE_OFF)
    }

    fun turnScreenOn() {
        displayControl?.setPowerModeToSurfaceControl(DisplayControlService.POWER_MODE_NORMAL)
    }

    // ---- Display listener ----

    private fun registerDisplayListener() {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        dm.registerDisplayListener(listener, handler)
    }

    private fun unregisterDisplayListener() {
        displayManager?.unregisterDisplayListener(listener)
        displayManager = null
    }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = refresh()
        override fun onDisplayRemoved(id: Int) = refresh()
        override fun onDisplayChanged(id: Int) = Unit
    }

    // ---- State ----

    fun refresh() {
        val displays = displayManager?.displays ?: return
        val ext = displays.firstOrNull { it.isExternal() }

        if (ext != null) {
            connectedDisplayName = queryDisplayName()
            // 开关开启时：外接显示器接入即黑屏（仅外接显示器输出）
            if (autoBlackScreen) turnScreenOff()
        } else {
            connectedDisplayName = null
            turnScreenOn()
        }
        onStateChanged?.invoke()
    }

    private fun queryDisplayName(): String? {
        val raw = shellService?.exec(
            "dumpsys SurfaceFlinger 2>/dev/null | grep displayName | grep -v 'displayName=\"\"' | cut -d'\"' -f2"
        ) ?: return null
        return raw.substringAfter("exit=0\n").lines().firstOrNull { it.isNotBlank() }
    }

    // ---- Helpers ----

    private fun serviceArgs(className: String, tag: String) = UserServiceArgs(
        ComponentName(appContext.packageName, className)
    ).tag(tag).processNameSuffix(tag).daemon(false).version(1)

    private class ServiceConn(
        private val onConnected: (IBinder) -> Unit,
        private val onDisconnected: () -> Unit,
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binder?.let { onConnected(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { onDisconnected() }
    }

    private fun Display.isExternal(): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return false
        return flags and Display.FLAG_PRESENTATION != 0
                || name.contains("HDMI", ignoreCase = true)
                || name.contains("DP", ignoreCase = true)
    }
}
