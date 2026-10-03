@file:Suppress("PrivateApi")

package top.loongway.autoresolution.shizuku.proxy

import android.os.IBinder
import java.lang.reflect.Method

/**
 * [android.view.SurfaceControl] 隐藏 API 的反射代理。
 * 用于 Android 10-13：获取 display token 和控制屏幕电源模式。
 * Android 14+ 的 display token 获取见 [DisplayControlProxy]。
 */
object SurfaceControlProxy {

    private val CLASS: Class<*> by lazy { Class.forName("android.view.SurfaceControl") }

    const val POWER_MODE_OFF = 0
    const val POWER_MODE_NORMAL = 2

    private val getInternalDisplayToken: Method by lazy {
        CLASS.getMethod("getInternalDisplayToken")
    }

    @Deprecated("Removed since Android 14")
    fun getInternalDisplayToken(): IBinder {
        return getInternalDisplayToken.invoke(null) as IBinder
    }

    private val getBuiltInDisplay: Method by lazy {
        CLASS.getMethod("getBuiltInDisplay", Int::class.java)
    }

    @Deprecated("Removed since Android 10")
    fun getBuiltInDisplay(): IBinder {
        return getBuiltInDisplay.invoke(null, 0) as IBinder
    }

    private val setDisplayPowerMode: Method by lazy {
        CLASS.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.java)
    }

    fun setDisplayPowerMode(displayToken: IBinder?, mode: Int) {
        setDisplayPowerMode.invoke(null, displayToken, mode)
    }

    private val setDisplayBrightness: Method by lazy {
        CLASS.getMethod("setDisplayBrightness", IBinder::class.java, Float::class.java)
    }

    /**
     * 设置显示亮度（Android 12 起提供）。brightness 取 0.0f-1.0f，-1.0f = 关闭背光。
     * 只关背光、不改电源模式：系统仍认为屏幕点亮，触摸屏照常上报——
     * 这是「息屏触摸」的基础；不可用时抛异常，由调用方降级为硬关屏。
     */
    fun setDisplayBrightness(displayToken: IBinder?, brightness: Float): Boolean {
        return setDisplayBrightness.invoke(null, displayToken, brightness) as Boolean
    }

}
