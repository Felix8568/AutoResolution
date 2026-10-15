package top.loongway.autoresolution

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku
import top.loongway.autoresolution.trackpad.a11y.AccessibilityRepair
import top.loongway.autoresolution.trackpad.a11y.CursorAccessibilityService

/** 主界面 UI 状态（UDF：UI 发事件 → ViewModel → StateFlow → UI 重组） */
data class MainUiState(
    val shizukuGranted: Boolean = false,
    val notification: Boolean = false,
    val batteryIgnored: Boolean = false,
    val a11yCursorService: Boolean = false,
    val autoBlackScreen: Boolean = false,
) {
    val allGranted: Boolean get() = shizukuGranted && notification && batteryIgnored
}

/** UI 事件（UDF：事件是 UI → ViewModel 的唯一通道） */
sealed interface MainEvent {
    data object RequestShizuku : MainEvent
    data object ToggleAutoBlackScreen : MainEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val appContext get() = getApplication<Application>()

    init {
        // 无障碍服务连上/断开 → 实时刷新状态行
        CursorAccessibilityService.onStateChanged = { refresh() }
        refresh()
    }

    fun onEvent(event: MainEvent) {
        when (event) {
            MainEvent.RequestShizuku -> requestShizukuPermission()
            MainEvent.ToggleAutoBlackScreen -> toggleAutoBlackScreen()
        }
    }

    /** 回到前台：自动修复缺失权限（异步完成后再检查一次），权限齐全则启动服务 */
    fun onResume() {
        ResolutionManager.ensureShizukuPermissions {
            refresh()
            startServiceIfReady()
        }
        refresh()
        startServiceIfReady()
    }

    /** 权限齐全后自动创建服务并启动监听（幂等，可重复调用） */
    private fun startServiceIfReady() {
        if (_uiState.value.allGranted) {
            DisplayMonitorService.start(appContext)
            ResolutionManager.rebind()
        }
    }

    fun onShizukuResult(granted: Boolean) {
        if (!granted) {
            Toast.makeText(appContext, "请在 Shizuku 应用中允许本应用", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    fun refresh() {
        val status = PermissionChecker.check(appContext)
        // 确保无障碍触点服务已启用且已绑定（未启用时 Shizuku 自动授权）
        AccessibilityRepair.ensure()
        _uiState.update {
            it.copy(
                shizukuGranted = status.shizuku,
                notification = status.notification,
                batteryIgnored = status.batteryIgnored,
                a11yCursorService = CursorAccessibilityService.isAvailable(),
                autoBlackScreen = ResolutionManager.autoBlackScreen,
            )
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            // 已授权会直接回调 GRANTED；未授权则弹出 Shizuku 授权界面，
            // 结果统一走 onRequestPermissionsResult
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } else {
            Toast.makeText(appContext, "请先启动 Shizuku 服务", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleAutoBlackScreen() {
        ResolutionManager.setAutoBlackScreen(!_uiState.value.autoBlackScreen)
        refresh()
    }

    companion object {
        const val SHIZUKU_REQUEST_CODE = 1
    }
}
