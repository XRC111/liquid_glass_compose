package com.example.liquidglass.glass

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 液态玻璃效果入口：提供状态创建、背景捕获标记与玻璃修饰符。
 *
 * 四条渲染路径由 [GlassQuality] 在运行时选择：
 *
 * | 路径 | 条件 | 实现 |
 * |------|------|------|
 * | AGSL | API 33+ | [AgslGlassLayer]：RuntimeShader + RenderEffect，SDF 高度场折射 + 7 路色散 + Fresnel |
 * | RenderEffect | API 31-32 | [RenderEffectGlassLayer]：`createBlurEffect` 硬件模糊 + Canvas 色调/高光 |
 * | CPU | API 24-30 | [LiquidGlassFallback.CpuBackedRenderer]：降采样 + 可分离高斯 |
 * | Gradient | API 21-23 | [LiquidGlassFallback.GradientRenderer]：渐变 + 透明度 |
 *
 * 所有路径均支持触摸跟随（边缘高光随手指移动）。
 */
object LiquidGlass {

    private const val TAG = "LiquidGlass"

    /** 后台模糊线程池：单线程低优先级，避免与 UI 线程争抢 CPU。 */
    private val blurExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "liquid-glass-blur").apply { priority = Thread.MIN_PRIORITY }
    }

    /**
     * 创建液态玻璃状态。
     *
     * 质量分级在创建时依据系统版本与设备能力一次性决策。
     *
     * @param context 用于查询低内存设备标记
     * @param forceQuality 强制指定质量级别，`null` 表示自动决策
     */
    @Composable
    fun rememberState(
        context: Context = LocalContext.current,
        forceQuality: GlassQuality? = null,
    ): LiquidGlassState {
        val detected = remember(context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            GlassQuality.resolve(
                sdkInt = Build.VERSION.SDK_INT,
                isLowRamDevice = am?.isLowRamDevice ?: false,
                hasHardwareAcceleration = true,
            )
        }
        val quality = forceQuality ?: detected
        return remember(quality) { LiquidGlassState(quality) }
    }

    /**
     * 纯 CPU 可分离 Box 模糊（三趟近似高斯），在低分辨率位图上执行。
     *
     * **注意**：Android 13+ 的 [androidx.compose.ui.graphics.layer.GraphicsLayer.toImageBitmap]
     * 返回的是 `Config#HARDWARE` 位图，其像素数据位于 GPU 显存，
     * 调用 [Bitmap.getPixels] 会抛
     * `IllegalStateException: pixel access is not supported on Config#HARDWARE bitmaps`。
     * 因此这里先把源位图复制成软件位图（ARGB_8888）再做像素级运算。
     *
     * @return 模糊结果；若源位图不可读则原样返回，调用方需容忍未模糊的背景
     */
    internal fun blurCpu(src: Bitmap, radius: Float): Bitmap {
        if (radius <= 0f) return src
        // HARDWARE 位图（以及 RGB_565 等非 8888 格式）先归一化为可读的软件位图
        val readable = src.toReadableArgb8888() ?: return src
        val w = readable.width
        val h = readable.height
        if (w <= 2 || h <= 2) return readable
        val pixels = IntArray(w * h)
        readable.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(pixels.size)
        val r = radius.roundToInt().coerceIn(1, 12)
        repeat(3) {
            boxH(pixels, tmp, w, h, r)
            boxV(tmp, pixels, w, h, r)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 把任意位图转成可进行像素读写的 [Bitmap.Config.ARGB_8888] 软件位图。
     *
     * [androidx.compose.ui.graphics.layer.GraphicsLayer.toImageBitmap] 在
     * Android 13+ 上返回 `Config#HARDWARE` 位图，其像素驻留显存、不可读取；
     * 这里用一次 `copy(ARGB_8888, false)` 把它落到系统内存。
     *
     * @return 可读位图；转换失败返回 `null`，此时调用方应跳过 CPU 处理，
     *   而不是让异常冒泡导致宿主应用闪退
     */
    internal fun Bitmap.toReadableArgb8888(): Bitmap? {
        // HARDWARE 配置的位图像素驻留显存，不可通过 getPixels 读取。
        // 该配置由 API 26 引入，且实际只会在 API 29+ 上由
        // GraphicsLayer.toImageBitmap 产生；低版本不存在，故带版本判断，
        // 避免在 API 21-25 上触碰 Bitmap.Config.HARDWARE 常量。
        val isHardwareConfig =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE
        if (config == Bitmap.Config.ARGB_8888 && !isHardwareConfig) return this
        return runCatching {
            // copy 到 ARGB_8888 会隐式完成 HARDWARE -> 软件位图的转换
            copy(Bitmap.Config.ARGB_8888, false)
        }.getOrElse { t ->
            Log.w(LG_TAG, "bitmap is not pixel-readable, skipping CPU pass", t)
            null
        }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                a += (c ushr 24) and 0xFF; rr += (c ushr 16) and 0xFF
                g += (c ushr 8) and 0xFF; b += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (a / div shl 24) or (rr / div shl 16) or (g / div shl 8) or (b / div)
                val o = src[row + (x - r).coerceIn(0, w - 1)]
                val n = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += ((n ushr 24) and 0xFF) - ((o ushr 24) and 0xFF)
                rr += ((n ushr 16) and 0xFF) - ((o ushr 16) and 0xFF)
                g += ((n ushr 8) and 0xFF) - ((o ushr 8) and 0xFF)
                b += (n and 0xFF) - (o and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                a += (c ushr 24) and 0xFF; rr += (c ushr 16) and 0xFF
                g += (c ushr 8) and 0xFF; b += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (a / div shl 24) or (rr / div shl 16) or (g / div shl 8) or (b / div)
                val o = src[(y - r).coerceIn(0, h - 1) * w + x]
                val n = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += ((n ushr 24) and 0xFF) - ((o ushr 24) and 0xFF)
                rr += ((n ushr 16) and 0xFF) - ((o ushr 16) and 0xFF)
                g += ((n ushr 8) and 0xFF) - ((o ushr 8) and 0xFF)
                b += (n and 0xFF) - (o and 0xFF)
            }
        }
}

}


