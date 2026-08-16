package top.loongway.autoresolution

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku
import top.loongway.autoresolution.ui.theme.AutoResolutionTheme

/**
 * 用户界面：显示 Shizuku 授权状态 + 目标分辨率 + 停止按钮。
 * Shizuku 已授权时 5 秒后自动退出，防止被任务列表划掉杀死服务。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoResolutionTheme {
                MainScreen()
            }
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
            Settings.canDrawOverlays(this)
        ) {
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 5000)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从悬浮窗授权页返回后立即生效
        ResolutionManager.rebind()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val context = LocalContext.current

        var shizukuGranted by remember {
            mutableStateOf(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text(getString(R.string.app_name)) }) }
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Shizuku: ${if (shizukuGranted) "已授权，5秒后自动退出程序" else "未授权"}")
                Text("目标分辨率: 1080x1920")

                if (!Settings.canDrawOverlays(context)) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }) {
                        Text("授予悬浮窗权限（音量键控制屏幕）")
                    }
                }

                Button(onClick = { DisplayMonitorService.stop(context) }) {
                    Text("停止后台监听")
                }
            }
        }
    }
}
