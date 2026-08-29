package top.loongway.autoresolution.trackpad.gesture

/**
 * 触控板手势指令回调：View 只负责把触摸翻译成抽象指令，
 * 通过本接口发出，不关心副屏、注入与计时细节（解耦的关键）。
 */
interface TrackpadListener {

    /**
     * 触点相对移动。
     * @param dx 触点归一化 x 增量（已含灵敏度换算），叠加到当前位置
     * @param dy 触点归一化 y 增量
     */
    fun onCursorMove(dx: Float, dy: Float) {}

    /** 垂直滚动：+ticks = 内容向下翻（同滚轮下滚），-ticks = 向上翻 */
    fun onVerticalScroll(ticks: Int) {}

    /** 水平滚动：+ticks = 内容向右翻（同滚轮右倾），-ticks = 向左翻 */
    fun onHorizontalScroll(ticks: Int) {}

    /**
     * 轻点（中心区快速点按）。
     * @param x 轻点位置归一化 x（[0,1]）
     * @param y 轻点位置归一化 y
     */
    fun onTap(x: Float, y: Float) {}

    /** 任何触摸活动（按下/移动/抬起都会回调），用于副屏触点保活与计时重置 */
    fun onTouchActive() {}
}