/** 背景捕获失败时使用的日志标签。 */
private const val LG_TAG = "LiquidGlass"

/**
 * 标记为玻璃背景来源的内容。
 *
 * 被标记内容会在 1/4 分辨率上被捕获为位图，供同一 [LiquidGlassState] 下的
 * 玻璃容器做模糊与折射采样。捕获在绘制阶段完成，模糊在后台线程执行，
 * 因此不阻塞 UI 线程。
 *
 * ```kotlin
 * Image(
 *     painter = painter,
 *     contentDescription = null,
 *     modifier = Modifier.liquidGlassSource(state),
 * )
 * ```
 */
@Composable
fun Modifier.liquidGlassSource(state: LiquidGlassState): Modifier =
    liquidGlassSource(state, enabled = true)

/**
 * [liquidGlassSource] 的开关版本。
 *
 * @param state 共享状态
 * @param enabled 是否启用背景捕获；关闭时玻璃退化为无折射渲染
 */
@Composable
fun Modifier.liquidGlassSource(
    state: LiquidGlassState,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layer = rememberGraphicsLayer()
    // 记录已提交的位图身份，避免同一帧重复触发模糊
    val submitted = remember { arrayOfNulls<ImageBitmap>(1) }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            state.sourcePosition = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            state.sourceSize = IntSize(
                coords.size.width.coerceAtLeast(1),
                coords.size.height.coerceAtLeast(1),
            )
            state.sourceDensity = density.density
        }
        .drawWithCache {
            val factor = resolveDownsample(state.quality, size.width.toInt(), size.height.toInt())
            val lw = (size.width / factor).coerceAtLeast(2f)
            val lh = (size.height / factor).coerceAtLeast(2f)
            val sx = lw / size.width
            val sy = lh / size.height

            onDrawWithContent {
                drawContent()

                // Fallback 级别无需背景，也不做离屏录制
                if (!state.quality.requiresOffscreenBuffer || lw < 2f || lh < 2f) {
                    if (state.backgroundBitmap != null) state.onBackgroundCaptured(null)
                    return@onDrawWithContent
                }

                // 以 1/factor 缩放录制，得到低分辨率背景
                val lwPx = lw.toInt()
                val lhPx = lh.toInt()
                layer.record(density, layoutDirection, IntSize(lwPx, lhPx)) {
                    scale(sx, sy, pivot = Offset.Zero) {
                        this@onDrawWithContent.drawContent()
                    }
                }

                // toImageBitmap 是挂起函数，提交到协程读取像素
                scope.launch {
                    val captured = runCatching { layer.toImageBitmap() }.getOrNull()
                    if (captured == null) {
                        Log.w(LG_TAG, "background capture failed; refraction disabled")
                        state.onBackgroundCaptured(null)
                        return@launch
                    }
                    if (submitted[0] === captured) return@launch
                    submitted[0] = captured
                    state.onBackgroundCaptured(captured)

                    // 异步 CPU 模糊，避免阻塞绘制。
                    // 整段包在 runCatching 内：捕获到的位图可能是 Config#HARDWARE
                    // （Android 13+ 的 GraphicsLayer 行为），像素不可读，
                    // 此时保留未模糊的背景继续渲染，绝不让异常冒泡崩溃。
                    val start = System.nanoTime()
                    val blurred = withContext(Dispatchers.Default) {
                        runCatching {
                            LiquidGlass.blurCpu(
                                captured.asAndroidBitmap(),
                                state.quality.blurRadius,
                            )
                        }.getOrElse { t ->
                            Log.w(LG_TAG, "CPU blur failed; using unblurred background", t)
                            captured.asAndroidBitmap()
                        }
                    }
                    state.blurredBackground = blurred.asImageBitmap()
                    state.lastRenderCostMs = (System.nanoTime() - start) / 1_000_000f
                }
            }
        }
}

