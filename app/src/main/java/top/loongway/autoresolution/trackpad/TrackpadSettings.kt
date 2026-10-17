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

    /** 面板高度占屏高比例范围与步进：50%–80%，5% 一档 */
    val panelHeightRange: ClosedFloatingPointRange<Float> = 0.50f..0.80f
    const val PANEL_HEIGHT_STEP = 0.05f

    /** 边缘滚动区宽度比例范围与步进：10%–25%，5% 一档（左右与底部共用） */
    val edgeRatioRange: ClosedFloatingPointRange<Float> = 0.10f..0.25f
    const val EDGE_RATIO_STEP = 0.05f

    /** 面板高度占屏高比例（默认 50%，与历史硬编码一致） */
    var panelHeight by mutableFloatStateOf(0.50f)
        private set

    /** 边缘滚动区宽度比例（默认 15%，与历史硬编码一致） */
    var edgeRatio by mutableFloatStateOf(0.15f)
        private set

    private var prefs: SharedPreferences? = null

    /** Application.onCreate 时调用；先于任何界面读取 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            panelHeight = it.getFloat(KEY_PANEL_HEIGHT, 0.50f)
            edgeRatio = it.getFloat(KEY_EDGE_RATIO, 0.15f)
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
}
