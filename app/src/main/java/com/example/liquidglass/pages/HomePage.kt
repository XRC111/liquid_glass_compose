package com.example.liquidglass.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.liquidglass.glass.GlassCard
import com.example.liquidglass.glass.GlassQuality
import com.example.liquidglass.glass.GlassTabBar
import com.example.liquidglass.glass.LiquidGlass
import com.example.liquidglass.glass.LiquidGlassState
import com.example.liquidglass.glass.liquidGlass
import com.example.liquidglass.glass.liquidGlassSource

/**
 * 演示首页：单卡片 + 触摸跟随高光。
 *
 * 展示内容：
 * - 当前设备命中的质量分级与降级原因
 * - 单张玻璃卡片，手指在卡片上移动时边缘高光跟随
 * - 实时模糊耗时
 *
 * @param state 共享玻璃状态
 * @param onNavigateToCards 跳转到卡片列表页
 */
@Composable
fun HomePage(
    state: LiquidGlassState,
    onNavigateToCards: () -> Unit,
    animate: Boolean = true,
) {
    Box(Modifier.fillMaxSize()) {
        GlassDemoBackground(
            modifier = Modifier.liquidGlassSource(state, enabled = true),
            animate = animate,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QualityBanner(state)

                Spacer(Modifier.height(24.dp))

                // ---- 主演示卡片：手指在其上移动可看到高光跟随 ----
                GlassCard(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Liquid Glass",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0C1B33),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "把手指放在卡片上移动，观察边缘高光与背景折射",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF2B3B57),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Column {
                                Text(
                                    text = qualityLabel(state.quality),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF1B4B8F),
                                )
                                Text(
                                    text = "模糊耗时 ${"%.1f".format(state.lastRenderCostMs)} ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4A5A75),
                                )
                            }
                            Text(
                                text = "API ${android.os.Build.VERSION.SDK_INT}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF6A7A95),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ---- 参数调节：实时改变玻璃厚度与色散 ----
                GlassCard(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "玻璃参数",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF0C1B33),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ParamChip("薄", selected = state.thickness.value < 18f) {
                                state.thickness = 12.dp
                            }
                            ParamChip("标准", selected = state.thickness.value in 18f..28f) {
                                state.thickness = 20.dp
                            }
                            ParamChip("厚", selected = state.thickness.value > 28f) {
                                state.thickness = 34.dp
                            }
                        }
                        Text(
                            text = "当前厚度 ${state.thickness.value.toInt()} dp · " +
                                "圆角 ${state.cornerRadius.value.toInt()} dp",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4A5A75),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ---- 手动切换质量分级，用于验证降级链路 ----
                GlassCard(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                    ) {
                        Text(
                            text = "强制质量分级（验证降级）",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF0C1B33),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GlassQuality.entries.forEach { q ->
                                ParamChip(
                                    label = q.name,
                                    selected = state.quality == q,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    state.quality = q
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "色散 ${state.quality.dispersionSamples} 路 · " +
                                "降采样 1/${state.quality.downsampleFactor} · " +
                                if (state.quality.supportsRefraction) "支持折射" else "无折射",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4A5A75),
                        )
                    }
                }

                Spacer(Modifier.height(96.dp))
            }

            // ---- 底部 TabBar ----
            GlassTabBar(
                state = state,
                tabs = listOf("首页", "卡片", "说明"),
                selectedIndex = 0,
                onTabSelected = { if (it == 1) onNavigateToCards() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
}

/** 顶部质量分级横幅。 */
@Composable
private fun QualityBanner(state: LiquidGlassState) {
    val q = state.quality
    val bg = when (q) {
        GlassQuality.Full -> Color(0xFF1B7F4B)
        GlassQuality.Medium -> Color(0xFF1B5B8F)
        GlassQuality.Minimal -> Color(0xFF8F6A1B)
        GlassQuality.Fallback -> Color(0xFF8F3A1B)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .androidxBackground(bg),
        )
        Text(
            text = qualityLabel(q),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** 把 [Modifier] 简写为带背景的扩展，保持页面代码可读。 */
private fun Modifier.androidxBackground(color: Color): Modifier =
    this.background(color, RoundedCornerShape(percent = 50))

/** 参数选择胶囊。 */
@Composable
private fun ParamChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .androidxClickable(onClick)
            .androidxBackground(
                if (selected) Color(0xFF1B5B8F) else Color.White.copy(alpha = 0.35f),
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else Color(0xFF1B2E4A),
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** 质量分级的中文说明。 */
internal fun qualityLabel(q: GlassQuality): String = when (q) {
    GlassQuality.Full -> "Full · AGSL 折射 + 7 路色散"
    GlassQuality.Medium -> "Medium · RenderEffect 模糊"
    GlassQuality.Minimal -> "Minimal · CPU 降采样模糊"
    GlassQuality.Fallback -> "Fallback · 渐变模拟"
}
