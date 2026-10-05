package com.example.liquidglass.glass

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.max

/**
 * 液态玻璃的低版本回退实现。
 *
 * 覆盖两条无法使用 GPU 渲染后端的路径：
 *
 * - **[GlassQuality.Minimal]（API 24-30）** — 无 [android.graphics.RenderEffect]，
 *   而 `Modifier.blur()` 在 API 31 以下会被 Compose 静默忽略（no-op）。
 *   因此这里直接绘制上层管线（[LiquidGlass]）已完成的 CPU 模糊背景位图，
 *   再叠加色调与边缘高光。
 * - **[GlassQuality.Fallback]（API 21-23）** — 不做任何模糊，
 *   仅绘制半透明渐变 + 1.6px 边框 + 触摸高光。
 *
 * 两个级别都通过 `Modifier.drawWithCache` 只在尺寸/状态变化时重算几何，
 * 每帧只做简单 GPU 绘制，保证低端设备也能维持 30fps。
 */
object LiquidGlassFallback {

    /**
     * 创建回退渲染器。
     *
     * @param quality 目标质量级别
     */
    fun create(quality: GlassQuality): Renderer = when (quality) {
        GlassQuality.Minimal -> CpuBackedRenderer(quality)
        else -> GradientRenderer(quality)
    }

    /**
     * 回退渲染器接口。实现类负责在 [DrawScope] 中绘制玻璃视觉。
     */
    interface Renderer {
        /**
         * 绘制玻璃底层（模糊位图或渐变）。
         *
         * @param cornerRadiusPx 圆角半径（px）
         * @param alpha 玻璃透明度（0f~1f）
         * @param touchPoint 当前触摸点（容器局部坐标）
         * @param touchStrength 触摸强度（0f~1f）
         */
        fun DrawScope.drawGlassBase(
            cornerRadiusPx: Float,
            alpha: Float,
            touchPoint: Offset?,
            touchStrength: Float,
        )

        /**
         * 绘制玻璃棱线与边缘高光。
         */
        fun DrawScope.drawGlassRim(
            cornerRadiusPx: Float,
            touchPoint: Offset?,
            touchStrength: Float,
        )
    }

    /**
     * API 21-23 使用的纯渐变渲染器：不采样背景，不做模糊。
     *
     * 通过对角线性渐变模拟玻璃的体积感（左上偏亮为光源，右下偏暗为阴影），
     * 再叠加顶部反射带与沿触摸方向的高光。
     */
    class GradientRenderer(private val quality: GlassQuality) : Renderer {

        override fun DrawScope.drawGlassBase(
            cornerRadiusPx: Float,
            alpha: Float,
            touchPoint: Offset?,
            touchStrength: Float,
        ) {
            val r = cornerRadiusPx.coerceAtLeast(0f)
            val a = (alpha * quality.alpha).coerceIn(0f, 1f)

            // 主体：对角渐变，模拟玻璃厚度带来的明暗过渡
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = a * 0.55f),
                        Color(0xFFDCEBFF).copy(alpha = a * 0.28f),
                        Color(0xFF9FC4F0).copy(alpha = a * 0.22f),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                cornerRadius = CornerRadius(r, r),
            )

            // 顶部高光带：模拟上表面反射
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = a * 0.42f), Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.45f,
                ),
                cornerRadius = CornerRadius(r, r),
            )

            // 触摸局部高光
            if (touchPoint != null && touchStrength > 0f) {
                val radius = max(size.width, size.height) * 0.55f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.30f * touchStrength),
                            Color.Transparent,
                        ),
                        center = touchPoint,
                        radius = radius,
                    ),
                    radius = radius,
                    center = touchPoint,
                )
            }
        }

        override fun DrawScope.drawGlassRim(
            cornerRadiusPx: Float,
            touchPoint: Offset?,
            touchStrength: Float,
        ) {
            val r = cornerRadiusPx.coerceAtLeast(0f)
            // 外发光：模拟玻璃边缘的散射
            drawRoundRect(
                color = Color.White.copy(alpha = 0.30f + 0.25f * touchStrength),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.6f),
            )
            // 内侧细线，增强玻璃厚度感
            val inner = (r - 2f).coerceAtLeast(0f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(2f, 2f),
                size = Size(size.width - 4f, size.height - 4f),
                cornerRadius = CornerRadius(inner, inner),
                style = Stroke(width = 1f),
            )
        }
    }

    /**
     * API 24-30 使用的 CPU 模糊渲染器。
     *
     * 模糊本身由上层 [LiquidGlass] 的后台线程完成（低分辨率位图 + 三趟
     * 可分离 Box 模糊），本渲染器只负责把结果按圆角裁剪绘制，并叠加
     * 色调与边缘高光。若背景位图缺失，则降级为渐变，保证不崩。
     */
    class CpuBackedRenderer(private val quality: GlassQuality) : Renderer {

        override fun DrawScope.drawGlassBase(
            cornerRadiusPx: Float,
            alpha: Float,
            touchPoint: Offset?,
            touchStrength: Float,
        ) {
            val r = cornerRadiusPx.coerceAtLeast(0f)
            val a = (alpha * quality.alpha).coerceIn(0f, 1f)
            val clip = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f, top = 0f,
                        right = size.width, bottom = size.height,
                        cornerRadius = CornerRadius(r, r),
                    ),
                )
            }

            clipPath(clip) {
                val bg = currentBackground
                if (bg != null) {
                    drawImage(
                        image = bg,
                        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        srcSize = androidx.compose.ui.unit.IntSize(
                            bg.width.coerceAtLeast(1),
                            bg.height.coerceAtLeast(1),
                        ),
                        dstOffset = backgroundDstOffset,
                        dstSize = androidx.compose.ui.unit.IntSize(
                            size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1),
                        ),
                        alpha = a,
                    )
                    // 色调叠加，模拟玻璃的色偏
                    drawRoundRect(
                        color = Color(0xFFB8D8FF).copy(alpha = a * 0.30f),
                        cornerRadius = CornerRadius(r, r),
                    )
                } else {
                    // 位图缺失：降级为渐变（与 Fallback 视觉一致）
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = a * 0.5f),
                                Color(0xFFA8CBF0).copy(alpha = a * 0.25f),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height),
                        ),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
            }

            if (touchPoint != null && touchStrength > 0f) {
                val radius = max(size.width, size.height) * 0.6f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.26f * touchStrength),
                            Color.Transparent,
                        ),
                        center = touchPoint,
                        radius = radius,
                    ),
                    radius = radius,
                    center = touchPoint,
                )
            }
        }

        override fun DrawScope.drawGlassRim(
            cornerRadiusPx: Float,
            touchPoint: Offset?,
            touchStrength: Float,
        ) {
            val r = cornerRadiusPx.coerceAtLeast(0f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f + 0.25f * touchStrength),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.4f),
            )
        }
    }
}

/**
 * 由 [LiquidGlass] 在绘制前注入的上下文：当前可用的背景位图与对齐偏移。
 *
 * 放在文件顶层而非 [LiquidGlassFallback] 内部，避免渲染器对象跨重组持有
 * 旧位图造成内存泄漏。
 */
internal var currentBackground: androidx.compose.ui.graphics.ImageBitmap? = null

/** 背景位图相对玻璃容器的绘制偏移（px）。 */
internal var backgroundDstOffset: androidx.compose.ui.unit.IntOffset =
    androidx.compose.ui.unit.IntOffset.Zero
