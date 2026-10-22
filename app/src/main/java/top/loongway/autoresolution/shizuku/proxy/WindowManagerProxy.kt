@file:Suppress("PrivateApi", "BlockedPrivateApi")

package top.loongway.autoresolution.shizuku.proxy

import android.os.IBinder
import java.lang.reflect.Method

/**
 * IWindowManager 反射封装：读写某个显示器上输入法的显示策略。
 *
 * 输入法显示在哪块屏由目标屏的策略决定。这个策略的常量数值没有公开到 SDK，
 * 所以不写死任何数字，而是运行时读主屏的值做参照（主屏永远是"在本屏显示"）。
 * 部分 ROM 删掉了配套的 shell 命令（wm set-display-ime-policy 会报 Unknown
 * command），但底层接口通常还在，以 shell 身份直接调用即可。整个 ROM 彻底
 * 删除该接口时，解析阶段就会失败，调用方拿到异常后按不支持处理。
 */
object WindowManagerProxy {

    /** 解析成功后的可用接口；ROM 删除了该 API 时为 null */
    private val api: Api? by lazy {
        try {
            val service = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as IBinder
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, service)!!
            val iwm = Class.forName("android.view.IWindowManager")
            Api(
                wm = wm,
                getPolicy = iwm.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType),
                setPolicy = iwm.getMethod(
                    "setDisplayImePolicy",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                ),
            )
        } catch (t: Throwable) {
            null
        }
    }

    private class Api(
        val wm: Any,
        val getPolicy: Method,
        val setPolicy: Method,
    )

    /** 读取 [displayId] 屏的 IME 策略值；接口不存在时抛 [UnsupportedOperationException] */
    fun getDisplayImePolicy(displayId: Int): Int {
        val a = api ?: throw UnsupportedOperationException("IWindowManager IME policy API unavailable")
        return a.getPolicy.invoke(a.wm, displayId) as Int
    }

    /** 设置 [displayId] 屏的 IME 策略值，取值与主屏读到的值同属一套 */
    fun setDisplayImePolicy(displayId: Int, policy: Int) {
        val a = api ?: throw UnsupportedOperationException("IWindowManager IME policy API unavailable")
        a.setPolicy.invoke(a.wm, displayId, policy)
    }
}
