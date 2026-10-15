package top.loongway.autoresolution.trackpad.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import top.loongway.autoresolution.trackpad.AppLauncher
import top.loongway.autoresolution.trackpad.cursor.CursorView
import top.loongway.autoresolution.trackpad.cursor.SecondaryDisplayController
import java.util.Date

/**
 * 副屏启动页（Activity，HUD 暗黑风格）：顶部时钟 + 应用网格（可滚动）。
 *
 * 为什么是 Activity 而不是 Presentation：Presentation 的窗口类型
 * （TYPE_PRESENTATION）压在普通应用图层之上——从网格启动的应用会被盖在下面；
 * Activity 则遵循正常任务栈，应用盖上来、返回即回落到本页。
 *
 * 最终页语义：本页拦截返回键（无动作）、无任何退出入口；
 * 注入的点击/滑动直接落在本页控件上（网格点击 → 跨屏启动到本副屏）。
 * [cursorView] 是无障碍 overlay 不可用时的触点兜底宿主（顶层、不消费触摸）。
 */
class LauncherActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LauncherActivity"
        private const val COLOR_BG = 0xFF0A0A0A.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFF2F4F8.toInt()
        private const val COLOR_TEXT_DIM = 0xFF8A93A6.toInt()

        /** 启动后等待落屏的校验延时：覆盖 Activity 冷启动 + 副屏窗口就绪的时间 */
        private const val LANDING_CHECK_DELAY_MS = 800L

        @Volatile
        private var instance: LauncherActivity? = null

        /** 启动页是否正在副屏前台（resumed；被其它应用覆盖时为 false） */
        @Volatile
        private var resumed = false

        fun isTop(): Boolean = resumed

        /** 兜底触点宿主（无障碍 overlay 不可用时由控制器驱动） */
        fun instanceCursorView(): CursorView? = instance?.cursorView

        /** 结束副屏上的启动页（显示器拔出时由控制器调用） */
        fun finishInstance() {
            instance?.finish()
        }

        /** 在指定副屏上启动启动页（框架跨屏路线，落屏校验失败或抛异常退回 shell am start） */
        fun startOnDisplay(context: Context, displayId: Int) {
            val intent = Intent(context, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                    context.startActivity(intent, options.toBundle())
                } else {
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                // 兜底：shell 身份跨屏启动（部分 ROM 限制应用进程的跨屏 startActivity）
                Log.w(TAG, "framework launch threw, fallback to shell am start", e)
                relaunchViaShell(context, displayId)
                return
            }
            // 校验实际落屏：副屏无法承载 Activity 时，框架会把启动「静默」回落到主屏
            // （不抛异常，catch 不到）。此时结束错位实例，改用 shell 身份重试
            // （shell 的 am start --display 不受应用层跨屏限制）。
            Handler(Looper.getMainLooper()).postDelayed({
                val inst = instance ?: return@postDelayed
                val landed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    inst.display?.displayId ?: Display.INVALID_DISPLAY
                } else Display.INVALID_DISPLAY
                if (landed != displayId) {
                    Log.w(TAG, "launcher landed on display $landed, expected $displayId; retry via shell")
                    inst.finish()
                    relaunchViaShell(context, displayId)
                }
            }, LANDING_CHECK_DELAY_MS)
        }

        private fun relaunchViaShell(context: Context, displayId: Int) {
            top.loongway.autoresolution.ResolutionManager.execShellAsync(
                "am start --display $displayId -n ${context.packageName}/top.loongway.autoresolution.trackpad.launcher.LauncherActivity"
            )
        }
    }

    /** 触点兜底宿主（无障碍 overlay 不可用时由控制器驱动） */
    lateinit var cursorView: CursorView
        private set

    private val handler = Handler(Looper.getMainLooper())
    // 惰性取值：字段初始化器在构造阶段执行，此时上下文尚未 attach，直接读 resources 会 NPE
    private val density by lazy { resources.displayMetrics.density }
    private var clockView: TextView? = null

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // 最终页：吞掉返回键，禁止任何退出路径
        onBackPressedDispatcher.addCallback(this) { /* 无动作：启动页不可退出 */ }

        val root = FrameLayout(this).apply { setBackgroundColor(COLOR_BG) }
        root.addView(buildContent())
        // 触点叠加在最上层；CursorView 默认不消费触摸，点击可命中下层控件
        cursorView = CursorView(this)
        root.addView(
            cursorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SecondaryDisplayController.onLauncherResumed()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun buildContent(): View {
        // 尺寸自适应：取显示器【最短边】均分 4 份作为单元格基准——
        // 横屏（带鱼屏）短边是高、竖屏短边是宽，两种方向都能保证一屏约 4 行/列；
        // 眼镜 dp 密度不可靠，全部按实际像素计算
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(real)
        val isLandscape = real.x >= real.y
        cellSize = minOf(real.x, real.y) / 4f
        // 横屏：按宽能容纳的单元格数定列数；竖屏：固定 4 列（行数自动增长）
        columns = if (isLandscape) (real.x / cellSize).toInt().coerceAtLeast(4) else 4
        val pad = (cellSize * 0.08f).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(pad, pad, pad, pad)
            addView(buildHeader(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(buildGrid(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }
    }

    /** 单元格基准尺寸（px，最短边/4，buildContent 时初始化） */
    private var cellSize = 100f

    /** 网格列数（buildContent 时初始化） */
    private var columns = 4

    /** 顶部：大号时钟 + 右侧应用名（紧凑：为网格留足 4 行空间） */
    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (cellSize * 0.05f).toInt()
            setPadding(pad, pad / 2, pad, (cellSize * 0.08f).toInt())
            addView(TextView(this@LauncherActivity).apply {
                clockView = this
                text = "00:00"
                textSize = cellSize * 0.24f
                setTextColor(COLOR_TEXT_PRIMARY)
                paint.isFakeBoldText = true
            })
            addView(TextView(this@LauncherActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(this@LauncherActivity).apply {
                text = "AR副屏助手"
                textSize = cellSize * 0.12f
                setTextColor(COLOR_TEXT_DIM)
            })
        }
    }

    /** 应用网格：图标 + 名称（全部应用，纵向滚动），点击跨屏启动 */
    private fun buildGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = columns
        }
        for (app in AppLauncher.queryLaunchable(this)) {
            // 单元格内容按 cellSize 的 0.75 收紧，保证短边方向放下 4 整行
            val iconSize = (cellSize * 0.40f).toInt()
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = (cellSize * 0.05f).toInt()
                setPadding(pad, pad, pad, pad)
                addView(ImageView(this@LauncherActivity), iconSize, iconSize)
                addView(TextView(this@LauncherActivity).apply {
                    text = app.label
                    textSize = cellSize * 0.13f
                    setTextColor(COLOR_TEXT_DIM)
                    maxLines = 1
                    gravity = Gravity.CENTER
                    // 与图标同宽并水平居中：避免标签按内容宽度左对齐产生错位
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (cellSize * 0.05f).toInt() }
                })
                isClickable = true
                setOnClickListener {
                    SecondaryDisplayController.onAppLaunched(app.packageName)
                    AppLauncher.launchOnDisplay(this@LauncherActivity, app, display.displayId)
                }
            }
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(grid, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun updateClock() {
        clockView?.text = DateFormat.getTimeFormat(this).format(Date())
    }
}
