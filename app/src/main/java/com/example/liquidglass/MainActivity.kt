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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color(0xFF0B1020),
                ) {
                    LiquidGlassApp()
                }
            }
        }
    }
}

/** 演示页面路由。 */
private enum class DemoPage { Home, TabBar, Cards }

/**
 * 演示应用主体：三个页面之间切换。
 */
@Composable
fun LiquidGlassApp() {
    // 质量分级在 App 级别决策一次，三个页面共享
    val state = LiquidGlass.rememberState()
    var page by rememberSaveable { mutableStateOf(DemoPage.Home) }

    val content = @Composable {
        when (page) {
            DemoPage.Home -> HomePage(
                state = state,
                onNavigateToCards = { page = DemoPage.Cards },
            )

            DemoPage.TabBar -> TabBarDemo(
                state = state,
                onBack = { page = DemoPage.Home },
            )

            DemoPage.Cards -> CardDemo(
                state = state,
                onBack = { page = DemoPage.Home },
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
