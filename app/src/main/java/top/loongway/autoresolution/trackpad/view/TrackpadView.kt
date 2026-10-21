package top.loongway.autoresolution.trackpad.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import top.loongway.autoresolution.trackpad.gesture.TrackpadListener
import top.loongway.autoresolution.trackpad.gesture.TrackpadZone
import kotlin.math.abs

/**
 * 主屏触控板自定义 View。
 *
 * 分区手势（见 [TrackpadZone]）：
 *  - 左右边缘 15%：上下滑 → 垂直滚动指令
 *  - 底部边缘 15%：左右滑 → 水平滚动指令
 *  - 中心区域：滑动 → 触点移动指令；轻点 → 点击指令
 *
 * 解耦：本 View 只通过 [listener] 发出抽象指令，
 * 不持有副屏、注入器与定时器的任何引用。
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        /**
         * 灵敏度：触点增量 = 手指位移（占触控板比例）× 灵敏度。
         * 1.0 = 手指划满触控板宽度，触点划满副屏宽度；>1 放大、<1 缩小。
         */
        const val SENSITIVITY = 1.6f

        /** 轻点判定：按下到抬起的最长时长（ms） */
        const val TAP_MAX_DURATION_MS = 220L

        /** 原地长按判定：按住不动超过该时长进入拖动模式（ms） */
        const val LONG_PRESS_MS = 500L

        /** 底部横滚条固定高度（dp）：不随触控板高度缩放，拖高触控板只改变中心区 */
        const val BOTTOM_STRIP_DP = 64f
    }

    var listener: TrackpadListener? = null

    /**
     * 边缘滚动区宽度比例（仅左右两侧，设置实时可调，默认 15%）。
     * setter 内同步重画分区提示并更新系统手势排除区，保证“看到的就是判定的”。
     */
    var edgeRatio: Float = TrackpadZone.EDGE_RATIO_X
        set(value) {
            if (field == value) return
            field = value
            updateGestureExclusionRects()
            invalidate()
        }

    /** 底部横滚区像素高度（设置可调，dp→px 转换，与触控板高度解耦） */
    var bottomStripPx: Float = BOTTOM_STRIP_DP * resources.displayMetrics.density
        set(value) {
            if (field == value) return
            field = value
            updateGestureExclusionRects()
            invalidate()
        }

    /**
     * 轻点位移阈值：系统标准 touchSlop（随屏幕密度缩放，约 8dp）。
     * 此前固定 24 物理像素，在 1.5K 高分屏上不足 9dp，手指自然抖动即超限，
     * 导致绝大多数“单击”被误判为滑动而不触发点击。
     */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---- 手势状态（每次 DOWN 重置） ----
    private var zone = TrackpadZone.CENTER  // 分区锁定：按下瞬间决定，本次手势内不变
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var movedBeyondSlop = false     // 位移是否已超出轻点判定阈值
    private var multiTouch = false          // 出现多指后本次手势作废，直到全部抬起

    // ---- 原地长按拖动（按住不动 → 在副屏按下"虚拟手指"，随后移动即拖拽） ----
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dragging = false            // 拖动进行中（虚拟手指已按下）
    private val longPressRunnable = Runnable {
        // 按住未移动也未抬手：进入拖动模式（触点当前位置按下虚拟手指）
        if (!multiTouch && !movedBeyondSlop && zone == TrackpadZone.CENTER) {
            dragging = true
            listener?.onSwipeBegin(TrackpadZone.CENTER, 0.5f, 0.5f)
        }
    }

    // ---- 分区提示绘制（HUD 霓虹红风格） ----
    private val density = resources.displayMetrics.density
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FF2A3C }   // 边缘分区底色（低透明度红）
    private val boundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FF2A3C }  // 分区分界线

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                downTime = event.downTime
                movedBeyondSlop = false
                multiTouch = false
                // 分区锁定：以按下瞬间的位置决定本次手势职责；
                // 手指中途划入其它分区不切换指令类型（避免移动/滚动互相窜扰）
                zone = TrackpadZone.of(event.x, event.y, width, height, edgeRatio, bottomStripPx)
                dragging = false
                // 中心区原地长按 → 拖动模式；边缘区 = 真实滑动手势：按下瞬间按按压位置
                // 重定位触点（对应轴），再按下"虚拟手指"，滑动跟手、抬起结束
                if (zone == TrackpadZone.CENTER) {
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                } else {
                    listener?.onSwipeBegin(
                        zone,
                        event.x / width.coerceAtLeast(1),
                        event.y / height.coerceAtLeast(1)
                    )
                }
                listener?.onTouchActive()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指/多指不在需求范围：本次手势作废（先抬走已按下的虚拟手指），等全部抬起后重新开始
                if (!multiTouch && zone != TrackpadZone.CENTER) listener?.onSwipeEnd()
                multiTouch = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) return true
                listener?.onTouchActive()

                // 本次采样间隔内的位移增量（Delta X / Delta Y）
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                // 轻点排除：累计位移一旦超出 touchSlop，中心区手势不再可能是“点击”，
                // 同时取消长按拖动计时（非原地长按 = 正常滑动）
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    if (!movedBeyondSlop) handler.removeCallbacks(longPressRunnable)
                    movedBeyondSlop = true
                }

                when (zone) {
                    TrackpadZone.CENTER -> {
                        // 长按拖动中：位移增量直接驱动副屏"虚拟手指"
                        if (dragging) {
                            dispatchSwipe(dx, dy)
                            return true
                        }
                        if (!movedBeyondSlop) return true
                        // 中心区：位移增量 → 触点移动
                        dispatchMove(dx, dy)
                    }

                    // 边缘区：真实滑动手势 —— 位移增量直接转为副屏"虚拟手指"移动，
                    // 与中心区相同的换算（占触控板比例 × 灵敏度），跟手感一致。
                    // 同样过 slop 门槛：按下不动直接抬起 = 不注入任何事件（侧边不响应点击）
                    TrackpadZone.LEFT_EDGE, TrackpadZone.RIGHT_EDGE,
                    TrackpadZone.BOTTOM_EDGE -> {
                        if (!movedBeyondSlop) return true
                        dispatchSwipe(dx, dy)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (multiTouch) {
                    resetGesture()
                    return true
                }
                listener?.onTouchActive()
                if (dragging) {
                    // 长按拖动结束：虚拟手指抬起
                    dragging = false
                    listener?.onSwipeEnd()
                    return true
                }
                if (zone != TrackpadZone.CENTER) {
                    // 边缘滑动结束：虚拟手指抬起
                    listener?.onSwipeEnd()
                    return true
                }
                // 轻点判定三条件：中心区 + 位移未超 slop + 时长够短
                val duration = event.eventTime - downTime
                if (!movedBeyondSlop && duration <= TAP_MAX_DURATION_MS) {
                    // 归一化轻点位置：与分辨率无关，交给接收方自行映射
                    listener?.onTap(event.x / width.coerceAtLeast(1), event.y / height.coerceAtLeast(1))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // 系统回收手势（如来电弹窗抢焦点）：抬走虚拟手指并清空状态，不发任何指令
                handler.removeCallbacks(longPressRunnable)
                if (dragging || (!multiTouch && zone != TrackpadZone.CENTER)) listener?.onSwipeEnd()
                dragging = false
                resetGesture()
            }
        }
        // 消费所有事件，后续 MOVE/UP 才会继续送达本 View
        return true
    }

    private fun resetGesture() {
        movedBeyondSlop = false
        multiTouch = false
    }

    /**
     * 中心区：主屏位移 → 副屏触点归一化增量，两步换算（跨分辨率解耦的关键）：
     *   ① dx / width          → 手指位移占触控板宽度的比例（0~1）
     *   ② 比例 × SENSITIVITY  → 灵敏度放大后的触点归一化增量
     * 接收方（副屏控制器）拿到的量与主/副屏具体分辨率均无关。
     */
    private fun dispatchMove(dx: Float, dy: Float) {
        if (width <= 0 || height <= 0) return
        listener?.onCursorMove(dx / width * SENSITIVITY, dy / height * SENSITIVITY)
    }

    /** 边缘区滑动：归一化增量（同 dispatchMove 的两步换算）交给接收方驱动"虚拟手指" */
    private fun dispatchSwipe(dx: Float, dy: Float) {
        if (width <= 0 || height <= 0) return
        listener?.onSwipeMove(dx / width * SENSITIVITY, dy / height * SENSITIVITY)
    }

    // ---- 绘制：分区提示 ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusionRects()
    }

    /**
     * 申请把左右边缘滚动区排除出系统「返回」手势热区。
     * 手势导航的返回热区就在屏幕左右边缘：在边缘区滚动时手指贴边，
     * 会被系统当成返回手势直接关掉触控板页 —— 用户看到的就是“闪退”。
     * 系统每边最多接受 200dp 的排除区（超出部分仍可能触发返回），
     * 配合 Activity 吞掉返回事件兜底。
     */
    private fun updateGestureExclusionRects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (width <= 0 || height <= 0) return
        val stripW = (width * edgeRatio).toInt()
        val stripBottom = height - bottomStripPx.toInt()
        systemGestureExclusionRects = listOf(
            Rect(0, 0, stripW, stripBottom),
            Rect(width - stripW, 0, width, stripBottom),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 与 TrackpadZone.of 完全同源的边界计算，保证“看到的就是判定的”
        val leftBound = w * edgeRatio
        val rightBound = w * (1f - edgeRatio)
        val bottomBound = h - bottomStripPx

        // 三块边缘区域淡淡提亮，提示可滚动范围
        canvas.drawRect(0f, 0f, leftBound, bottomBound, zonePaint)
        canvas.drawRect(rightBound, 0f, w, bottomBound, zonePaint)
        canvas.drawRect(0f, bottomBound, w, h, zonePaint)
        // 分界线
        canvas.drawLine(leftBound, 0f, leftBound, bottomBound, boundPaint)
        canvas.drawLine(rightBound, 0f, rightBound, bottomBound, boundPaint)
        canvas.drawLine(0f, bottomBound, w, bottomBound, boundPaint)
    }
}
