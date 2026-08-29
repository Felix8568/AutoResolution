package top.loongway.autoresolution.trackpad.cursor

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import top.loongway.autoresolution.trackpad.gesture.TrackpadZone
import top.loongway.autoresolution.trackpad.launcher.LauncherActivity
import top.loongway.autoresolution.trackpad.a11y.CursorAccessibilityService
import top.loongway.autoresolution.trackpad.inject.InputInjector
import top.loongway.autoresolution.trackpad.inject.ShellInputInjector

/**
 * 副屏控制器（单例）：与 ResolutionManager 完全解耦，自带显示器监听。
 *
 * 职责：
 * 1. 副屏热插拔 → 挂载/销毁 [CursorPresentation]（触点窗口全程穿透触摸）；
 * 2. 接收主屏触控板指令 → 平滑更新触点 / 注入真实点击与滚轮；
 * 3. 3 秒无触摸 → 触点渐隐（Handler 防抖计时器，见 [onUserActive]）。
 *
 * 线程模型：DisplayListener 与计时器都挂在主线程 Handler 上，
 * 触摸事件本身也在主线程，因此所有公开方法天然串行、无需加锁。
 */
object SecondaryDisplayController {

    private const val TAG = "SecondaryDisplayCtrl"

    /** 无触摸多久后隐藏触点 */
    private const val HIDE_DELAY_MS = 3_000L

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var displayManager: DisplayManager? = null

    /**
     * 触点窗口是否由无障碍服务承载（TYPE_ACCESSIBILITY_OVERLAY，可信 overlay）。
     * 应用进程自己的全屏透明窗口会让穿透触摸被目标 App 当作不可信而丢弃
     * （Android 12+ 默认拦截，表现为触点能动但点击/滚动无效），因此优先使用；
     * 无障碍未开启时才本地兜底（此时点击/滚动可能被目标 App 拦截）。
     */
    private var a11yCursor = false

    /** 当前副屏 displayId（INVALID_DISPLAY = 未连接） */
    var displayId: Int = Display.INVALID_DISPLAY
        private set

    /** 触点归一化坐标（[0,1]，跨屏传递的唯一真值，与两侧分辨率无关） */
    private var nx = 0.5f
    private var ny = 0.5f

    // ---- 滑动手势（虚拟手指）状态 ----
    private var swiping = false
    private var swipeZone = TrackpadZone.CENTER
    private var swipeDownTime = 0L
    private var swipeX = 0f
    private var swipeY = 0f

    private val cursorView: CursorView? get() = LauncherActivity.instanceCursorView()

    fun hasSecondaryDisplay(): Boolean = displayId != Display.INVALID_DISPLAY

    // ---- 生命周期 ----

    fun start(context: Context) {
        if (displayManager != null) return
        appContext = context.applicationContext
        val dm = appContext!!.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        // 回调经主线程 handler 派发 → 本控制器所有方法都运行在主线程
        dm.registerDisplayListener(displayListener, handler)
        attachIfPossible() // 兜底：start 之前副屏可能已经连接
    }

    fun stop() {
        displayManager?.unregisterDisplayListener(displayListener)
        displayManager = null
        detach()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = attachIfPossible()
        override fun onDisplayRemoved(id: Int) {
            if (id == displayId) detach()
        }

        override fun onDisplayChanged(id: Int) = Unit
    }

    private fun attachIfPossible() {
        if (displayId != Display.INVALID_DISPLAY) return
        val ctx = appContext ?: return
        val dm = displayManager ?: return
        val display = dm.displays.firstOrNull { it.isTrackpadTarget() } ?: return
        displayId = display.displayId
        // 触点优先挂无障碍可信 overlay，不可用时内嵌在启动页 Activity 里兜底
        a11yCursor = CursorAccessibilityService.isAvailable() &&
            CursorAccessibilityService.attachCursor(display)
        // 副屏启动页：普通 Activity，应用盖上来、返回回落（Presentation 会压住应用）
        LauncherActivity.startOnDisplay(ctx, display.displayId)
    }

    private fun detach() {
        if (a11yCursor) CursorAccessibilityService.detachCursor()
        a11yCursor = false
        LauncherActivity.finishInstance()
        displayId = Display.INVALID_DISPLAY
    }

