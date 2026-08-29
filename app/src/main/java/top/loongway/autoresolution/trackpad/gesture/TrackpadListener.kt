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

    /**
     * 滑动手势开始（边缘区按下瞬间）：触点先跳到按压位置对应的百分比坐标
     * （侧边区只重定位纵轴、底部区只重定位横轴），再在该处按下"虚拟手指"，
     * 后续 [onSwipeMove] 移动、[onSwipeEnd] 抬起，等效于在副屏上真实滑动。
     * @param zone 按下时锁定的分区
     * @param nx 按下位置归一化 x（0~1，相对触控板）
     * @param ny 按下位置归一化 y（0~1，相对触控板）
     */
    fun onSwipeBegin(zone: TrackpadZone, nx: Float, ny: Float) {}

    /**
     * 滑动手势移动。
     * @param dx 归一化 x 增量（已含灵敏度换算）
     * @param dy 归一化 y 增量
     */
    fun onSwipeMove(dx: Float, dy: Float) {}

    /** 滑动手势结束（抬起/取消）：注入 ACTION_UP，虚拟手指离开副屏 */
    fun onSwipeEnd() {}

    /** 垂直滚动：+ticks = 内容向下翻（同滚轮下滚），-ticks = 向上翻 */
    fun onVerticalScroll(ticks: Int) {}

    /** 水平滚动：+ticks = 内容向右翻（同滚轮右倾），-ticks = 向左翻 */
    fun onHorizontalScroll(ticks: Int) {}

    /**
     * 轻点（中心区快速点按）。
     * @param x 轻点位置归一化 x（[0,1]）
     * @param y 轻点位置归一化 y（[0,1]）
     */
    fun onTap(x: Float, y: Float) {}

    /** 任何触摸活动（按下/移动/抬起都会回调），用于副屏触点保活与计时重置 */
    fun onTouchActive() {}
}
