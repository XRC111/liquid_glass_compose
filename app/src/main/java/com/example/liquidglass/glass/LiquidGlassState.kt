package com.example.liquidglass.glass

import android.graphics.PointF
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃的状态持有者。
 *
 * 通过 `Modifier.liquidGlass(state)` 挂载到玻璃容器，
 * 通过 `Modifier.liquidGlassSource(state)` 标记需要被玻璃折射/模糊的背景内容。
 *
 * 典型用法：
 * ```kotlin
 * val state = rememberLiquidGlassState()
 * Box(Modifier.fillMaxSize()) {
 *     Image(
 *         painter = painter,
 *         contentDescription = null,
 *         modifier = Modifier.liquidGlassSource(state),
 *     )
 *     GlassCard(state = state) { Text("Liquid Glass") }
 * }
 * ```
 *
 * 该类标注 [androidx.compose.runtime.Stable]，所有变更通过 Compose Snapshot
 * 系统通知重组，读写开销最低。
 */
@Stable
class LiquidGlassState internal constructor(
    /** 当前生效的质量分级，由 [GlassQuality.resolve] 决策。 */
    quality: GlassQuality,
) {
    /** 当前质量分级。 */
    var quality: GlassQuality by mutableStateOf(quality)
        internal set

    /** 玻璃容器的当前尺寸（px），由 Modifier 测量阶段写入。 */
    var size: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** 玻璃容器相对窗口的原点（px），用于把背景裁剪对齐到容器。 */
    var positionInWindow: IntOffset by mutableStateOf(IntOffset.Zero)
        internal set

    /** 背景来源相对窗口的原点（px）。 */
    var sourcePosition: IntOffset by mutableStateOf(IntOffset.Zero)
        internal set

    /** 背景来源的尺寸（px）。 */
    var sourceSize: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** 屏幕密度，用于 px/dp 换算。 */
    var sourceDensity: Float by mutableStateOf(3f)
        internal set

    /** 当前触摸点（容器局部坐标，px）。无触摸时为 null。 */
    var touchPoint: Offset? by mutableStateOf(null)
        internal set

    /** 当前触摸强度（0f~1f），用于动态光照衰减。 */
    var touchStrength: Float by mutableStateOf(0f)
        internal set

    /** 捕获的原始背景位图（低分辨率），由 [liquidGlassSource] 写入。 */
    var backgroundBitmap: ImageBitmap? by mutableStateOf(null)
        internal set

    /** 背景捕获后的 CPU 模糊结果，供非 AGSL 路径使用。 */
    var blurredBackground: ImageBitmap? by mutableStateOf(null)
        internal set

    /** 背景捕获是否成功。 */
    var backgroundCaptured: Boolean by mutableStateOf(false)
        internal set

    /** 当前是否处于按下状态。 */
    var isPressed: Boolean by mutableStateOf(false)
        internal set

    /** 玻璃色调。 */
    var tint: Color by mutableStateOf(Color(0xFFB8D8FF))
        internal set

    /** 圆角半径（dp）。 */
    var cornerRadius: Dp by mutableStateOf(Dp(DEFAULT_CORNER_RADIUS_DP))
        internal set

    /** 玻璃厚度（dp），影响 AGSL 中的折射位移量。 */
    var thickness: Dp by mutableStateOf(Dp(20f))
        internal set

    /** 是否启用触摸跟随高光。 */
    var enableTouchFollow: Boolean by mutableStateOf(true)
        internal set

    /** 当前效果可用的模糊半径（px），已按降采样折算。 */
    var lastRenderCostMs: Float by mutableStateOf(0f)
        internal set

    /** 是否已预热着色器，避免首帧卡顿。 */
    var shaderWarmedUp: Boolean by mutableStateOf(false)
        internal set

    /** 圆角半径的 px 值，供 AGSL `edgeRadius` uniform 使用。 */
    val cornerRadiusPx: Float
        get() = cornerRadius.value * sourceDensity

    /** 厚度的 px 值，供 AGSL `thickness` uniform 使用。 */
    val thicknessPx: Float
        get() = thickness.value * sourceDensity

    /**
     * 记录一次触摸事件（容器局部坐标）。
     *
     * @param localPosition 触摸点在玻璃容器内的坐标（px）
     * @param pressure 触摸压力，0f~1f
     */
    fun onTouchLocal(localPosition: Offset, pressure: Float) {
        if (!enableTouchFollow) return
        touchPoint = localPosition
        touchStrength = (pressure * 2f).coerceIn(0f, 1f)
        isPressed = true
    }

    /**
     * 记录一次触摸事件（窗口绝对坐标）。
     *
     * @param absolutePosition 触摸点的窗口绝对坐标（px）
     * @param pressure 触摸压力，0f~1f
     */
    fun onTouch(absolutePosition: Offset, pressure: Float) {
        onTouchLocal(
            absolutePosition - Offset(
                positionInWindow.x.toFloat(),
                positionInWindow.y.toFloat(),
            ),
            pressure,
        )
    }

    /** 结束触摸，高光在随后数帧内衰减。 */
    fun onRelease() {
        isPressed = false
    }

    /** 每帧衰减触摸强度，实现高光平滑消失。 */
    fun decayTouch() {
        if (!isPressed && touchStrength > 0f) {
            touchStrength = (touchStrength - 0.06f).coerceAtLeast(0f)
        }
    }

    /** 尺寸变化时同步状态。 */
    internal fun onSizeChanged(newSize: IntSize) {
        if (size != newSize) size = newSize
    }

    /** 写入背景位图；传入 null 表示捕获失败，触发无折射回退。 */
    internal fun onBackgroundCaptured(bitmap: ImageBitmap?) {
        backgroundBitmap = bitmap
        backgroundCaptured = bitmap != null
        if (bitmap == null) blurredBackground = null
    }

    /** 归一化触摸点到 0f~1f（相对容器尺寸），无触摸时返回中心点。 */
    fun normalizedTouch(): PointF {
        val s = size
        val p = touchPoint
        return PointF(
            if (s.width == 0) 0.5f else (p?.x ?: s.width / 2f) / s.width,
            if (s.height == 0) 0.5f else (p?.y ?: s.height / 2f) / s.height,
        )
    }

    /** 当前效果可用的模糊半径（px）。 */
    fun effectiveBlurRadiusPx(density: Density): Float {
        if (quality == GlassQuality.Fallback) return 0f
        return max(with(density) { quality.blurRadius.dp.toPx() }, 1f)
    }

    /** 背景位图是否可用于折射（已捕获且级别支持）。 */
    fun canRefract(): Boolean =
        quality.supportsRefraction && backgroundCaptured && backgroundBitmap != null

    /** 当前应参与渲染的后景位图：优先用模糊结果。 */
    val renderBackground: ImageBitmap?
        get() = blurredBackground ?: backgroundBitmap

    companion object {
        /** 默认圆角半径（dp）。 */
        const val DEFAULT_CORNER_RADIUS_DP = 28f
    }
}

/**
 * 计算给定质量级别下的降采样分母。
 *
 * 大面积玻璃应提高降采样率以保证帧率 ≥ 30fps：
 * 长边超过 1080px 时额外降一级。
 */
internal fun resolveDownsample(quality: GlassQuality, widthPx: Int, heightPx: Int): Int {
    if (!quality.requiresOffscreenBuffer) return 1
    val longest = max(widthPx, heightPx)
    return if (longest > 1080) min(quality.downsampleFactor + 1, 6) else quality.downsampleFactor
}

/** 求 [Size] 的中心点。 */
internal fun centerOf(size: Size): Offset = Offset(size.width / 2f, size.height / 2f)

/** 把 [value] 归一化到 0f~1f。 */
internal fun normalize(value: Float, min: Float, max: Float): Float {
    if (abs(max - min) < 1e-6f) return 0f
    return ((value - min) / (max - min)).coerceIn(0f, 1f)
}