/**
 * 把平台 [Bitmap] 转成 Compose [ImageBitmap]。
 *
 * 平台 [Bitmap] 本身就实现了 Compose 的 [ImageBitmap] 接口，直接返回即可。
 */
internal fun Bitmap.asComposeImageBitmap(): ImageBitmap = this.asImageBitmap()

/* -------------------------------------------------------------------------- */
/*                          AGSL 渲染层（API 33+）                              */
/* -------------------------------------------------------------------------- */

/**
 * 基于 AGSL RuntimeShader 的玻璃渲染层，仅在 API 33+ 实例化。
 *
 * 流程：
 * 1. 把捕获到的背景按相对偏移绘制进 [GraphicsLayer]（完成背景裁剪对齐）；
 * 2. 为该 Layer 设置 `RenderEffect.createRuntimeShaderEffect(shader, "image")`，
 *    硬件会把 Layer 内容作为名为 `image` 的 `uniform shader` 传入着色器；
 * 3. 每帧更新 uniform（分辨率 / 触摸点 / 色散 / 厚度）；
 * 4. `drawLayer` 输出结果。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AgslGlassLayer {

    private val shader = android.graphics.RuntimeShader(AgslShaders.GLASS_AGSL)

    /**
     * 预热着色器，避免首帧编译卡顿。
     *
     * 用 8×8 的小位图跑一次空绘制，触发 SkSL 编译与管线预热。
     */
    /**
     * 预热着色器，避免首帧编译卡顿。
     *
     * 用 8×8 的小位图跑一次空绘制，触发 SkSL 编译与管线预热。
     *
     * @return 预热是否成功。SkSL 编译失败（例如厂商驱动不支持某些内建函数）
     *   会在这里被捕获并返回 `false`，调用方据此回退到不依赖 AGSL 的
     *   [GlassQuality.Minimal] 渲染路径，而不是在后续 [updateEffect] 中崩溃。
     */
    fun warmUp(): Boolean = runCatching {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { this.shader = shader }
        canvas.drawRect(0f, 0f, 8f, 8f, paint)
        bmp.recycle()
        true
    }.getOrElse { t ->
        Log.w(LG_TAG, "AGSL shader warm-up failed; falling back to CPU path", t)
        false
    }

    /**
     * 更新 uniform 并返回绑定好的 RenderEffect。
     *
     * @param width 玻璃容器宽（px）
     * @param height 玻璃容器高（px）
     * @param state 玻璃状态
     */
    fun updateEffect(width: Int, height: Int, state: LiquidGlassState): android.graphics.RenderEffect? {
        val n = state.normalizedTouch()
        return runCatching {
            shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
            shader.setFloatUniform("mouse", n.x * width, n.y * height)
            shader.setFloatUniform("touchStrength", state.touchStrength)
            shader.setFloatUniform("refractiveIndex", 1.5f)
            shader.setFloatUniform("dispersion", state.quality.dispersion)
            shader.setFloatUniform("edgeRadius", state.cornerRadiusPx)
            shader.setFloatUniform("thickness", state.thicknessPx)
            shader.setColorUniform("tint", state.tint.toArgbInt())
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "image")
        }.getOrElse { t ->
            // 例如某厂商驱动不支持某个内建函数：不崩溃，退到无折射路径
            Log.w(LG_TAG, "AGSL uniform update failed; skipping refraction this frame", t)
            null
        }
    }
}

