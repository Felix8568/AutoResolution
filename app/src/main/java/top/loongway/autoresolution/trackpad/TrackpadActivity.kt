package top.loongway.autoresolution.trackpad

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.loongway.autoresolution.ResolutionManager
import top.loongway.autoresolution.trackpad.a11y.AccessibilityRepair
import top.loongway.autoresolution.trackpad.a11y.CursorAccessibilityService
import top.loongway.autoresolution.trackpad.cursor.SecondaryDisplayController
import top.loongway.autoresolution.trackpad.gesture.TrackpadListener
import top.loongway.autoresolution.trackpad.inject.InputInjector
import top.loongway.autoresolution.trackpad.view.TrackpadView

/**
 * 触控板全屏页：主屏整块屏幕即触控板，手势指令经 [trackpadListener]
 * 转发给 [SecondaryDisplayController]（跨屏通信唯一通道，职责单一）。
 * 右上角「应用」按钮打开启动器，可把第三方应用跨屏启动到副屏。
 */
class TrackpadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 触控板常亮 + 沉浸式全屏（最大化可操作面积）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // 幂等绑定注入服务：后台监听未启动时触控板也能独立工作
        InputInjector.bind()
        // 预热 Shell 服务（execShellAsync 内部幂等懒绑定）：
        // 应用启动器优先走 am start，避免首次点击时 shell 尚未就绪而退回框架路线
        ResolutionManager.execShellAsync("true")

        val density = resources.displayMetrics.density
        val trackpad = TrackpadView(this).apply { listener = trackpadListener }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF141414.toInt()) // 深色磨砂触控板底色
        }
        root.addView(
            trackpad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 「应用」入口：叠加在触控板之上（后添加者在上层），
        // 作为独立 View 接收自己的点击，不会落进 TrackpadView 的手势处理
        val launcherChip = TextView(this).apply {
            text = "应用"
            textSize = 13f
            setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            val pad = (12 * density).toInt()
            setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad)
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(0x2EFFFFFF)
            }
        }
        root.addView(
            launcherChip,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                marginEnd = (16 * density).toInt()
                topMargin = (16 * density).toInt()
            }
        )
        launcherChip.setOnClickListener { showAppLauncher() }

        setContentView(root)

        if (!SecondaryDisplayController.hasSecondaryDisplay()) {
            Toast.makeText(this, "未检测到副屏，连接显示器后再试", Toast.LENGTH_LONG).show()
        } else if (!CursorAccessibilityService.isAvailable()) {
            // 副屏触点强依赖 Shizuku（可信 overlay + 事件注入都经它授权）
            AccessibilityRepair.enableNow()
            Toast.makeText(this, "副屏触点依赖 Shizuku，请确认 Shizuku 已启动并授权", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能中途去开了无障碍：回前台时把触点热切换到可信 overlay
        SecondaryDisplayController.ensureCursorHostUpToDate()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 故意吞掉返回：左右边缘滚动可能被系统派发为返回，误触即退出触控板。
        // 退出方式：从屏幕底部中央上滑（Home 手势不受影响）
    }

    /** 应用启动器：列出启动器应用，选中后跨屏启动到副屏 */
    private fun showAppLauncher() {
        if (!SecondaryDisplayController.hasSecondaryDisplay()) {
            Toast.makeText(this, "未检测到副屏，无法在副屏打开应用", Toast.LENGTH_LONG).show()
            return
        }
        val apps = AppLauncher.queryLaunchable(this)
        if (apps.isEmpty()) {
            Toast.makeText(this, "未查询到可启动的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val adapter = object : ArrayAdapter<AppLauncher.AppInfo>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                // 无 XML 布局：代码构建「图标 + 名称」行，并复用 convertView
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = (16 * density).toInt()
                    setPadding(pad, pad / 2, pad, pad / 2)
                    addView(ImageView(context), (40 * density).toInt(), (40 * density).toInt())
                    addView(
                        TextView(context).apply {
                            textSize = 15f
                            setTextColor(0xFF1A1A1A.toInt())
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = (14 * density).toInt() }
                    )
                }
                val item = getItem(position)!!
                (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
                (row.getChildAt(1) as TextView).text = item.label
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("在副屏打开应用")
            .setAdapter(adapter) { dialog, which ->
                dialog.dismiss()
                val app = apps[which]
                AppLauncher.launchOnDisplay(this, app, SecondaryDisplayController.displayId)
                Toast.makeText(this, "正在副屏打开：${app.label}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** View 只发指令，副屏/注入细节全部收敛在控制器内 */
    private val trackpadListener = object : TrackpadListener {
        override fun onCursorMove(dx: Float, dy: Float) =
            SecondaryDisplayController.onCursorMove(dx, dy)

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