    /**
     * 无障碍服务刚连接（或主界面回到前台）时调用：
     * 触点若还挂在内嵌兜底视图上且无障碍已可用 → 重建副屏挂载，热切换到可信 overlay。
     */
    fun ensureCursorHostUpToDate() {
        if (displayId == Display.INVALID_DISPLAY || a11yCursor) return
        if (!CursorAccessibilityService.isAvailable()) return
        detach()
        attachIfPossible()
    }

    /** 无障碍服务断开（用户关闭/服务崩溃）：触点退回本地兜底窗口 */
    fun onA11yDisconnected() {
        if (!a11yCursor) return
        a11yCursor = false
        attachIfPossible()
    }

    /** 副屏判定：非主屏且（支持 Presentation 内容 或 名字带 HDMI/DP） */
    private fun Display.isTrackpadTarget(): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return false
        return flags and Display.FLAG_PRESENTATION != 0
            || name.contains("HDMI", ignoreCase = true)
            || name.contains("DP", ignoreCase = true)
    }

    /** 启动页是否正在副屏前台（resumed；被其它应用覆盖时为 false） */
    fun isLauncherTop(): Boolean = LauncherActivity.isTop()

    /** 从启动页启动到副屏的应用包名（force-stop 关闭入口用） */
    @Volatile
    var secondaryAppPackage: String? = null
        private set

    /** 记录副屏当前打开的应用（启动页网格点击时回调） */
    fun onAppLaunched(pkg: String) {
        secondaryAppPackage = pkg
    }

    /** 直接关闭副屏当前应用（am force-stop），关闭后自然回落到启动页 */
    fun closeSecondaryApp() {
        val pkg = secondaryAppPackage ?: return
        top.loongway.autoresolution.ResolutionManager.execShellAsync("am force-stop $pkg")
    }

    /** 启动页回到前台（副屏应用已自行退出）：清空应用记录 */
    fun onLauncherResumed() {
        secondaryAppPackage = null
    }

    // ---- 触控板指令入口（TrackpadActivity 转发进来） ----

    /** 触点相对移动：叠加归一化增量并 clamp 到屏幕范围 */
    fun onCursorMove(dx: Float, dy: Float) {
        onUserActive()
        val view = cursorView
        nx = (nx + dx).coerceIn(0f, 1f)
        ny = (ny + dy).coerceIn(0f, 1f)
        if (a11yCursor) CursorAccessibilityService.updateCursor(nx, ny, true)
        else view?.setNormalizedPosition(nx, ny)
    }

    /**
     * 轻点 → 在触点当前位置注入真实点击。
     * 触控板惯例：点击发生在光标处而非手指按下处，因此不使用回调携带的位置。
     */
    fun onTap() {
        onUserActive()
        val (px, py) = cursorPixelPosition() ?: return
        // 反射路线优先；未绑定/注入失败时降级 shell 路线
        if (!InputInjector.tap(displayId, px, py)) {
            ShellInputInjector.tap(displayId, px, py)
        }
    }

    /**
     * 滑动手势（边缘区）：只按按压位置重定位触点（侧边区只动纵轴、底部区只动横轴）
     * 并记录起点，**不立即注入按下**。真正的 ACTION_DOWN 推迟到首次移动时（见 [onSwipeMove]），
     * 这样"按下不动直接抬起"不会在副屏产生任何事件（侧边不响应点击）。
     */
    fun onSwipeBegin(zone: TrackpadZone, pressNx: Float, pressNy: Float) {
        onUserActive()
        if (!hasSecondaryDisplay()) return
        when (zone) {
            TrackpadZone.LEFT_EDGE, TrackpadZone.RIGHT_EDGE -> ny = pressNy.coerceIn(0f, 1f)
            TrackpadZone.BOTTOM_EDGE -> nx = pressNx.coerceIn(0f, 1f)
            TrackpadZone.CENTER -> Unit
        }
        if (a11yCursor) CursorAccessibilityService.updateCursor(nx, ny, true)
        else cursorView?.setNormalizedPosition(nx, ny)

        val (px, py) = cursorPixelPosition() ?: return
        swipeZone = zone
        swipeDownTime = SystemClock.uptimeMillis()
        swipeX = px.toFloat()
        swipeY = py.toFloat()
        swiping = false // 待滑动：首次移动才注入 ACTION_DOWN
    }

    /** 滑动移动：首次移动先补注入 ACTION_DOWN，之后归一化增量跟手注入 MOVE。
     *  轴向锁定：侧边区只走纵轴、底部区只走横轴，保证可以沿边缘连续长滑。 */
    fun onSwipeMove(dx: Float, dy: Float) {
        if (!hasSecondaryDisplay()) return
        onUserActive()
        if (!swiping) {
            swiping = true
            injectSwipe(MotionEvent.ACTION_DOWN)
        }
        val (w, h) = displayRealSize() ?: return
        // 轴向过滤：非本区轴向的增量为 0
        val (edx, edy) = when (swipeZone) {
            TrackpadZone.LEFT_EDGE, TrackpadZone.RIGHT_EDGE -> 0f to dy
            TrackpadZone.BOTTOM_EDGE -> dx to 0f
            TrackpadZone.CENTER -> dx to dy
        }
        swipeX = (swipeX + edx * w).coerceIn(0f, w - 1f)
        swipeY = (swipeY + edy * h).coerceIn(0f, h - 1f)
        // 触点跟随"虚拟手指"，视觉一致
        nx = (swipeX / w).coerceIn(0f, 1f)
        ny = (swipeY / h).coerceIn(0f, 1f)
        if (a11yCursor) CursorAccessibilityService.updateCursor(nx, ny, true)
        else cursorView?.setNormalizedPosition(nx, ny)
        injectSwipe(MotionEvent.ACTION_MOVE)
    }

    fun onSwipeEnd() {
        if (!swiping) return
        swiping = false
        injectSwipe(MotionEvent.ACTION_UP)
    }

    /** 注入单个触摸采样点（同一 downTime 串成一次完整手势） */
    private fun injectSwipe(action: Int) {
        if (!hasSecondaryDisplay()) return
        val event = MotionEvent.obtain(
            swipeDownTime, SystemClock.uptimeMillis(), action, swipeX, swipeY, 0 /* metaState */
        )
        if (!InputInjector.inject(event, displayId)) {
            Log.w(TAG, "swipe inject failed (action=$action)")
        }
    }

    /** 副屏真实像素尺寸（宽, 高） */
    private fun displayRealSize(): Pair<Int, Int>? {
        val display = displayManager?.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** 垂直滚动：+ticks = 内容向下翻 */
    fun onVerticalScroll(ticks: Int) {
        onUserActive()
        val (px, py) = cursorPixelPosition() ?: return
        val v = ticks.toFloat()
        if (!InputInjector.scroll(displayId, px, py, v, 0f)) {
            ShellInputInjector.scroll(displayId, px, py, v, 0f)
        }
    }

    /** 水平滚动：+ticks = 内容向右翻 */
    fun onHorizontalScroll(ticks: Int) {
        onUserActive()
        val (px, py) = cursorPixelPosition() ?: return
        val h = ticks.toFloat()
        if (!InputInjector.scroll(displayId, px, py, 0f, h)) {
            ShellInputInjector.scroll(displayId, px, py, 0f, h)
        }
    }

    /** 归一化触点坐标 → 副屏像素坐标（副屏真实全尺寸，含刘海/圆角外的区域） */
    private fun cursorPixelPosition(): Pair<Int, Int>? {
        if (!hasSecondaryDisplay()) return null
        val display = displayManager?.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        // 映射数学：像素 = 归一化坐标 × 副屏对应边像素数
        return (nx * metrics.widthPixels).toInt() to (ny * metrics.heightPixels).toInt()
    }

    // ---- 3 秒自动隐藏（防抖计时器） ----

    /**
     * 触点隐藏任务：由 postDelayed 排队。
     * 3 秒内任何新触摸都会先被 removeCallbacks 摘除（见 [onUserActive]），
     * 因此只有「静默满 3 秒」它才会真正执行，把触点渐隐。
     */
    private val hideRunnable = Runnable {
        if (a11yCursor) CursorAccessibilityService.updateCursor(nx, ny, false)
        else cursorView?.hideWithFade()
    }

    /**
     * 【3 秒计时核心】所有触摸活动（按下/移动/滚动/点击）都会调用这里：
     * 1. show：触点若处于隐藏或渐隐中 → 打断渐隐并立即淡入；
     * 2. removeCallbacks + postDelayed：把倒计时「清零重来」（防抖 debounce）。
     *    只要手指还在动，隐藏任务就永远排在 3 秒之后，触点持续可见；
     *    一旦停止操作，最后一次排队的任务在 3 秒后到期，触点渐隐。
     */
    fun onUserActive() {
        if (a11yCursor) CursorAccessibilityService.updateCursor(nx, ny, true)
        else cursorView?.show()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }
}
