package top.loongway.autoresolution.trackpad.gesture

/**
 * 触控板分区定义与判定（纯数学，无 UI 依赖，便于单独测试）。
 *
 * 布局示意（宽 w × 高 h）：
 * ```
 *  ┌──────┬──────────────────────┬──────┐
 *  │      │                      │      │
 *  │ LEFT │        CENTER        │RIGHT │ ← 上下滑 = 垂直滚动
 *  │      │   滑动=移动触点       │      │
 *  │      │   轻点=点击          │      │
 *  ├──────┴──────────────────────┴──────┤
 *  │           BOTTOM EDGE              │ ← 左右滑 = 水平滚动
 *  └────────────────────────────────────┘
 * ```
 */
enum class TrackpadZone {
    /** 中心主区域：滑动 = 移动触点，轻点 = 点击 */
    CENTER,
    /** 左边缘：上下滑 = 垂直滚动 */
    LEFT_EDGE,
    /** 右边缘：上下滑 = 垂直滚动 */
    RIGHT_EDGE,
    /** 底部边缘：左右滑 = 水平滚动 */
    BOTTOM_EDGE;

    companion object {
        /** 左右边缘各占触控板总宽的比例（两侧各 15%） */
        const val EDGE_RATIO_X = 0.15f

        /** 底部边缘占触控板总高的比例（15%） */
        const val EDGE_RATIO_Y = 0.15f

        /**
         * 判定触点所在分区。
         *
         * 边缘检测数学：把触控板宽 w 按比例切三段——
         *   左边界 leftBound = w × 15%，右边界 rightBound = w × 85%；
         *   底边界 bottomBound = h × 85%。
         * 落点 x < leftBound → 左边缘，x > rightBound → 右边缘，
         * y ≥ bottomBound → 底部边缘，其余为中心区。
         *
         * 重叠处理：左/右边缘与底部边缘交叠的“角部”优先判为底部
         * （水平滚动优先），保证分区互斥、判定顺序即优先级。
         */
        fun of(x: Float, y: Float, width: Int, height: Int): TrackpadZone {
            if (width <= 0 || height <= 0) return CENTER
            val leftBound = width * EDGE_RATIO_X
            val rightBound = width * (1f - EDGE_RATIO_X)
            val bottomBound = height * (1f - EDGE_RATIO_Y)
            return when {
                y >= bottomBound -> BOTTOM_EDGE
                x < leftBound -> LEFT_EDGE
                x > rightBound -> RIGHT_EDGE
                else -> CENTER
            }
        }
    }
}
