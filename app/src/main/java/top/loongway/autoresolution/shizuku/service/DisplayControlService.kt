package top.loongway.autoresolution.shizuku.service

import android.util.Log
import android.view.Display
import top.loongway.autoresolution.shizuku.IDisplayControl
import top.loongway.autoresolution.shizuku.proxy.DisplayControlProxy
import top.loongway.autoresolution.shizuku.proxy.SurfaceControlProxy
import top.loongway.autoresolution.shizuku.proxy.WindowManagerProxy
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 屏幕电源/亮度控制器（Shizuku UserService）。
 * 通过 [SurfaceControlProxy] 反射调用 SurfaceControl 隐藏 API：
 * - 电源模式：硬关屏，触摸中断（亮度方案不可用时的降级路径）
 * - 显示亮度：-1f 关背光但电源保持开启，触摸屏照常上报（息屏触摸方案）
 * settings 接口写回 Settings.System，让系统按自己的换算重新套用亮度。
 * Android 14+ 通过 [DisplayControlProxy] 获取 display token。
 */
class DisplayControlService : IDisplayControl.Stub() {

    companion object {
        const val TAG = "DisplayControlService"
        const val POWER_MODE_OFF = SurfaceControlProxy.POWER_MODE_OFF
        const val POWER_MODE_NORMAL = SurfaceControlProxy.POWER_MODE_NORMAL

        /** 关背光（电源仍 ON）：息屏触摸专用值 */
        const val BRIGHTNESS_BACKLIGHT_OFF = -1f
        /** 最低可见亮度：恢复路径的过渡值，让屏幕先亮起来再等设置套用 */
        const val BRIGHTNESS_MIN_VISIBLE = 0f
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

    override fun setBrightnessToSurfaceControl(brightness: Float): Boolean {
        return try {
            SurfaceControlProxy.setDisplayBrightness(token, brightness)
        } catch (e: Exception) {
            Log.e(TAG, "setDisplayBrightness($brightness) failed", e)
            false
        }
    }

    override fun setBrightnessToSetting(brightness: Int): Boolean =
        execSetting("screen_brightness", brightness.toString())

    override fun setBrightnessModeToSetting(mode: Int): Boolean =
        execSetting("screen_brightness_mode", mode.toString())

    /**
     * 让输入法显示在 [displayId] 外接屏上：把该屏的 IME 策略改成与主屏一致
     * （策略的常量数值没有公开，主屏永远是"在本屏显示"，运行时读它的值做参照）。
     * 部分 ROM 删掉了对应的 shell 命令，但底层接口通常还在；
     * 策略由系统按显示器持久化，重新插拔不用重新设置。
     */
    override fun showImeOnDisplay(displayId: Int): Boolean {
        return try {
            val local = WindowManagerProxy.getDisplayImePolicy(Display.DEFAULT_DISPLAY)
            WindowManagerProxy.setDisplayImePolicy(displayId, local)
            Log.i(TAG, "ime policy display $displayId -> $local (copied from default display)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "showImeOnDisplay($displayId) failed (API removed or not permitted)", e)
            false
        }
    }

    private fun execSetting(key: String, value: String): Boolean {
        return try {
            val p = Runtime.getRuntime()
                .exec(arrayOf("sh", "-c", "settings put system $key $value"))
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "settings put $key $value failed", e)
            false
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy")
        exitProcess(0)
    }
}
