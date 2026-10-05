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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * 卡片列表演示。
 *
 * 展示要点：
 * - 列表滚动中玻璃卡片的表现（长列表中每项独立捕获背景，开销较大，
 *   因此这里用轻量 [GlassQuality.Minimal] 建议的写法：卡片高度固定、
 *   数量有限，避免大面积离屏缓冲）
 * - 不同尺寸/圆角的玻璃组合
 *
 * @param state 共享玻璃状态
 * @param onBack 返回首页
 */
@Composable
fun CardDemo(
    state: LiquidGlassState,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        GlassDemoBackground(Modifier.liquidGlassSource(state, enabled = true)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 28.dp, bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Column {
                        Text(
                            text = "卡片列表",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "共 ${SAMPLE_CARDS.size} 张玻璃卡片，滚动观察边缘高光",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }

                items(SAMPLE_CARDS) { card ->
                    GlassCard(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(card.heightDp.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .androidxCircleBackground(card.accent),
                            )
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = card.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF0C1B33),
                                )
                                Spacer(Modifier.size(3.dp))
                                Text(
                                    text = card.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF3A4A66),
                                )
                            }
                            Text(
                                text = card.badge,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF1B5B8F),
                            )
                        }
                    }
                }
            }

            GlassTabBar(
                state = state,
                tabs = listOf("首页", "卡片", "说明"),
                selectedIndex = 1,
                onTabSelected = { if (it == 0) onBack() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
}

private fun Modifier.androidxCircleBackground(color: Color): Modifier =
    this.background(color, androidx.compose.foundation.shape.CircleShape)

/** 演示卡片数据。 */
private data class DemoCard(
    val title: String,
    val subtitle: String,
    val badge: String,
    val heightDp: Int,
    val accent: Color,
)

private val SAMPLE_CARDS = listOf(
    DemoCard("高度场折射", "SDF 中心差分梯度 → 法线 → 位移", "Full", 96, Color(0xFF2E7D5B)),
    DemoCard("七路色散", "RGB 沿折射轴分离采样", "Full", 112, Color(0xFF8E44AD)),
    DemoCard("Fresnel 边缘光", "掠射角反射增强 + 玻璃棱线", "Full", 128, Color(0xFF1F6FB2)),
    DemoCard("触摸动态光照", "高光随手指移动，松开后衰减", "Full", 104, Color(0xFFC0392B)),
    DemoCard("硬件模糊回退", "API 31-32 使用 RenderEffect", "Medium", 96, Color(0xFF16A085)),
    DemoCard("CPU 降采样", "API 24-30，1/3 分辨率三趟 Box 模糊", "Minimal", 112, Color(0xFFD35400)),
    DemoCard("纯渐变兜底", "API 21-23 无离屏缓冲，零 OOM 风险", "Fallback", 96, Color(0xFF7F8C8D)),
)
