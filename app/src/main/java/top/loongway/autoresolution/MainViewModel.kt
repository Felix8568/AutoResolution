package top.loongway.autoresolution

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku

/** 主界面 UI 状态（UDF：UI 发事件 → ViewModel → StateFlow → UI 重组） */
data class MainUiState(
    val shizukuGranted: Boolean = false,
    val notification: Boolean = false,
    val batteryIgnored: Boolean = false,
    val autoBlackScreen: Boolean = false,
    val resolutionWidth: Int = 1080,
    val resolutionHeight: Int = 1920,
    val canReset: Boolean = false,
    val serviceRunning: Boolean = false,
) {
    val allGranted: Boolean get() = shizukuGranted && notification && batteryIgnored
    val resolutionText: String get() = "${resolutionWidth}×${resolutionHeight}"
}

/** UI 事件（UDF：事件是 UI → ViewModel 的唯一通道） */
sealed interface MainEvent {
    data object RequestShizuku : MainEvent
    data object ToggleService : MainEvent
    data object ToggleAutoBlackScreen : MainEvent
    data object ResetResolution : MainEvent
    data class SaveResolution(val width: Int, val height: Int) : MainEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val appContext get() = getApplication<Application>()

    init {
        refresh()
    }

    fun onEvent(event: MainEvent) {
        when (event) {
            MainEvent.RequestShizuku -> requestShizukuPermission()
            MainEvent.ToggleService -> toggleService()
            MainEvent.ToggleAutoBlackScreen -> toggleAutoBlackScreen()
            MainEvent.ResetResolution -> resetResolution()
            is MainEvent.SaveResolution -> saveResolution(event.width, event.height)
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

    private fun refresh() {
        val status = PermissionChecker.check(appContext)
        val (sysW, sysH) = ResolutionManager.systemScreenResolution()
        _uiState.update {
            it.copy(
                shizukuGranted = status.shizuku,
                notification = status.notification,
                batteryIgnored = status.batteryIgnored,
                autoBlackScreen = ResolutionManager.autoBlackScreen,
                resolutionWidth = ResolutionManager.targetWidth,
                resolutionHeight = ResolutionManager.targetHeight,
                // 与系统屏幕分辨率不同才允许「还原」
                canReset = ResolutionManager.targetWidth != sysW ||
                    ResolutionManager.targetHeight != sysH,
                serviceRunning = DisplayMonitorService.isRunning(appContext),
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

    private fun saveResolution(width: Int, height: Int) {
        ResolutionManager.setTargetResolution(width, height)
        refresh()
    }

    private fun toggleAutoBlackScreen() {
        ResolutionManager.setAutoBlackScreen(!_uiState.value.autoBlackScreen)
        refresh()
    }

    private fun resetResolution() {
        ResolutionManager.resetTargetResolution()
        refresh()
    }

    private fun toggleService() {
        val context = appContext
        if (_uiState.value.serviceRunning) {
            DisplayMonitorService.stop(context)
        } else {
            // 权限齐全才能启动；缺失时界面已有提示
            if (PermissionChecker.check(context).allGranted) {
                DisplayMonitorService.start(context)
                ResolutionManager.rebind()
            } else {
                Toast.makeText(context, "权限未齐全，无法启动后台监听", Toast.LENGTH_SHORT).show()
            }
        }
        refresh()
    }

    companion object {
        const val SHIZUKU_REQUEST_CODE = 1
    }
}
