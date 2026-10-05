package com.example.liquidglass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.LiquidGlass
import com.example.liquidglass.pages.CardDemo
import com.example.liquidglass.pages.HomePage
import com.example.liquidglass.pages.TabBarDemo

/**
 * 应用入口。
 *
 * 三个演示页面共享同一个 [com.example.liquidglass.glass.LiquidGlassState]，
 * 以便直观对比不同玻璃层对同一背景的折射/模糊效果。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 基准测试 / 调试入口：通过 Intent extra 定向到指定质量分级与页面，
        // 使 Macrobenchmark 能在一次运行内采集四档数据的对比。
        val forcedQuality = intent.getStringExtra(EXTRA_QUALITY)
            ?.let { name -> GlassQuality.entries.firstOrNull { it.name.equals(name, true) } }
        val initialPage = intent.getStringExtra(EXTRA_PAGE)
            ?.let { name -> DemoPage.entries.firstOrNull { it.name.equals(name, true) } }
            ?: DemoPage.Home
        val animate = intent.getBooleanExtra(EXTRA_ANIMATE, false)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color(0xFF0B1020),
                ) {
                    LiquidGlassApp(
                        forcedQuality = forcedQuality,
                        initialPage = initialPage,
                        animate = animate,
                    )
                }
            }
        }
    }

    companion object {
        /** 强制质量分级，取值 `Full` / `Medium` / `Minimal` / `Fallback`。 */
        const val EXTRA_QUALITY = "com.example.liquidglass.QUALITY"

        /** 启动时直接进入的页面，取值 `Home` / `TabBar` / `Cards`。 */
        const val EXTRA_PAGE = "com.example.liquidglass.PAGE"

        /** 是否启用背景动画（基准测试建议设为 `false` 以排除动画噪声）。 */
        const val EXTRA_ANIMATE = "com.example.liquidglass.ANIMATE"
    }
}

/** 演示页面路由。 */
internal enum class DemoPage { Home, TabBar, Cards }

/**
 * 演示应用主体：三个页面之间切换。
 *
 * @param forcedQuality 非空时强制覆盖自动决策的质量分级，用于验证降级链路
 * @param initialPage 启动时展示的页面
 * @param animate 是否启用背景动画；基准测试关闭以排除噪声
 */
@Composable
internal fun LiquidGlassApp(
    forcedQuality: GlassQuality? = null,
    initialPage: DemoPage = DemoPage.Home,
    animate: Boolean = true,
) {
    // 质量分级在 App 级别决策一次，三个页面共享
    val state = LiquidGlass.rememberState()

    if (forcedQuality != null) {
        state.quality = forcedQuality
    }

    // 页面路由：rememberSaveable 的默认 Saver 无法保存自定义枚举
    // （枚举不实现 Parcelable / Serializable），会抛
    // "cannot be saved using the current SaveableStateRegistry"。
    // 因此这里以枚举名（String）作为存储载体，读写时双向映射。
    var pageName by rememberSaveable { mutableStateOf(initialPage.name) }
    val page = DemoPage.entries.firstOrNull { it.name == pageName } ?: DemoPage.Home

    val content = @Composable {
        when (page) {
            DemoPage.Home -> HomePage(
                state = state,
                onNavigateToCards = { pageName = DemoPage.Cards.name },
                animate = animate,
            )

            DemoPage.TabBar -> TabBarDemo(
                state = state,
                onBack = { pageName = DemoPage.Home.name },
                animate = animate,
            )

            DemoPage.Cards -> CardDemo(
                state = state,
                onBack = { pageName = DemoPage.Home.name },
                animate = animate,
            )
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        content()
    }
}
