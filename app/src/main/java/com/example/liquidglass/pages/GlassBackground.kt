package com.example.liquidglass.pages

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 演示背景：一张会缓慢流动的彩色渐变。
 *
 * 玻璃的折射与色散效果需要有明显纹理和高频边缘的背景才能被肉眼观察，
 * 因此这里叠加了移动的色斑与细网格，而不是纯色。
 *
 * @param modifier 布局修饰符
 * @param content 在背景之上绘制的玻璃层
 */
@Composable
fun GlassDemoBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0B1020),
                        Color(0xFF152A4A),
                        Color(0xFF1E1040),
                    ),
                ),
            ),
    ) {
        // 流动的彩色光斑：为折射提供高频细节
        val transition = rememberInfiniteTransition(label = "bg")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9000),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val blobs = listOf(
                Triple(Color(0xFFFF5F6D), 0.25f, 0.30f),
                Triple(Color(0xFF36D1DC), 0.75f, 0.25f),
                Triple(Color(0xFFFFC371), 0.60f, 0.72f),
                Triple(Color(0xFF7B2FF7), 0.20f, 0.78f),
            )
            blobs.forEachIndexed { i, (color, bx, by) ->
                val t = phase + i * 1.1f
                val cx = w * bx + cos(t) * w * 0.10f
                val cy = h * by + sin(t * 1.3f) * h * 0.10f
                val radius = minOf(w, h) * 0.32f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.72f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }

            // 高频条纹：让色散与折射更容易被观察
            val stripeGap = 14.dp.toPx()
            var y = 0f
            var i = 0
            while (y < h) {
                if (i % 2 == 0) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.07f),
                        topLeft = Offset(0f, y),
                        size = androidx.compose.ui.geometry.Size(w, stripeGap / 2f),
                    )
                }
                y += stripeGap
                i++
            }

            // 细网格
            val grid = 28.dp.toPx()
            var x = 0f
            while (x < w) {
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
                x += grid
            }
        }

        content()
    }
}

private val Int.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this.toFloat())
