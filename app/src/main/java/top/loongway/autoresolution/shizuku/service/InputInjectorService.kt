package top.loongway.autoresolution.shizuku.service

import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import top.loongway.autoresolution.shizuku.IInputInjector
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * 触摸事件注入器（Shizuku UserService，以 shell uid 运行，天然具备 INJECT_EVENTS 权限）。
 *
 * 注入本身没有公开 SDK：需要反射 `injectInputEvent` 隐藏方法，且该 API 在
 * Android 14（API 34）从 InputManager 迁移到了 InputManagerGlobal，
 * 因此这里按版本做反射分派，并用元反射解除 hidden API 限制。
 */
class InputInjectorService : IInputInjector.Stub() {

    companion object {
        private const val TAG = "InputInjectorService"
        /** injectInputEvent 的异步模式：不等目标窗口处理完成，注入延迟最低 */
        private const val MODE_ASYNC = 0
        /** 一次点击 DOWN→UP 的间隔（ms），模拟真实按压时长 */
        private const val TAP_DURATION_MS = 50L
    }

    // 反射产物缓存：进程内只需解析一次
    private var injector: Any? = null            // InputManagerGlobal(Android 14+) 或 InputManager 实例
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean {
        try {
            if (!applyDisplayId(event, displayId)) return false
            return inject(event)
        } finally {
            // 事件所有权已移交服务端（见 AIDL 注释），注入完负责回收
            event.recycle()
        }
    }

    /** 点击 = 同一 downTime 的 ACTION_DOWN + ACTION_UP 两个采样点 */
    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = buildTouchEvent(now, now, MotionEvent.ACTION_DOWN, x, y)
        val up = buildTouchEvent(now, now + TAP_DURATION_MS, MotionEvent.ACTION_UP, x, y)
        val ok = try {
            applyDisplayId(down, displayId) && applyDisplayId(up, displayId) &&
                inject(down) && inject(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        return ok
    }

    /** 构造标准触摸事件（手指语义），与系统 input 命令的注入行为一致 */
    private fun buildTouchEvent(now: Long, eventTime: Long, action: Int, x: Int, y: Int): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.toFloat()
            this.y = y.toFloat()
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            now, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0,           // metaState
            0,           // buttonState
            1f, 1f,      // xPrecision / yPrecision
            0,           // deviceId
            0,           // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,
            0            // flags
        )
    }

    /**
     * 滚轮注入：ACTION_SCROLL 是自足事件（无需 DOWN/UP 配对），
     * 滚动量通过 PointerCoords 的滚轮轴携带：
     *   AXIS_VSCROLL：+vTicks = 内容向下翻一页方向（等同滚轮向下滚）
     *   AXIS_HSCROLL：+hTicks = 内容向右翻一页方向（等同滚轮向右倾斜）
     */
    override fun injectScroll(displayId: Int, x: Int, y: Int, vTicks: Float, hTicks: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE // 滚轮属鼠标/触控板语义
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.toFloat()
            this.y = y.toFloat()
            pressure = 1f
            size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vTicks)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hTicks)
        }
        val scroll = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(props), arrayOf(coords),
            0,           // metaState
            0,           // buttonState
            1f, 1f,      // xPrecision / yPrecision
            0,           // deviceId（注入事件固定 0 即可）
            0,           // edgeFlags
            InputDevice.SOURCE_MOUSE,
            0            // flags
        )
        val ok = try {
            if (!applyDisplayId(scroll, displayId)) false else inject(scroll)
        } finally {
            scroll.recycle()
        }
        return ok
    }

    // ---- 反射核心 ----

    /**
     * 把事件定向到指定显示器。返回 false = 无法定向（隐藏 API 缺失/调用失败），
     * 此时调用方必须放弃本次注入：未定向的事件会缺省落到主屏（display 0），
     * 这种“看似成功”的错屏注入正是「点击无效且不触发 shell 降级」的元凶。
     */
    private fun applyDisplayId(event: InputEvent, displayId: Int): Boolean {
        val method = setDisplayIdMethod ?: runCatching {
            InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
        }.getOrNull()?.also { setDisplayIdMethod = it }
        if (method == null) {
            Log.w(TAG, "InputEvent.setDisplayId unavailable, abort injection")
            return false
        }
        return try {
            method.invoke(event, displayId)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "setDisplayId failed, abort injection", e)
            false
        }
    }

    private fun inject(event: InputEvent): Boolean = try {
        ensureInjector()
        injectMethod!!.invoke(injector, event, MODE_ASYNC) as Boolean
    } catch (e: Throwable) {
        Log.e(TAG, "injectInputEvent failed", e)
        false
    }

    /** 按系统版本定位 injectInputEvent 的宿主对象并缓存 */
    private fun ensureInjector() {
        if (injectMethod != null) return
        exemptHiddenApi() // 先解除 hidden API 限制，否则下方反射可能被系统拦截
        injector = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 起注入逻辑迁移到 InputManagerGlobal
            Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance").invoke(null)
        } else {
            InputManager::class.java.getMethod("getInstance").invoke(null)
        }
        injectMethod = injector!!.javaClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
        )
    }

    /**
     * 解除 Android 9+ 的 hidden API 限制（元反射，FreeReflection 思路）：
     * 先反射拿到不受限制的 Class.getDeclaredMethod，再用它去获取受限的
     * VMRuntime.setHiddenApiExemptions，把本进程整体豁免（"L" 前缀覆盖绝大多数类）。
     * 失败不影响功能：若目标方法本身不在黑名单，仍可正常反射。
     */
    private fun exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val classClass = Class::class.java
            // 元反射入口：Class.getDeclaredMethod 自身（获取它这一步不受黑名单限制）
            // 第二个参数类型是 Class<?>...，用数组实例的 javaClass 取其 Class 对象
            val getDeclaredMethod = classClass.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>().javaClass
            )
            // 定位 VMRuntime 与它的两个受限方法
            val vmRuntimeClass = classClass
                .getMethod("forName", String::class.java)
                .invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setExempts = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(arrayOf<String>().javaClass) // String[] 的 Class 对象
            ) as Method
            // 豁免所有 "L" 开头的类签名 = 全量豁免
            setExempts.invoke(getRuntime.invoke(null), arrayOf("L"))
        } catch (t: Throwable) {
            Log.w(TAG, "exemptHiddenApi failed", t)
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy requested, exiting")
        exitProcess(0)
    }
}
