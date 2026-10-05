package com.example.liquidglass.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.liquidglass.glass.GlassCard
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.GlassTabBar
import com.example.liquidglass.glass.LiquidGlassState
import com.example.liquidglass.glass.liquidGlassSource

/**
 * 底部 TabBar 演示页。
 *
 * 底部导航栏是液态玻璃最典型的应用场景：宽度大、需要压住滚动内容、
 * 且手指点击频繁。本页同时演示：
 * - Tab 切换状态与玻璃层的联动
 * - 大面积玻璃的降采样策略（长边 > 1080px 时自动提高降采样率）
 * - 说明页：各 API 级别的降级表现对照表
 *
 * @param state 共享玻璃状态
 * @param onBack 返回首页
 */
@Composable
fun TabBarDemo(
    state: LiquidGlassState,
    onBack: () -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = listOf("首页", "卡片", "说明")

    Box(Modifier.fillMaxSize()) {
        GlassDemoBackground(Modifier.liquidGlassSource(state, enabled = true)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
            ) {
                Text(
                    text = "TabBar 演示",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "当前选中：${tabs[selected]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                )

                Spacer(Modifier.height(20.dp))

                // ---- 页面内容随 Tab 切换 ----
                when (selected) {
                    0 -> TabPane(
                        state = state,
                        title = "透镜聚光",
                        body = "底部导航栏覆盖在彩色背景之上，玻璃会把它后面的光斑" +
                            "折射并分离出彩色边缘。手指点击不同 Tab 时，边缘高光会" +
                            "跟随手指移动。",
                    )

                    1 -> TabPane(
                        state = state,
                        title = "大面积玻璃",
                        body = "TabBar 宽度接近屏幕宽度，属于大面积玻璃。" +
                            "实现上会按 resolveDownsample() 提高降采样率，" +
                            "把模糊 Pass 的填充率开销压到 1/16，" +
                            "保证滚动时仍能维持 30fps 以上。",
                    )

                    else -> DegradeMatrix(state)
                }

                Spacer(Modifier.height(120.dp))
            }

            GlassTabBar(
                state = state,
                tabs = tabs,
                selectedIndex = selected,
                onTabSelected = { selected = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
}

/** 普通内容面板。 */
@Composable
private fun TabPane(state: LiquidGlassState, title: String, body: String) {
    GlassCard(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF0C1B33),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF2B3B57),
            )
        }
    }
}

/** 各 API 级别的降级表现对照。 */
@Composable
private fun DegradeMatrix(state: LiquidGlassState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "降级对照表",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        GlassQuality.entries.forEach { q ->
            GlassCard(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (q == state.quality) Color(0xFF1B7F4B) else Color.White.copy(alpha = 0.6f),
                                androidx.compose.foundation.shape.CircleShape,
                            ),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = qualityLabel(q),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF0C1B33),
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = "模糊 ${q.blurRadius}px · 色散 ${q.dispersionSamples} 路 · " +
                                "降采样 1/${q.downsampleFactor} · α=${q.alpha}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4A5A75),
                        )
                    }
                }
            }
        }
    }
}
