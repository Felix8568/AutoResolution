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
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import top.loongway.autoresolution.shizuku.IDisplayControl
import top.loongway.autoresolution.shizuku.IShellService
import top.loongway.autoresolution.shizuku.service.DisplayControlService

/**
 * 全局协调中心。
 *
 * - 管理 2 个 Shizuku UserService 的绑定/解绑
 * - 监听外接显示器插拔（DisplayManager），自动切换分辨率
 * - 悬浮窗拦截音量键，控制屏幕电源开关（无需 Shizuku）
 *
 * 对外暴露 [start] / [stop] / [turnScreenOff] / [turnScreenOn] 给 Android Service 调用。
 */
object ResolutionManager {

    private const val TARGET_WIDTH = 1080
    private const val TARGET_HEIGHT = 1920
    private const val TAG = "ResolutionManager"

    private lateinit var appContext: Context
    private val handler = Handler(Looper.getMainLooper())

    // Shell service (wm size)
    private var shellService: IShellService? = null
    private var shellConn: ServiceConnection? = null
    private var shellArgs: UserServiceArgs? = null

    // Display control (SurfaceControl power mode)
    private var displayControl: IDisplayControl? = null
    private var dcConn: ServiceConnection? = null
    private var dcArgs: UserServiceArgs? = null

    // Volume key window (overlay focus, no Shizuku needed)
    private var volumeKeyWindow: VolumeKeyWindowHost? = null

    private var displayManager: DisplayManager? = null

    @Volatile var isStarted = false
    @Volatile var connectedDisplayName: String? = null
    var onStateChanged: (() -> Unit)? = null

    // ---- lifecycle ----

    fun init(application: Application) {
        appContext = application.applicationContext
        Shizuku.addBinderDeadListener {
            shellService = null
            displayControl = null
        }
    }

    fun start() {
        if (isStarted) return
        isStarted = true
        bindShellService()
        bindDisplayControl()
        startVolumeKeyWindow()
        registerDisplayListener()
    }

    fun stop() {
        isStarted = false
        shellService?.exec("wm size reset")
        unregisterDisplayListener()
        destroyVolumeKeyWindow()
        unbindShellService()
        unbindDisplayControl()
    }

    fun rebind() {
        if (isStarted) {
            bindShellService()
            bindDisplayControl()
            startVolumeKeyWindow()
        }
    }

    // ---- Shell Service ----

    private fun bindShellService() {
        if (shellService != null) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        shellArgs = serviceArgs("top.loongway.autoresolution.shizuku.service.ShellService", "shell")
        shellConn = ServiceConn(
            onConnected = { binder ->
                shellService = IShellService.Stub.asInterface(binder)
                refresh()
            },
            onDisconnected = { shellService = null }
        )
        Shizuku.bindUserService(shellArgs!!, shellConn!!)
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

    // ---- Volume key window ----

    private fun startVolumeKeyWindow() {
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted, volume key control disabled")
            return
        }
        if (volumeKeyWindow == null) {
            volumeKeyWindow = VolumeKeyWindowHost(appContext) { keyCode ->
                val hasExternal = displayManager?.displays?.any { it.isExternal() } ?: return@VolumeKeyWindowHost
                if (!hasExternal) return@VolumeKeyWindowHost
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> turnScreenOff()
                    KeyEvent.KEYCODE_VOLUME_DOWN -> turnScreenOn()
                }
            }
        }
        volumeKeyWindow?.create()
    }

    private fun destroyVolumeKeyWindow() {
        volumeKeyWindow?.destroy()
        volumeKeyWindow = null
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

    // ---- Resolution ----

    fun refresh() {
        val displays = displayManager?.displays ?: return
        val ext = displays.firstOrNull { it.isExternal() }

        if (ext != null) {
            connectedDisplayName = queryDisplayName()
            shellService?.exec("wm size ${TARGET_WIDTH}x${TARGET_HEIGHT}")
        } else {
            connectedDisplayName = null
            shellService?.exec("wm size reset")
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
