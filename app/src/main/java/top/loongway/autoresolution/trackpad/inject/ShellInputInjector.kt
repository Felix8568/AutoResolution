package top.loongway.autoresolution.trackpad.inject

import top.loongway.autoresolution.ResolutionManager

/**
 * Shell 命令注入路线：复用 ResolutionManager 的 Shizuku ShellService 执行
 * `input -d <displayId> ...`（-d 参数要求 Android 10+）。
 * 每条命令 fork 一个进程，延迟高（几十毫秒），只适合点击/滚动这类低频离散事件，
 * 因此仅作为 [InputInjector] 反射路线不可用时的降级方案。
 */
object ShellInputInjector {

    /** swipe 模拟滚动：每 tick 对应的滑动像素 */
    private const val SCROLL_STEP_PX = 48

    /** swipe 时长（ms），越短滚动越“脆” */
    private const val SCROLL_DURATION_MS = 120

    /** 点击：input -d <displayId> tap <x> <y> */
    fun tap(displayId: Int, x: Int, y: Int) {
        ResolutionManager.execShellAsync("input -d $displayId tap $x $y")
    }

    /** 返回键：input -d <displayId> keyevent KEYCODE_BACK，控制副屏应用返回 */
    fun back(displayId: Int) {
        ResolutionManager.execShellAsync("input -d $displayId keyevent KEYCODE_BACK")
    }

    /**
     * 用 swipe 模拟拖拽滚动（input 命令没有真滚轮语义），方向换算：
     *   vTicks > 0（内容向下翻）→ 手指向上滑 → 终点 y 减小
     *   hTicks > 0（内容向右翻）→ 手指向左滑 → 终点 x 减小
     */
    fun scroll(displayId: Int, x: Int, y: Int, vTicks: Float, hTicks: Float) {
        val dx = -(hTicks * SCROLL_STEP_PX).toInt()
        val dy = -(vTicks * SCROLL_STEP_PX).toInt()
        if (dx == 0 && dy == 0) return
        ResolutionManager.execShellAsync(
            "input -d $displayId swipe $x $y ${x + dx} ${y + dy} $SCROLL_DURATION_MS"
        )
    }
}
