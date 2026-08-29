package top.loongway.autoresolution.trackpad.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import top.loongway.autoresolution.trackpad.cursor.CursorView
import top.loongway.autoresolution.trackpad.cursor.SecondaryDisplayController

/**
 * 副屏触点服务：通过无障碍在副屏渲染控制触点。
 *
 * 为什么必须走无障碍（同类开源项目 DeskControl 的做法）：
 * Android 12+ 默认拦截「穿透不可信 overlay 的触摸」——应用进程自己添加的全屏
 * 透明窗口（TYPE_PRESENTATION 等）属于不可信 overlay，点击/滚动穿透它时会被
 * 副屏上的目标 App（targetSdk 31+ 默认开启拦截）直接丢弃，表现为触点能动但
 * 点击/滚动完全无效。TYPE_ACCESSIBILITY_OVERLAY 是系统认可的可信 overlay：
 * 穿透它的触摸可以正常送达副屏上的应用，且无需 Shizuku。
 *
 * 窗口布局注意：必须显式锚定左上角并适配零 insets。XREAL 的系统 insets
 * 会把全屏 overlay 整体推移数百像素（画在哪 ≠ 点在哪）；且根视图禁止使用
 * translationX/Y（会被提升为窗口位移，触点视觉位置变成 2 倍）——
 * 位置一律在 CursorView.onDraw 内按状态值直接绘制。
 */
class CursorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CursorA11y"

        /** createWindowContext(Display, type, options) 要求 Android 11+ */
        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        @Volatile
        private var instance: CursorAccessibilityService? = null

        /** 服务连接/断开回调（供界面状态行实时刷新） */
        @Volatile var onStateChanged: (() -> Unit)? = null

        /** 无障碍服务已连接（用户在系统无障碍设置中开启后生效） */
        fun isAvailable(): Boolean = instance != null && isSupported()

        fun attachCursor(display: Display): Boolean =
            instance?.attachCursorInternal(display) ?: false

        fun updateCursor(nx: Float, ny: Float, visible: Boolean) =
            instance?.updateCursorInternal(nx, ny, visible)

        fun detachCursor() = instance?.detachCursorInternal()
    }

    private var windowContext: Context? = null
    private var cursorView: CursorView? = null
    private var attachedDisplay: Display? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 触点若挂在本地兜底窗口上，热切换到可信 overlay（否则点击仍被拦截）
        SecondaryDisplayController.ensureCursorHostUpToDate()
        onStateChanged?.invoke()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        detachCursorInternal()
        if (instance === this) instance = null
        // 通知控制器触点退回本地兜底窗口
        SecondaryDisplayController.onA11yDisconnected()
        onStateChanged?.invoke()
        super.onDestroy()
    }

    fun attachCursorInternal(display: Display): Boolean {
        detachCursorInternal()
        return try {
            // 副屏专属窗口上下文：窗口只存在于目标 display 上
            val ctx = createWindowContext(
                display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null
            )
            val view = CursorView(ctx)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            ).apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                // 窗口表面必须是半透明格式：默认 OPAQUE 的全屏窗口
                // 除圆环外全是黑像素，会把副屏内容整个盖成黑屏
                format = PixelFormat.TRANSLUCENT
                // 显式锚定左上角并适配零 insets：XREAL 的系统 insets 会把
                // 全屏 overlay 整体推移数百像素（画在哪 ≠ 点在哪）
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                }
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            ctx.getSystemService(WindowManager::class.java).addView(view, lp)
            windowContext = ctx
            cursorView = view
            attachedDisplay = display
            true
        } catch (t: Throwable) {
            Log.e(TAG, "attach cursor overlay failed", t)
            false
        }
    }

    fun updateCursorInternal(nx: Float, ny: Float, visible: Boolean) {
        val view = cursorView ?: return
        val display = attachedDisplay ?: return
        // 与注入侧完全同一坐标源：getRealMetrics 的像素坐标
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        view.setPixelPosition(
            nx.coerceIn(0f, 1f) * metrics.widthPixels,
            ny.coerceIn(0f, 1f) * metrics.heightPixels
        )
        if (visible) view.show() else view.hideWithFade()
    }

    fun detachCursorInternal() {
        val view = cursorView ?: return
        cursorView = null
        val ctx = windowContext
        windowContext = null
        try {
            ctx?.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "remove cursor overlay failed", t)
        }
    }
}
