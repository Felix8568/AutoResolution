package top.loongway.autoresolution.trackpad.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import android.view.ViewPropertyAnimator

/**
 * 副屏“控制触点”：四色渐变环 + 白色核心 + 全屏十字校准线。
 *
 * 位置体系：与注入侧共用同一像素坐标（副屏 getRealMetrics 空间）。
 *
 * 【生死攸关】根视图绝对不能调用 translationX/Y（或任何视图位移属性）：
 * 现代 Android 的 ViewRootImpl 会把根视图的位移「提升为窗口位移」——
 * 窗口整体跟着坐标跑，触点视觉位置 = 期望位置 × 2（窗口位移 + 窗口内绘制），
 * 表现为触点“飞出屏幕右/下边缘很远”且“点击总落在目标左上方的一半处”。
 * 触点位置只在 onDraw 里按状态值直接绘制，窗口本身纹丝不动。
 *
 * 显隐：show/hideWithFade 由 SecondaryDisplayController 的 3 秒计时器驱动；
 * 根视图的 alpha/visibility 会被映射为窗口透明度/可见性，正是显隐需要的语义。
 */
class CursorView(context: Context) : View(context) {

    companion object {
        /** 触点外半径（dp） */
        private const val CURSOR_RADIUS_DP = 14f

        /** 渐变环宽度占外半径的比例 */
        private const val RING_WIDTH_RATIO = 0.55f

        /** 淡入/渐隐时长 */
        private const val FADE_DURATION_MS = 250L

        /** 校准辅助（全屏十字线 + 左上角坐标读数）：排障时打开，日常保持关闭 */
        const val DEBUG_HELPERS = false

        /**
         * 四色渐变（首尾相接闭合）：蓝 → 绿 → 琥珀 → 红。
         * 中等明度四色在纯白/纯黑界面上都保持高辨识度。
         */
        private val SWEEP_COLORS = intArrayOf(
            0xFF3B82F6.toInt(), // 蓝
            0xFF22C55E.toInt(), // 绿
            0xFFF59E0B.toInt(), // 琥珀
            0xFFEF4444.toInt(), // 红
            0xFF3B82F6.toInt(), // 闭环：重复首色收口
        )
    }

    private val density = resources.displayMetrics.density
    private val radius = CURSOR_RADIUS_DP * density

    /** 渐变环：以圆心为轴的四色锥形（Sweep）渐变 */
    private val ringShader = SweepGradient(
        0f, 0f, SWEEP_COLORS, floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    )
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = radius * RING_WIDTH_RATIO
        shader = ringShader
    }

    /** 白色核心：填满环的内孔，提供视觉锚点 */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }

    /** 十字线画笔：贯穿全屏，交点即触点真实位置（肉眼可校准） */
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        color = 0x88FFFFFF.toInt()
    }

    /** 左上角坐标读数（校准用）：纯蓝底（可被像素扫描精确识别）+ 白字 */
    private val debugBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0000FF.toInt() }
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f * density
        typeface = Typeface.MONOSPACE
    }

    /** 环路径半径：使环的外缘恰好等于外半径 [radius] */
    private val ringRadius = radius * (1f - RING_WIDTH_RATIO / 2f)

    /** 核心半径：恰好填满环的内孔 */
    private val coreRadius = radius * (1f - RING_WIDTH_RATIO)

    /** 渐变随圆心平移用的矩阵（复用实例，避免每帧重建 shader） */
    private val gradientMatrix = Matrix()

    /**
     * 单独持有 alpha 动画引用：只有显隐动画可被 show 打断，
     * 避免误杀其它属性动画（ViewPropertyAnimator 是 View 级共享实例）。
     */
    private var fadeAnim: ViewPropertyAnimator? = null

    /** 是否正在渐隐（只有这种情况才需要被 [show] 打断） */
    private var fadingOut = false

    /** 触点像素位置（与注入同一坐标空间的唯一真值） */
    private var px = 960f
    private var py = 540f

    /** 尚未布局时的待用归一化坐标（本地兜底路径） */
    private var pendingNx = 0.5f
    private var pendingNy = 0.5f

    init {
        // 透明背景：每帧以透明清空画布区域（硬件加速安全，避免拖影）
        setBackgroundColor(Color.TRANSPARENT)
        // 初始隐藏：首次触摸主屏时由 show() 唤醒
        alpha = 0f
        visibility = INVISIBLE
    }

    /**
     * 像素坐标直填（无障碍托管路径）：px/py 与注入侧完全同源，
     * 画的位置即点击的位置。只触发重绘，绝不移动窗口。
     */
    fun setPixelPosition(px: Float, py: Float) {
        this.px = px
        this.py = py
        invalidate()
    }

    /**
     * 归一化坐标更新（本地兜底窗口路径）。
     * 布局完成后按当前视图尺寸换算为像素（视图与屏幕一致时两者等价）。
     */
    fun setNormalizedPosition(nx: Float, ny: Float) {
        pendingNx = nx.coerceIn(0f, 1f)
        pendingNy = ny.coerceIn(0f, 1f)
        if (!isLaidOut) return
        px = pendingNx * width
        py = pendingNy * height
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        px = pendingNx * w
        py = pendingNy * h
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 绘制位置钳制在视图内（px/py 本身保持全域，供注入命中屏幕边缘）
        val cx = px.coerceIn(radius, (width - radius).coerceAtLeast(radius))
        val cy = py.coerceIn(radius, (height - radius).coerceAtLeast(radius))
        // shader 定义在原点，每帧把矩阵平移到圆心即可，无需重建
        gradientMatrix.setTranslate(cx, cy)
        ringShader.setLocalMatrix(gradientMatrix)
        if (DEBUG_HELPERS) {
            // 十字线贯穿全屏：交点 = 触点真实位置，肉眼可校准
            canvas.drawLine(0f, cy, width.toFloat(), cy, crossPaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), crossPaint)
            canvas.drawRect(0f, 0f, 620f * density, 150f * density, debugBgPaint)
            canvas.drawText(
                "x=${cx.toInt()} y=${cy.toInt()}",
                24f * density, 60f * density, debugTextPaint
            )
            canvas.drawText(
                "w=$width h=$height",
                24f * density, 120f * density, debugTextPaint
            )
        }
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
    }

    /**
     * 显示：淡入到不透明稳态。
     * 只有“渐隐中”才取消旧动画（withEndAction 在 cancel 时不会执行）；
     * 进行中的淡入不重启，否则每个触摸事件都会把淡入进度打回。
     */
    fun show() {
        if (fadingOut) {
            fadeAnim?.cancel()
            fadeAnim = null
            fadingOut = false
        }
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
        }
        // fadeAnim == null 才启动淡入：引用在动画自然结束时被清空，
        // 若残留陈旧引用会永远挡住淡入，触点隐身后再也回不来
        if (alpha < 1f && fadeAnim == null) {
            fadeAnim = animate().alpha(1f).setDuration(FADE_DURATION_MS)
                .withEndAction { fadeAnim = null }
        }
    }

    /** 渐隐后置为 INVISIBLE（不用 GONE：避免触发重布局） */
    fun hideWithFade() {
        if (visibility != VISIBLE || fadingOut) return
        fadingOut = true
        fadeAnim = animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction {
                fadingOut = false
                fadeAnim = null
                visibility = INVISIBLE
            }
    }
}