/**
 * [androidx.compose.ui.graphics.toArgb] 的 int 版本，供 AGSL `half4` uniform 使用。
 */
private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt().coerceIn(0, 255),
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255),
)

/* -------------------------------------------------------------------------- */
/*                       RenderEffect 渲染层（API 31-32）                       */
/* -------------------------------------------------------------------------- */

/**
 * 基于 [android.graphics.RenderEffect.createBlurEffect] 的玻璃渲染层。
 *
 * 该级别**不支持折射**（RenderEffect 无法做像素位移采样），
 * 仅做硬件加速模糊 + Canvas 色调与边缘高光叠加。
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class RenderEffectGlassLayer {
    /** 构造指定半径的模糊 RenderEffect。 */
    fun blurEffect(radiusPx: Float): android.graphics.RenderEffect =
        android.graphics.RenderEffect.createBlurEffect(
            radiusPx,
            radiusPx,
            android.graphics.Shader.TileMode.MIRROR,
        )
}

/* -------------------------------------------------------------------------- */
/*                              公共 Modifier                                  */
/* -------------------------------------------------------------------------- */

/**
 * 给容器挂上液态玻璃效果。
 *
 * 玻璃层绘制在内容**下方**（`drawWithCache` 的 `onDrawBehind`），
 * 因此内容文字始终清晰可读。
 *
 * @param state 共享的 [LiquidGlassState]
 * @param shape 玻璃形状；`null` 时使用圆角矩形，半径取 [LiquidGlassState.cornerRadius]
 */
@Composable
fun Modifier.liquidGlass(
    state: LiquidGlassState,
    shape: Shape? = null,
): Modifier = liquidGlassInternal(state, shape)

