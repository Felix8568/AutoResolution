package top.loongway.autoresolution

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.update
import top.loongway.autoresolution.trackpad.TrackpadSettings
import top.loongway.autoresolution.trackpad.cursor.SecondaryDisplayController
import top.loongway.autoresolution.trackpad.gesture.TrackpadListener
import top.loongway.autoresolution.trackpad.inject.InputInjector
import top.loongway.autoresolution.trackpad.inject.ShellInputInjector
import top.loongway.autoresolution.trackpad.view.TouchBridgeWindow
import top.loongway.autoresolution.trackpad.view.TrackpadView
import top.loongway.autoresolution.ui.theme.AutoResolutionTheme
import top.loongway.autoresolution.ui.theme.HudBg
import top.loongway.autoresolution.ui.theme.HudGreen
import top.loongway.autoresolution.ui.theme.HudPanel
import top.loongway.autoresolution.ui.theme.HudRed
import top.loongway.autoresolution.ui.theme.HudStatusRed
import top.loongway.autoresolution.ui.theme.HudStroke
import top.loongway.autoresolution.ui.theme.HudTextPrimary
import top.loongway.autoresolution.ui.theme.HudTextSecondary

/**
 * 主界面（MVVM + UDF）：HUD 暗黑霓虹风格。
 * 底部大面板即触控板本体：内嵌 [TrackpadView]，手势直接在主界面处理。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 触控板常驻主界面：保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 拦截返回键：副屏被其它应用覆盖时注入返回；焦点仍在启动页时无动作
        // （在启动页上注入 BACK 会被系统当成退出显示模式，XREAL 会切回镜像）。
        // 兜底捕获异常：快速连续返回会并发多次注入，任何一处出错都不应导致崩溃
        onBackPressedDispatcher.addCallback(this) {
            runCatching {
                if (SecondaryDisplayController.hasSecondaryDisplay() &&
                    !SecondaryDisplayController.isLauncherTop()
                ) {
                    ShellInputInjector.back(SecondaryDisplayController.displayId)
                } else if (!SecondaryDisplayController.hasSecondaryDisplay()) {
                    finish()
                }
            }.onFailure { Log.e("MainActivity", "back dispatch failed", it) }
        }
        // 幂等绑定注入服务：后台监听未启动时触控板也能独立工作；
        // 预热 Shell 服务，避免首次跨屏启动退回框架路线
        InputInjector.bind()
        ResolutionManager.execShellAsync("true")

        setContent {
            AutoResolutionTheme {
                val state by viewModel.uiState.collectAsState()
                MainScreen(state = state, onEvent = viewModel::onEvent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        // 用户可能中途去开了无障碍：回前台时把触点热切换到可信 overlay
        SecondaryDisplayController.ensureCursorHostUpToDate()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        permissionsGranted: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, permissionsGranted)
        if (requestCode == MainViewModel.SHIZUKU_REQUEST_CODE) {
            val granted = permissionsGranted.isNotEmpty() &&
                permissionsGranted[0] == PackageManager.PERMISSION_GRANTED
            viewModel.onShizukuResult(granted)
        }
    }

    // ---- HUD 配色（局部别名） ----
    private val TextPrimary = HudTextPrimary
    private val TextSecondary = HudTextSecondary
    private val AccentRed = HudRed
    private val OkGreen = HudGreen
    private val BadRed = HudStatusRed

    @Composable
    private fun MainScreen(state: MainUiState, onEvent: (MainEvent) -> Unit) {
        // 副屏连接信息（单一真值，热插拔实时刷新）
        val context = LocalContext.current
        var displayInfo by remember { mutableStateOf(displayInfoText(context)) }
        DisposableEffect(Unit) {
            val dm = context.getSystemService(DisplayManager::class.java)
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(id: Int) { displayInfo = displayInfoText(context) }
                override fun onDisplayChanged(id: Int) { displayInfo = displayInfoText(context) }
                override fun onDisplayRemoved(id: Int) { displayInfo = displayInfoText(context) }
            }
            dm.registerDisplayListener(listener, null)
            onDispose { dm.unregisterDisplayListener(listener) }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(HudBg)
        ) {
            // 触控板高度实时可调（设置弹窗改动 → 状态变化 → 此处重组，面板立即伸缩）
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Spacer(Modifier.height(12.dp))
                // 信息区：单独保留左右边距
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HeaderRow(state = state, onEvent = onEvent)
                    Spacer(Modifier.height(14.dp))

                    if (!state.shizukuGranted) {
                        ShizukuGuide { onEvent(MainEvent.RequestShizuku) }
                        return@Column
                    }
                    DeviceInfoBar(displayInfo)
                }

                // 面板区：占据剩余空间，顶部 14dp 与信息条间隔（与上方间距对称）。
                // 触控板高度 = 面板区高度 × 设置比例，100% 档贴齐间隔线，
                // 任何档位都不会遮挡顶部内容；无副屏时手势经守卫自然无效果
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    val panelHeight = maxHeight * TrackpadSettings.panelHeight
                    TrackpadPanel(
                        Modifier
                            .height(panelHeight)
                            .align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    /**
     * 标题区：应用图标 + 标题 + 统一状态胶囊 + 设置按钮。
     * 四项权限（Shizuku/通知/省电/无障碍）收敛为一颗状态胶囊：
     * 有问题红色并写明缺什么，全就绪绿色；Shizuku 未授权时点击可请求。
     */
    @Composable
    private fun HeaderRow(state: MainUiState, onEvent: (MainEvent) -> Unit) {
        var showSettings by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val appIcon = remember { runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (appIcon != null) {
                AndroidView(
                    factory = { ctx -> android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
                    update = { it.setImageDrawable(appIcon) },
                    modifier = Modifier.size(34.dp)
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_ar_glasses),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "AR副屏助手",
                style = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
            Spacer(Modifier.width(10.dp))
            // 统一状态胶囊：四项权限任何一项缺失都会显形
            HudPill(
                label = when {
                    !state.shizukuGranted -> "Shizuku 未授权"
                    !state.notification -> "通知权限未授予"
                    !state.batteryIgnored -> "省电策略未放行"
                    !state.a11yCursorService -> "无障碍未开启"
                    else -> "已就绪"
                },
                ok = state.allGranted && state.a11yCursorService,
                onClick = if (!state.shizukuGranted) ({ onEvent(MainEvent.RequestShizuku) }) else null
            )
            Spacer(Modifier.weight(1f))
            // 设置按钮：触控板设置与简要帮助
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, HudStroke, CircleShape)
                    .clickable { showSettings = true },
                contentAlignment = Alignment.Center
            ) {
                Text("⚙", color = TextSecondary, fontSize = 15.sp)
            }
        }
        if (showSettings) {
            TrackpadSettingsDialog(state, onEvent) { showSettings = false }
        }
    }

    /**
     * 触控板设置（HUD 风格自绘面板）：自动息屏开关 + 面板高度/边缘滚动区滑杆 + 简要帮助。
     * 滑杆改动即时生效（面板在弹窗后实时伸缩），开关与持久化走 UDF 事件。
     */
    @Composable
    private fun TrackpadSettingsDialog(
        state: MainUiState,
        onEvent: (MainEvent) -> Unit,
        onDismiss: () -> Unit,
    ) {
        // 在组合期读取状态：滑杆拖动 → 设置变化 → 本对话框与背后的触控板同时重组
        val heightPct = (TrackpadSettings.panelHeight * 100).toInt()
        val sidePct = (TrackpadSettings.edgeRatio * 100).toInt()
        val bottomDp = TrackpadSettings.bottomStripDp.toInt()
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(HudPanel)
                    .border(1.dp, HudStroke, RoundedCornerShape(18.dp))
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                // 标题行：标题 + 右上角关闭钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "触控板设置",
                        color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(1.dp, HudStroke, CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✕", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                HudDivider()

                // 自动息屏
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自动息屏",
                            color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "连接眼镜后自动熄灭本机屏幕，摘下眼镜自动恢复；黑屏期间触控板照常可用",
                            color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.autoBlackScreen,
                        onCheckedChange = { onEvent(MainEvent.ToggleAutoBlackScreen) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OkGreen,
                            checkedTrackColor = OkGreen.copy(alpha = 0.35f),
                            checkedBorderColor = OkGreen.copy(alpha = 0.6f),
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = Color.Transparent,
                            uncheckedBorderColor = HudStroke
                        )
                    )
                }
                HudDivider()

                // 滑杆区：标签居左、实时数值居右（HUD 绿），档位点隐藏保持干净
                SettingSlider(
                    label = "触控板高度", valueText = "$heightPct%",
                    value = TrackpadSettings.panelHeight,
                    range = TrackpadSettings.panelHeightRange, steps = 9 // 50/55/…/100
                ) {
                    TrackpadSettings.updatePanelHeight(snapToStep(it, TrackpadSettings.PANEL_HEIGHT_STEP))
                }
                Spacer(Modifier.height(14.dp))
                SettingSlider(
                    label = "左右滚动区宽度", valueText = "$sidePct%",
                    value = TrackpadSettings.edgeRatio,
                    range = TrackpadSettings.edgeRatioRange, steps = 3 // 10/15/20/25
                ) {
                    TrackpadSettings.updateEdgeRatio(snapToStep(it, TrackpadSettings.EDGE_RATIO_STEP))
                }
                Spacer(Modifier.height(14.dp))
                SettingSlider(
                    label = "底部横滚区高度", valueText = "$bottomDp dp",
                    value = TrackpadSettings.bottomStripDp,
                    range = TrackpadSettings.bottomStripRange, steps = 9 // 40/48/…/120
                ) {
                    TrackpadSettings.updateBottomStripDp(snapToStep(it, TrackpadSettings.BOTTOM_STRIP_STEP))
                }
                HudDivider()

                // 使用说明
                Text(
                    "使用说明",
                    color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "· 中间滑动移动触点，轻点即单击\n· 按住不动再滑动，即可拖动内容\n· 左右边缘上下滑：滚动\n· 底部边缘左右滑：横向滚动\n· 眼镜启动页轻点图标打开应用，触控板角落 ✕ 关闭应用",
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 19.sp
                )
                // 无「完成」按钮：所有改动即改即存，无需确认；关闭走 ✕ / 点外部 / 返回键
            }
        }
    }

    /** HUD 分割线 */
    @Composable
    private fun HudDivider() {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 14.dp),
            thickness = 1.dp,
            color = HudStroke.copy(alpha = 0.6f)
        )
    }

    /** 设置滑杆：标签居左 + 实时数值居右（绿），隐藏档位圆点保持干净 */
    @Composable
    private fun SettingSlider(
        label: String,
        valueText: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        onChange: (Float) -> Unit,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(valueText, color = OkGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = OkGreen,
                    activeTrackColor = OkGreen,
                    inactiveTrackColor = HudStroke,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }
    }

    /** 滑杆值吸附到步进档位（5% 一档），避免浮点中间值 */
    private fun snapToStep(v: Float, step: Float): Float =
        Math.round(v / step) * step

    /** Shizuku 未授权引导页 */
    @Composable
    private fun ShizukuGuide(onRequest: () -> Unit) {
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, AccentRed.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .background(HudPanel)
                .padding(20.dp)
        ) {
            Text("需要 Shizuku 授权", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "触控板注入与副屏触点依赖 Shizuku。请先启动 Shizuku 服务，再授权本应用。",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRequest,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) {
            Text("授予 Shizuku 权限", fontSize = 15.sp, color = Color.White)
        }
    }

    /** 设备信息条：显示外接显示器「名称 · 宽×高 · 刷新率」，未连接时红点提示 */
    @Composable
    private fun DeviceInfoBar(info: String?) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, HudStroke, RoundedCornerShape(10.dp))
                .background(HudPanel)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (info != null) OkGreen else BadRed)
            )
            Spacer(Modifier.width(8.dp))
            Text(info ?: "未检测到外接显示器", color = TextPrimary, fontSize = 13.sp)
        }
    }

    /** 读取第一块外接演示屏（非默认屏且带 FLAG_PRESENTATION）的描述 */
    private fun displayInfoText(context: android.content.Context): String? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = SecondaryDisplayController.displayId.takeIf { it != Display.INVALID_DISPLAY }
            ?.let { dm.getDisplay(it) }
            ?: dm.displays.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY &&
                    it.flags and Display.FLAG_PRESENTATION != 0
            }
            ?: return null
        val mode = display.mode
        return "${display.name} · ${mode.physicalWidth}×${mode.physicalHeight} · ${mode.refreshRate.toInt()}Hz"
    }

    /**
     * 单个状态胶囊：随内容宽（不拉伸）。[ok] 控制绿/红语义色，
     * 红色只用于真正的权限问题；[onClick] 非空时整颗可点。
     */
    @Composable
    private fun HudPill(label: String, ok: Boolean, onClick: (() -> Unit)? = null) {
        val color = if (ok) OkGreen else BadRed
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
                .background(color.copy(alpha = 0.10f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, color = TextPrimary, fontSize = 12.sp)
        }
    }

    /**
     * 底部触控板（本体）：内嵌 TrackpadView 处理手势，分区高亮由 View 自绘（常驻显示）。
     *
     * 真实触摸不直接交给主窗口：触摸会让主窗口抢到全局焦点，眼镜侧输入框的键盘
     * 随即消失；主窗口声明为不可聚焦又会引发返回键 ANR。所以由 [TouchBridgeWindow]
     * （不可聚焦的子窗口）接收面板区域的触摸并转发回来——焦点不动，手势逻辑不变。
     */
    @Composable
    private fun TrackpadPanel(modifier: Modifier) {
        val context = LocalContext.current
        val trackpadView = remember {
            TrackpadView(context).apply { listener = trackpadListener }
        }
        // 边缘分区实时同步：设置弹窗改动 → 状态变化 → 重组 → 此处写入 View
        val edgeRatio = TrackpadSettings.edgeRatio
        val bottomStripPx = with(LocalDensity.current) { TrackpadSettings.bottomStripDp.dp.toPx() }
        val bridge = remember { TouchBridgeWindow(context as Activity, window.decorView) }
        Box(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(HudPanel)
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    bridge.setFrame(b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
                }
        ) {
            // 触控板本体：滚动区高亮与分界线由 TrackpadView 自绘
            AndroidView(
                factory = { trackpadView },
                update = {
                    it.edgeRatio = edgeRatio
                    it.bottomStripPx = bottomStripPx
                },
                modifier = Modifier.matchParentSize()
            )
            // 左下/右下角「关闭应用」按钮：直接结束副屏当前应用，角落固定位置便于盲操作
            CloseCornerButton(Modifier.align(Alignment.BottomStart))
            CloseCornerButton(Modifier.align(Alignment.BottomEnd))
        }
        DisposableEffect(Unit) { onDispose { bridge.detach() } }
    }

    /** 角落关闭按钮：红色描边圆钮 ✕，贴屏幕最角落便于盲操作 */
    @Composable
    private fun CloseCornerButton(modifier: Modifier) {
        val context = LocalContext.current
        Box(
            modifier
                .padding(6.dp)
                .size(44.dp)
                .clip(CircleShape)
                .border(1.5.dp, AccentRed.copy(alpha = 0.7f), CircleShape)
                .background(AccentRed.copy(alpha = 0.12f))
                .clickable {
                    if (SecondaryDisplayController.hasSecondaryDisplay()) {
                        SecondaryDisplayController.closeSecondaryApp()
                    } else {
                        Toast.makeText(context, "副屏未连接", Toast.LENGTH_SHORT).show()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = AccentRed, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }

    /** View 只发指令，副屏/注入细节全部收敛在控制器内 */
    private val trackpadListener = object : TrackpadListener {
        override fun onCursorMove(dx: Float, dy: Float) =
            SecondaryDisplayController.onCursorMove(dx, dy)

        override fun onSwipeBegin(zone: top.loongway.autoresolution.trackpad.gesture.TrackpadZone, nx: Float, ny: Float) =
            SecondaryDisplayController.onSwipeBegin(zone, nx, ny)

        override fun onSwipeMove(dx: Float, dy: Float) =
            SecondaryDisplayController.onSwipeMove(dx, dy)

        override fun onSwipeEnd() =
            SecondaryDisplayController.onSwipeEnd()

        override fun onVerticalScroll(ticks: Int) =
            SecondaryDisplayController.onVerticalScroll(ticks)

        override fun onHorizontalScroll(ticks: Int) =
            SecondaryDisplayController.onHorizontalScroll(ticks)

        override fun onTap(x: Float, y: Float) =
            SecondaryDisplayController.onTap()

        override fun onTouchActive() =
            SecondaryDisplayController.onUserActive()
    }
}
