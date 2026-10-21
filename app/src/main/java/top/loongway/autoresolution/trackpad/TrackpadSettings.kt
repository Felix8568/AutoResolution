package top.loongway.autoresolution.trackpad

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * 触控板外观/手感设置：SharedPreferences 持久化 + Compose 状态实时生效。
 *
 * 「马上改马上看得见」的实现：面板高度是 Compose 状态，改动即触发主界面重组；
 * 边缘分区比例由 MainActivity 的 AndroidView update 块同步进 [TrackpadView]
 * （setter 内部重画分区提示并更新手势排除区）。
 */
object TrackpadSettings {

    private const val PREFS = "trackpad_settings"
    private const val KEY_PANEL_HEIGHT = "trackpad_panel_height"
    private const val KEY_EDGE_RATIO = "trackpad_edge_ratio"
    private const val KEY_BOTTOM_STRIP_DP = "trackpad_bottom_strip_dp"

    /**
     * 触控板高度比例范围与步进：50%–100%，5% 一档。
     * 比例的基数是「信息条以下的剩余空间」（非整屏），100% = 贴齐信息条，
     * 因此任何档位都不会遮挡顶部内容。
     */
    val panelHeightRange: ClosedFloatingPointRange<Float> = 0.50f..1.00f
    const val PANEL_HEIGHT_STEP = 0.05f

    /** 左右滚动区宽度比例范围与步进：10%–25%，5% 一档（占触控板宽度） */
    val edgeRatioRange: ClosedFloatingPointRange<Float> = 0.10f..0.25f
    const val EDGE_RATIO_STEP = 0.05f

    /** 底部横滚区高度范围与步进：40–120dp，8dp 一档（绝对高度，不随触控板缩放） */
    val bottomStripRange: ClosedFloatingPointRange<Float> = 40f..120f
    const val BOTTOM_STRIP_STEP = 8f

    /** 触控板高度比例（默认 50%；基数为信息条以下剩余空间） */
    var panelHeight by mutableFloatStateOf(0.50f)
        private set

    /** 左右滚动区宽度比例（默认 15%） */
    var edgeRatio by mutableFloatStateOf(0.15f)
        private set

    /** 底部横滚区高度（dp，默认 64） */
    var bottomStripDp by mutableFloatStateOf(64f)
        private set

    private var prefs: SharedPreferences? = null

    /** Application.onCreate 时调用；先于任何界面读取 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            panelHeight = it.getFloat(KEY_PANEL_HEIGHT, 0.50f)
            edgeRatio = it.getFloat(KEY_EDGE_RATIO, 0.15f)
            bottomStripDp = it.getFloat(KEY_BOTTOM_STRIP_DP, 64f)
        }
    }

    fun updatePanelHeight(v: Float) {
        panelHeight = v
        prefs?.edit()?.putFloat(KEY_PANEL_HEIGHT, v)?.apply()
    }

    fun updateEdgeRatio(v: Float) {
        edgeRatio = v
        prefs?.edit()?.putFloat(KEY_EDGE_RATIO, v)?.apply()
    }

    fun updateBottomStripDp(v: Float) {
        bottomStripDp = v
        prefs?.edit()?.putFloat(KEY_BOTTOM_STRIP_DP, v)?.apply()
    }
}