@Composable
private fun Modifier.liquidGlassInternal(
    state: LiquidGlassState,
    shape: Shape?,
): Modifier {
    val density = LocalDensity.current
    val glassLayer = rememberGraphicsLayer()
    val agslLayer = remember { arrayOfNulls<AgslGlassLayer>(1) }
    val effectLayer = remember { arrayOfNulls<RenderEffectGlassLayer>(1) }
    val fallbackRenderer = remember { arrayOfNulls<LiquidGlassFallback.Renderer>(1) }

    if (state.quality == GlassQuality.Full && Build.VERSION.SDK_INT >= 33) {
        if (agslLayer[0] == null) {
            val layer = AgslGlassLayer()
            // warmUp 返回真实结果：某些厂商驱动无法编译 AGSL，
            // 此时不能把 shaderWarmedUp 误标为 true，否则后续每帧
            // updateEffect 都会抛异常导致闪退。这里直接降级到
            // Minimal（CPU 模糊）路径，保证内容仍然可见。
            val ok = layer.warmUp()
            state.shaderWarmedUp = ok
            if (ok) {
                agslLayer[0] = layer
            } else {
                state.quality = GlassQuality.Minimal
                fallbackRenderer[0] = LiquidGlassFallback.create(GlassQuality.Minimal)
            }
        }
    } else if (state.quality == GlassQuality.Medium && Build.VERSION.SDK_INT >= 31) {
        if (effectLayer[0] == null) {
            effectLayer[0] = RenderEffectGlassLayer()
        }
    } else {
        fallbackRenderer[0] = LiquidGlassFallback.create(state.quality)
    }

    return this
        .onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            state.positionInWindow = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
            state.onSizeChanged(
                IntSize(
                    coords.size.width.coerceAtLeast(1),
                    coords.size.height.coerceAtLeast(1),
                ),
            )
        }
        .pointerInput(state) {
            if (!state.enableTouchFollow) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                    .also { state.onTouchLocal(it.position, 1f) }
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.any { it.pressed }
                    event.changes.forEach { state.onTouchLocal(it.position, it.pressure) }
                    if (!pressed) break
                }
                state.onRelease()
            }
        }
        .drawWithCache {
            val radiusPx = with(density) { state.cornerRadius.toPx() }
            // 玻璃形状：统一用圆角矩形路径
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f, top = 0f,
                        right = size.width, bottom = size.height,
                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                    ),
                )
            }

            onDrawBehind {
                val q = state.quality
                val w = size.width
                val h = size.height
                val touch = state.touchPoint
                val ts = state.touchStrength
                val bg = state.blurredBackground ?: state.backgroundBitmap
                val iw = w.toInt().coerceAtLeast(1)
                val ih = h.toInt().coerceAtLeast(1)

                // 背景相对玻璃容器的偏移，把 source 坐标系裁剪到容器内
                val offX = state.positionInWindow.x - state.sourcePosition.x
                val offY = state.positionInWindow.y - state.sourcePosition.y

                // 图层实例由组合阶段创建，但在极端时序下（首帧绘制早于组合赋值、
                // 质量分级被外部改写、驱动不支持 AGSL 等）可能仍为 null，
                // 或 updateEffect 返回 null（uniform 设置失败）。
                // 此处做二次校验并回退到 Fallback 渲染，避免绘制线程抛异常闪退。
                val agsl = agslLayer[0]
                val effect = effectLayer[0]
                val agslEffect = if (
                    q == GlassQuality.Full && bg != null &&
                    Build.VERSION.SDK_INT >= 33 && agsl != null
                ) {
                    agsl.updateEffect(iw, ih, state)
                } else {
                    null
                }
                val renderEffect = if (
                    agslEffect == null &&
                    q == GlassQuality.Medium && bg != null &&
                    Build.VERSION.SDK_INT >= 31 && effect != null
                ) {
                    runCatching {
                        effect.blurEffect(with(density) { q.blurRadius.dp.toPx() })
                    }.getOrNull()
                } else {
                    null
                }

                when {
                    // ---- API 33+：AGSL 完整液态玻璃 ----
                    agslEffect != null && bg != null -> {
                        val src = bg
                        glassLayer.renderEffect = agslEffect.asComposeRenderEffect()
                        glassLayer.record(density, layoutDirection, IntSize(iw, ih)) {
                            clipPath(path) {
                                drawImage(
                                    image = src,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(src.width.coerceAtLeast(1), src.height.coerceAtLeast(1)),
                                    dstOffset = IntOffset(offX, offY),
                                    dstSize = IntSize(iw, ih),
                                )
                            }
                        }
                        clipPath(path) { drawLayer(glassLayer) }
                    }

                    // ---- API 31-32：RenderEffect 硬件模糊 ----
                    // renderEffect 仅在 bg != null 时被赋值，故此处 bg 必然非空
                    renderEffect != null && bg != null -> {
                        val src = bg
                        glassLayer.renderEffect = renderEffect.asComposeRenderEffect()
                        glassLayer.record(density, layoutDirection, IntSize(iw, ih)) {
                            clipPath(path) {
                                drawImage(
                                    image = src,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(src.width.coerceAtLeast(1), src.height.coerceAtLeast(1)),
                                    dstOffset = IntOffset(offX, offY),
                                    dstSize = IntSize(iw, ih),
                                )
                            }
                        }
                        clipPath(path) { drawLayer(glassLayer) }
                        clipPath(path) { drawRect(state.tint.copy(alpha = 0.16f * q.alpha)) }
                        with(fallbackRenderer[0] ?: LiquidGlassFallback.GradientRenderer(q)) {
                            drawGlassRim(radiusPx, touch, ts)
                        }
                    }

                    // ---- API 24-30，或 Full / Medium 档硬件路径不可用时的回退 ----
                    q == GlassQuality.Minimal || q == GlassQuality.Full || q == GlassQuality.Medium -> {
                        currentBackground = bg
                        backgroundDstOffset = IntOffset(offX, offY)
                        // 回退渲染器若尚未创建（例如 AGSL 预热失败刚切到本分支），
                        // 这里按当前档位临时构造一个，画完即弃，不缓存
                        val fb = fallbackRenderer[0]
                            ?: if (bg == null) {
                                LiquidGlassFallback.GradientRenderer(GlassQuality.Fallback)
                            } else {
                                LiquidGlassFallback.create(q)
                            }
                        with(fb) {
                            drawGlassBase(radiusPx, q.alpha, touch, ts)
                            drawGlassRim(radiusPx, touch, ts)
                        }
                    }

                    // ---- API 21-23 / 无背景：纯渐变 ----
                    else -> {
                        with(fallbackRenderer[0] ?: LiquidGlassFallback.GradientRenderer(GlassQuality.Fallback)) {
                            drawGlassBase(radiusPx, q.alpha, touch, ts)
                            drawGlassRim(radiusPx, touch, ts)
                        }
                    }
                }

                state.decayTouch()
            }
        }
}

