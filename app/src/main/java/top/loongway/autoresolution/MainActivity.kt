package top.loongway.autoresolution

import android.content.pm.PackageManager
import android.widget.Toast
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.update
import top.loongway.autoresolution.trackpad.cursor.SecondaryDisplayController
import top.loongway.autoresolution.trackpad.gesture.TrackpadListener
import top.loongway.autoresolution.trackpad.inject.InputInjector
import top.loongway.autoresolution.trackpad.inject.ShellInputInjector
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
        // （在启动页上注入 BACK 会被系统当成退出显示模式，XREAL 会切回镜像）
        onBackPressedDispatcher.addCallback(this) {
            if (SecondaryDisplayController.hasSecondaryDisplay() &&
                !SecondaryDisplayController.isLauncherTop()
            ) {
                ShellInputInjector.back(SecondaryDisplayController.displayId)
            } else if (!SecondaryDisplayController.hasSecondaryDisplay()) {
                finish()
            }
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
            val panelMaxHeight = maxHeight * 0.5f
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Spacer(Modifier.height(12.dp))
                // 信息区：单独保留左右边距
                Column(Modifier.padding(horizontal = 16.dp)) {
                    HeaderRow(shizukuGranted = state.shizukuGranted, onEvent = onEvent)
                    Spacer(Modifier.height(14.dp))

                    if (!state.shizukuGranted) {
                        ShizukuGuide { onEvent(MainEvent.RequestShizuku) }
                        return@Column
                    }
                    DeviceInfoBar(displayInfo)
                    Spacer(Modifier.height(12.dp))
                    StatusPillRow(state, onEvent)
                }

                // 触控板面板：全出血贴左右边缘和底部，高度固定为屏高 60%
                if (displayInfo != null) {
                    Spacer(Modifier.weight(1f))
                    TrackpadPanel(Modifier.height(panelMaxHeight))
                } else {
                    NoDisplayHint(Modifier.weight(1f))
                }
            }
        }
    }

    /** 标题区：应用图标 + 标题 + Shizuku 状态 + 右上信息按钮 */
    @Composable
    private fun HeaderRow(shizukuGranted: Boolean, onEvent: (MainEvent) -> Unit) {
        var showInfo by remember { mutableStateOf(false) }
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
            // Shizuku 状态：紧跟标题，未授权时可点击请求
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(
                        1.dp,
                        if (shizukuGranted) OkGreen.copy(alpha = 0.6f) else BadRed.copy(alpha = 0.6f),
                        RoundedCornerShape(50)
                    )
                    .clickable { if (!shizukuGranted) onEvent(MainEvent.RequestShizuku) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (shizukuGranted) OkGreen else BadRed)
                )
                Spacer(Modifier.width(5.dp))
                Text("Shizuku", color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            // 信息按钮：关于与帮助
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(1.dp, HudStroke, CircleShape)
                    .clickable { showInfo = true },
                contentAlignment = Alignment.Center
            ) {
                Text("i", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showInfo) {
            InfoDialog { showInfo = false }
        }
    }

    @Composable
    private fun InfoDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            },
            title = { Text("关于与帮助") },
            text = {
                Text(
                    "将手机作为触控板，控制外接显示器：\n\n· 中部区域滑动移动触点，轻点即点击\n· 左右边缘上下滑滚动\n· 底部边缘左右滑横向滚动\n· 常用应用点击后跨屏启动到副屏\n· 连接眼镜后主屏自动息屏，触控板在黑屏下仍可操作（点「自动息屏」可关闭）\n\n需要 Shizuku 授权与无障碍触点服务。",
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        )
    }

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

    /** 状态胶囊行：权限状态纯展示 + 自动息屏开关 */
    @Composable
    private fun StatusPillRow(state: MainUiState, onEvent: (MainEvent) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudPill("通知权限", state.notification)
            HudPill("省电策略", state.batteryIgnored)
            HudPill("无障碍服务", state.a11yCursorService)
            HudPill("自动息屏", state.autoBlackScreen) {
                onEvent(MainEvent.ToggleAutoBlackScreen)
            }
        }
    }

    /** 单个状态胶囊：状态色描边 + 圆点 + 名称；[onClick] 非空时整颗可点（开关） */
    @Composable
    private fun RowScope.HudPill(label: String, ok: Boolean, onClick: (() -> Unit)? = null) {
        val color = if (ok) OkGreen else BadRed
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
                .background(color.copy(alpha = 0.10f))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(5.dp))
            Text(label, color = TextPrimary, fontSize = 12.sp)
        }
    }

    /** 未连接副屏时的占位提示 */
    @Composable
    private fun NoDisplayHint(modifier: Modifier) {
        val context = LocalContext.current
        val appIcon = remember { runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull() }
        Column(
            modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .border(1.dp, HudStroke, CircleShape)
                    .background(HudPanel),
                contentAlignment = Alignment.Center
            ) {
                if (appIcon != null) {
                    AndroidView(
                        factory = { ctx -> android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
                        update = { it.setImageDrawable(appIcon) },
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_ar_glasses),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("未连接显示器", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "连接外接显示器后，此处将变为触控板",
                color = TextSecondary,
                fontSize = 13.sp
            )
        }
    }

    /** 底部触控板面板（本体）：霓虹边框背景 + 内嵌 TrackpadView 直接处理手势（仅在已连接副屏时显示） */
    @Composable
    private fun TrackpadPanel(modifier: Modifier) {
        val context = LocalContext.current
        val trackpadView = remember {
            TrackpadView(context).apply { listener = trackpadListener }
        }
        Box(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
        ) {
            // 可拉伸霓虹边框素材（自带点阵背景），拉伸铺满面板
            Image(
                painter = painterResource(R.drawable.hud_border),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
            // 触控板本体：边框图之上接收手势
            AndroidView(
                factory = { trackpadView },
                modifier = Modifier.matchParentSize()
            )
            // 左下/右下角「关闭应用」按钮：直接结束副屏当前应用，角落固定位置便于盲操作
            CloseCornerButton(Modifier.align(Alignment.BottomStart))
            CloseCornerButton(Modifier.align(Alignment.BottomEnd))
        }
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
