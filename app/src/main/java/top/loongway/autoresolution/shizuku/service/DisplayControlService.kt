package top.loongway.autoresolution.shizuku.service

import android.util.Log
import top.loongway.autoresolution.shizuku.IDisplayControl
import top.loongway.autoresolution.shizuku.proxy.DisplayControlProxy
import top.loongway.autoresolution.shizuku.proxy.SurfaceControlProxy
import kotlin.system.exitProcess

/**
 * 屏幕电源控制器（Shizuku UserService）。
 * 通过 [SurfaceControlProxy] 反射调用 SurfaceControl 隐藏 API，控制显示电源的开启/关闭。
 * Android 14+ 通过 [DisplayControlProxy] 获取 display token。
 */
class DisplayControlService : IDisplayControl.Stub() {

    companion object {
        const val TAG = "DisplayControlService"
        const val POWER_MODE_OFF = SurfaceControlProxy.POWER_MODE_OFF
        const val POWER_MODE_NORMAL = SurfaceControlProxy.POWER_MODE_NORMAL
    }

    private val token by lazy { DisplayControlProxy.getToken() }

    override fun setPowerModeToSurfaceControl(mode: Int): Boolean {
        return try {
            SurfaceControlProxy.setDisplayPowerMode(token, mode)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setPowerModeToSurfaceControl failed", e)
            false
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy")
        exitProcess(0)
    }
}
