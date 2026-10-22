package top.loongway.autoresolution.trackpad.view

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 触摸桥接窗口：盖在触控板可视区域正上方的一层透明子窗口，声明为不可聚焦。
 *
 * 为什么要这一层：触控板手势如果直接由主窗口接收，触摸会把全局焦点从眼镜侧
 * 输入框抢回手机，键盘立即消失；而主窗口如果声明为不可聚焦，手机上又没有别的
 * 窗口持有焦点，按返回键时系统找不到派发目标，超时后直接杀掉应用（ANR）。
 * 所以分工是：主窗口保持可聚焦（负责按键），这一层负责触摸（不改变焦点），
 * 两边都正常。
 *
 * 收到的触摸事件原样转发给主窗口的 decorView，由它按坐标分发给触控板和角落
 * 按钮，手势与点击的处理逻辑不用做任何修改。
 *
 * 坐标校准：不同 ROM 对子窗口 x/y 的定位基准不一致（有的按窗口顶边、有的按
 * 状态栏以下算），无法写死。挂载后实测本窗口和 decorView 在屏幕上的真实原点，
 * 据此修正窗口位置、换算转发坐标，任何 ROM 上都能对齐。
 */
class TouchBridgeWindow(activity: Activity, private val forwardTarget: View) {

    private companion object {
        /** 位置修正的重试上限，防止 ROM 不响应位置调整时无限循环 */
        const val MAX_REALIGN_ATTEMPTS = 3
    }

    private var realignAttempts = 0

    private val windowManager = activity.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val bridge = View(activity)
    private var attached = false

    /** 期望的面板位置与大小（主窗口坐标，由 TrackpadPanel 每次布局时上报） */
    private var intendedLeft = 0
    private var intendedTop = 0

    /**
     * 转发触摸时的坐标偏移：decorView 原点减去桥接窗口原点（屏幕坐标）。
     * 触点在桥接窗口里的坐标加上这个值，才是 decorView 里对应的坐标。
     */
    private var offsetX = 0f
    private var offsetY = 0f

    private val layoutParams = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Activity context 的 WindowManager 会自动附带主窗口的 token（子窗口必需）
    }

    init {
        bridge.setOnTouchListener { _, event -> forward(event); true }
    }

    /** 把桥接窗口对齐到面板的可视区域（主窗口坐标），首次调用时挂载 */
    fun setFrame(left: Int, top: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        intendedLeft = left
        intendedTop = top
        layoutParams.x = left
        layoutParams.y = top
        layoutParams.width = width
        layoutParams.height = height
        if (attached) {
            windowManager.updateViewLayout(bridge, layoutParams)
        } else {
            windowManager.addView(bridge, layoutParams)
            attached = true
        }
        // 窗口完成布局后才能读到真实位置，所以校准放在稍后执行
        realignAttempts = 0
        handler.removeCallbacks(::realign)
        handler.post(::realign)
    }

    fun detach() {
        handler.removeCallbacks(::realign)
        if (!attached) return
        windowManager.removeView(bridge)
        attached = false
    }

    /**
     * 位置校准：对比桥接窗口的实际位置与期望位置，不一致就修正窗口位置。
     * 每一轮都会同时刷新转发坐标的偏移量，所以即使重试用尽、位置仍有个固定
     * 偏差，转发的坐标也是对的（触控板手势只取相对位移，固定偏差不影响使用）。
     */
    private fun realign() {
        if (!attached) return
        val bridgeLoc = IntArray(2)
        bridge.getLocationOnScreen(bridgeLoc)
        val decorLoc = IntArray(2)
        forwardTarget.getLocationOnScreen(decorLoc)
        offsetX = (bridgeLoc[0] - decorLoc[0]).toFloat()
        offsetY = (bridgeLoc[1] - decorLoc[1]).toFloat()
        val deltaX = (decorLoc[0] + intendedLeft) - bridgeLoc[0]
        val deltaY = (decorLoc[1] + intendedTop) - bridgeLoc[1]
        if (deltaX != 0 || deltaY != 0) {
            val attempt = ++realignAttempts
            if (attempt <= MAX_REALIGN_ATTEMPTS) {
                Log.i("TouchBridge", "realign #$attempt by ($deltaX,$deltaY)")
                layoutParams.x += deltaX
                layoutParams.y += deltaY
                windowManager.updateViewLayout(bridge, layoutParams)
            }
            // 最后一次修正之后再测一轮，让偏移量跟上窗口的最终位置
            handler.postDelayed(::realign, if (attempt <= MAX_REALIGN_ATTEMPTS) 100 else 300)
        }
    }

    /** 触摸坐标从桥接窗口的空间换算到 decorView 的空间，然后原样转发 */
    private fun forward(event: MotionEvent) {
        event.offsetLocation(offsetX, offsetY)
        runCatching { forwardTarget.dispatchTouchEvent(event) }
            .onFailure { Log.e("TouchBridge", "forward failed", it) }
    }
}
