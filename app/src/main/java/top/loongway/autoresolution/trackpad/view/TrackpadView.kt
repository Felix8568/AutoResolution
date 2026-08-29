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

        /** 滚动格阈值：边缘区每累计滑动该像素数产生 1 格滚动 */
        const val SCROLL_TICK_PX = 48f

        /** 轻点判定：按下到抬起的最长时长（ms） */
        const val TAP_MAX_DURATION_MS = 220L
    }

    var listener: TrackpadListener? = null

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
    private var scrollAccum = 0f            // 滚动像素累计器（保留不足一格的余量）
    private var multiTouch = false          // 出现多指后本次手势作废，直到全部抬起

    // ---- 分区提示绘制 ----
    private val density = resources.displayMetrics.density
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0DFFFFFF }   // 边缘分区底色（5% 白）
    private val boundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }  // 分区分界线
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF } // 中心准星

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                downTime = event.downTime
                movedBeyondSlop = false
                scrollAccum = 0f
                multiTouch = false
                // 分区锁定：以按下瞬间的位置决定本次手势职责；
                // 手指中途划入其它分区不切换指令类型（避免移动/滚动互相窜扰）
                zone = TrackpadZone.of(event.x, event.y, width, height)
                listener?.onTouchActive()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指/多指不在需求范围：本次手势作废，等全部抬起后重新开始
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

                // 轻点排除：累计位移一旦超出 touchSlop，中心区手势不再可能是“点击”。
                // 边缘滚动区没有点击语义，不设阈值 —— 立即累计，轻扫也能触发滚动
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    movedBeyondSlop = true
                }

                when (zone) {
                    TrackpadZone.CENTER -> {
                        if (!movedBeyondSlop) return true
                        // 中心区：位移增量 → 触点移动
                        dispatchMove(dx, dy)
                    }

                    TrackpadZone.LEFT_EDGE, TrackpadZone.RIGHT_EDGE ->
                        // 左右边缘：竖直位移 → 垂直滚动。
                        // 取负号统一语义：手指上滑(dy<0) → +ticks（内容向下翻，同滚轮下滚）
                        dispatchScroll(-dy, horizontal = false)

                    TrackpadZone.BOTTOM_EDGE ->
                        // 底部边缘：水平位移 → 水平滚动。
                        // 手指左滑(dx<0) → +ticks（内容向右翻，同滚轮右倾）
                        dispatchScroll(-dx, horizontal = true)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (multiTouch) {
                    resetGesture()
                    return true
                }
                listener?.onTouchActive()
                // 轻点判定三条件：中心区 + 位移未超 slop + 时长够短
                val duration = event.eventTime - downTime
                if (zone == TrackpadZone.CENTER && !movedBeyondSlop && duration <= TAP_MAX_DURATION_MS) {
                    // 归一化轻点位置：与分辨率无关，交给接收方自行映射
                    listener?.onTap(event.x / width.coerceAtLeast(1), event.y / height.coerceAtLeast(1))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // 系统回收手势（如来电弹窗抢焦点）：清空状态，不发任何指令
                resetGesture()
            }
        }
        // 消费所有事件，后续 MOVE/UP 才会继续送达本 View
        return true
    }

    private fun resetGesture() {
        movedBeyondSlop = false
        multiTouch = false
        scrollAccum = 0f
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

    /**
     * 边缘区：像素位移 → 滚动格（tick）。累计器模式：
     * delta 先入账，满 [SCROLL_TICK_PX] 发出 1 格并扣除，余量保留到下次，
     * 保证慢速滑动攒够一格后同样能触发。
     */
    private fun dispatchScroll(delta: Float, horizontal: Boolean) {
        scrollAccum += delta
        val ticks = (scrollAccum / SCROLL_TICK_PX).toInt() // 向零取整：只消费整格
        if (ticks == 0) return
        scrollAccum -= ticks * SCROLL_TICK_PX              // 扣除已发出的整格，保留小数余量
        if (horizontal) listener?.onHorizontalScroll(ticks)
        else listener?.onVerticalScroll(ticks)
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
        val stripW = (width * TrackpadZone.EDGE_RATIO_X).toInt()
        val bottomH = (height * TrackpadZone.EDGE_RATIO_Y).toInt()
        systemGestureExclusionRects = listOf(
            Rect(0, 0, stripW, height - bottomH),
            Rect(width - stripW, 0, width, height - bottomH),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 与 TrackpadZone.of 完全同源的边界计算，保证“看到的就是判定的”
        val leftBound = w * TrackpadZone.EDGE_RATIO_X
        val rightBound = w * (1f - TrackpadZone.EDGE_RATIO_X)
        val bottomBound = h * (1f - TrackpadZone.EDGE_RATIO_Y)

        // 三块边缘区域淡淡提亮，提示可滚动范围
        canvas.drawRect(0f, 0f, leftBound, bottomBound, zonePaint)
        canvas.drawRect(rightBound, 0f, w, bottomBound, zonePaint)
        canvas.drawRect(0f, bottomBound, w, h, zonePaint)
        // 分界线
        canvas.drawLine(leftBound, 0f, leftBound, bottomBound, boundPaint)
        canvas.drawLine(rightBound, 0f, rightBound, bottomBound, boundPaint)
        canvas.drawLine(0f, bottomBound, w, bottomBound, boundPaint)
        // 中心准星：提示主操作区
        canvas.drawCircle(w / 2f, h / 2f, 6f * density, centerPaint)
    }
}
