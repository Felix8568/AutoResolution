package top.loongway.autoresolution

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.loongway.autoresolution.R
import top.loongway.autoresolution.trackpad.TrackpadActivity
import top.loongway.autoresolution.trackpad.a11y.AccessibilityRepair
import top.loongway.autoresolution.trackpad.a11y.CursorAccessibilityService
import top.loongway.autoresolution.ui.theme.AutoResolutionTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import rikka.shizuku.Shizuku

/**
 * 主界面（MVVM + UDF）：UI 只负责展示 [MainUiState] 并发送 [MainEvent]。
 * 仿 MIUI 设置页风格：浅灰背景 + 白色圆角卡片列表 + 状态圆点。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // edge-to-edge（targetSdk 36 强制）：浅色背景 → 状态栏图标用深色
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    WindowCompat.getInsetsController(window, view)
                        .isAppearanceLightStatusBars = true
                }
            }
            AutoResolutionTheme {
                val state by viewModel.uiState.collectAsState()
                MainScreen(state = state, onEvent = viewModel::onEvent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MainViewModel.SHIZUKU_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            viewModel.onShizukuResult(granted)
        }
    }

    // ---- MIUI 风格配色 ----

    private val PageBg = Color(0xFFF5F5F7)
    private val CardBg = Color(0xFFFFFFFF)
    private val DividerColor = Color(0xFFF0F0F0)
    private val TextPrimary = Color(0xFF1A1A1A)
    private val TextSecondary = Color(0xFF8A8A8A)
    private val AccentBlue = Color(0xFF0A84FF)
    private val GrantedGreen = Color(0xFF34C759)
    private val MissingRed = Color(0xFFFF3B30)

    // 行内文字收紧行高（默认 1.2 倍行高使汉字视觉中心偏移），保证圆点/图标与文字垂直居中对齐
    private val RowTitleStyle = TextStyle(fontSize = 15.sp, lineHeight = 15.sp)
    private val RowValueStyle = TextStyle(fontSize = 14.sp, lineHeight = 14.sp)
    private val RowHintStyle = TextStyle(fontSize = 12.sp, lineHeight = 12.sp)

    @Composable
    private fun MainScreen(state: MainUiState, onEvent: (MainEvent) -> Unit) {
        val context = LocalContext.current

        Box(Modifier.fillMaxSize().background(PageBg)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "自动分辨率",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text("外接显示器自动切换分辨率", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(20.dp))

                if (!state.shizukuGranted) {
                    // Shizuku 未授权
                    MiuiGroup {
                        MiuiRow {
                            Text("需要 Shizuku 授权才能控制屏幕", color = TextPrimary, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    MiuiPrimaryButton(text = "授予 Shizuku 权限") { onEvent(MainEvent.RequestShizuku) }
                } else {
                    // 自动黑屏开关
                    MiuiGroup {
                        MiuiSwitchRow(
                            title = "检测到显示器自动黑屏",
                            subtitle = "推荐在使用触控板、飞鼠的情况下开启。",
                            checked = state.autoBlackScreen,
                            onCheckedChange = { onEvent(MainEvent.ToggleAutoBlackScreen) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 触控板模式入口：主屏当触控板，副屏显示触点并接收注入事件
                    MiuiGroup {
                        MiuiClickRow(
                            title = "触控板模式",
                            rightContent = {
                                Text("打开", color = AccentBlue, style = RowValueStyle)
                            },
                            onClick = {
                                context.startActivity(
                                    Intent(context, TrackpadActivity::class.java)
                                )
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 开机自启引导
                    MiuiGroup {
                        MiuiClickRow(
                            title = "开机自启",
                            rightContent = {
                                Text("去开启", color = AccentBlue, style = RowValueStyle)
                            },
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 权限状态卡片组
                    MiuiGroup {
                        PermissionRow("通知权限", state.notification, "前台服务通知")
                        HorizontalDivider(color = DividerColor, thickness = 1.dp)
                        PermissionRow("省电策略权限", state.batteryIgnored, "后台保活")
                        HorizontalDivider(color = DividerColor, thickness = 1.dp)
                        // 副屏触点服务：状态样式 + 点击触发 Shizuku 自动授权
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!state.a11yCursorService) {
                                        // Shizuku 自动授权；服务连接后由 onStateChanged 实时刷新状态行
                                        AccessibilityRepair.enableNow()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(state.a11yCursorService)
                            Spacer(Modifier.size(10.dp))
                            Text("副屏触点服务", color = TextPrimary, style = RowTitleStyle)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (state.a11yCursorService) "已开启" else "未开启",
                                style = RowValueStyle,
                                color = if (state.a11yCursorService) TextSecondary else MissingRed
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 后台服务状态
                    MiuiGroup {
                        MiuiRow {
                            StatusDot(state.serviceRunning)
                            Spacer(Modifier.size(10.dp))
                            Text("后台服务", color = TextPrimary, style = RowTitleStyle)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (state.serviceRunning) "正在运行" else "未运行",
                                style = RowValueStyle,
                                color = if (state.serviceRunning) TextSecondary else MissingRed
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    MiuiPrimaryButton(
                        text = if (state.serviceRunning) "停止后台监听" else "启动后台监听"
                    ) { onEvent(MainEvent.ToggleService) }

                    // 权限缺失提示
                    if (!state.allGranted) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "权限未齐全，后台监听暂不可用",
                            fontSize = 13.sp,
                            color = MissingRed,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }

    // ---- MIUI 风格组件 ----

    /** 白色圆角卡片组（仿 MIUI 设置项分组） */
    @Composable
    private fun MiuiGroup(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBg),
            content = content
        )
    }

    /** 权限状态行：状态圆点 + 名称 + 右侧说明 */
    @Composable
    private fun PermissionRow(name: String, granted: Boolean, hint: String) {
        MiuiRow {
            StatusDot(granted)
            Spacer(Modifier.size(10.dp))
            Text(name, color = TextPrimary, style = RowTitleStyle)
            Spacer(Modifier.weight(1f))
            Text(
                if (granted) "已开启" else "未开启",
                style = RowValueStyle,
                color = if (granted) TextSecondary else MissingRed
            )
            if (!granted) {
                Spacer(Modifier.size(6.dp))
                Text(hint, style = RowHintStyle, color = TextSecondary)
            }
        }
    }

    /** 通用行：左侧内容 + 右侧内容 */
    @Composable
    private fun MiuiRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }

    /** 开关行（MIUI 风格）：标题+说明两行文字为一组，Switch 单独一组，垂直居中 */
    @Composable
    private fun MiuiSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, color = TextPrimary, style = RowTitleStyle)
                Text(subtitle, color = TextSecondary, style = RowHintStyle)
            }
            Spacer(Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentBlue,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFE0E0E0),
                    uncheckedBorderColor = Color.Transparent,
                )
            )
        }
    }

    /** 可点击行：标题 + 右侧内容 + 箭头 */
    @Composable
    private fun MiuiClickRow(
        title: String,
        rightContent: @Composable () -> Unit,
        onClick: () -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = TextPrimary, style = RowTitleStyle)
            Spacer(Modifier.weight(1f))
            rightContent()
            Spacer(Modifier.size(4.dp))
            RowArrowIcon(tint = TextSecondary)
        }
    }

    /** 设置项行尾图标（ic_unfolding，统一 tint 着色，以组件中心渲染精确居中） */
    @Composable
    private fun RowArrowIcon(tint: Color) {
        Icon(
            painter = painterResource(R.drawable.ic_unfolding),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }

    /** 状态圆点（绿=就绪，红=缺失） */
    @Composable
    private fun StatusDot(granted: Boolean) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (granted) GrantedGreen else MissingRed)
        )
    }

    /** MIUI 风格主按钮：全宽、圆角、蓝色 */
    @Composable
    private fun MiuiPrimaryButton(text: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
        ) {
            Text(text, fontSize = 16.sp, color = Color.White)
        }
    }

}