/* -------------------------------------------------------------------------- */
/*                                 组件                                        */
/* -------------------------------------------------------------------------- */

/**
 * 通用的液态玻璃卡片。
 *
 * 玻璃层绘制在内容下方，可直接把文字 / 图标放进 [content]，无需处理透明度。
 *
 * @param state 共享状态
 * @param modifier 布局修饰符
 * @param onClick 点击回调；为 `null` 时不可点击
 * @param content 卡片内容
 */
@Composable
fun GlassCard(
    state: LiquidGlassState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
            } else Modifier,
        )
        .liquidGlass(state)
    Box(base) { content() }
}

/**
 * 底部液态玻璃 TabBar。
 *
 * 宽度撑满、高度固定，内部等分 Tab。玻璃层在 API 33+ 上会因宽度较大
 * 自动提高降采样率（见 [resolveDownsample]），保证滚动时仍维持 30fps。
 *
 * @param state 共享状态
 * @param tabs Tab 标题列表
 * @param selectedIndex 当前选中下标
 * @param onTabSelected 选中回调
 * @param modifier 布局修饰符
 */
@Composable
fun GlassTabBar(
    state: LiquidGlassState,
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(state = state, modifier = modifier.fillMaxWidth().height(78.dp)) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onTabSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(width = 46.dp, height = 32.dp)
                                .then(if (selected) Modifier.liquidGlassPill(state) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = title.take(1),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (selected) Color(0xFF0B3D91) else Color(0xFF4A5A75),
                            )
                        }
                        Spacer(Modifier.size(3.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Color(0xFF12203A) else Color(0xFF6A7A95),
                        )
                    }
                }
            }
        }
    }
}

/** 选中态胶囊：内部再叠一层薄玻璃，制造层次感。 */
private fun Modifier.liquidGlassPill(state: LiquidGlassState): Modifier =
    this.background(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.60f), Color(0xFF9EC8FF).copy(alpha = 0.38f)),
        ),
        shape = RoundedCornerShape(percent = 50),
    )
