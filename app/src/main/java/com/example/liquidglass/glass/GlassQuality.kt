package com.example.liquidglass.glass

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable

/**
 * 玻璃渲染质量分级。
 *
 * 分级依据两条约束：
 * 1. **图形 API 能力** — AGSL 需要 API 33+，[android.graphics.RenderEffect] 需要 API 31+。
 * 2. **设备硬件能力** — 低内存设备（`isLowRamDevice`）即使 API 达标也必须降级，
 *    否则离屏缓冲区会导致 OOM。
 *
 * 各级别对应不同的渲染后端与参数（模糊半径、色散强度、采样次数）：
 *
 * | 级别 | 条件 | 渲染后端 | 折射 | 色散 | 模糊 |
 * |------|------|----------|------|------|------|
 * | [Full] | API 33+ 且非低内存 | AGSL RuntimeShader | ✅ 高度场 | ✅ 7 路 | ✅ 1/4 分辨率两趟 |
 * | [Medium] | API 31-32 或低内存 | RenderEffect.createBlurEffect | ❌ | ❌ | ✅ 硬件两趟 |
 * | [Minimal] | API 24-30 | CPU 降采样 + Canvas 合成 | ❌ | ❌ | ✅ CPU 1/3 降采样 |
 * | [Fallback] | API 21-23 | 纯渐变 + 透明度 | ❌ | ❌ | ❌ |
 *
 * 该枚举是纯逻辑决策，**不依赖任何 Android 图形 API**，
 * 因此可以在单元测试中直接验证（见 `GlassQualityTest`）。
 */
@Immutable
enum class GlassQuality(
    /** 模糊半径（px），在 1/4 分辨率上执行。 */
    val blurRadius: Float,
    /** 色散强度，0 表示关闭色散。 */
    val dispersion: Float,
    /** 色散采样路数，1 表示仅单次采样。 */
    val dispersionSamples: Int,
    /** 模糊 Pass 的降采样分母，1 表示全分辨率。 */
    val downsampleFactor: Int,
    /** 玻璃透明度（0f~1f），值越大越通透。 */
    val alpha: Float,
    /** 是否绘制边缘高光。 */
    val supportsEdgeHighlight: Boolean,
) {
    /**
     * 完整效果：API 33+ 使用 AGSL RuntimeShader，
     * 实现 SDF 高度场折射 + 7 路色散 + Fresnel 边缘光 + 触摸动态光照。
     */
    Full(
        blurRadius = 12f,
        dispersion = 0.006f,
        dispersionSamples = 7,
        downsampleFactor = 4,
        alpha = 0.72f,
        supportsEdgeHighlight = true,
    ),

    /**
     * 中等效果：API 31-32 使用 [android.graphics.RenderEffect.createBlurEffect]
     * 做硬件加速模糊，再叠加 Canvas 色调与边缘高光。此级别**不支持折射**，
     * 因为 RenderEffect 只能做形变/模糊/颜色滤镜，无法采样位移。
     */
    Medium(
        blurRadius = 25f,
        dispersion = 0f,
        dispersionSamples = 1,
        downsampleFactor = 2,
        alpha = 0.66f,
        supportsEdgeHighlight = true,
    ),

    /**
     * 极简效果：API 24-30 无 RenderEffect，使用 CPU 管线
     * （RenderScript/自研可分离模糊）做 1/3 降采样模糊，再由 Canvas 合成色调。
     * 折射与色散均不可用。
     */
    Minimal(
        blurRadius = 8f,
        dispersion = 0f,
        dispersionSamples = 1,
        downsampleFactor = 3,
        alpha = 0.58f,
        supportsEdgeHighlight = true,
    ),

    /**
     * 兜底：API 21-23 或极低端设备。**不做任何模糊**，
     * 仅使用半透明纯色/渐变 + 1px 边框，保证不崩溃、不 OOM。
     */
    Fallback(
        blurRadius = 0f,
        dispersion = 0f,
        dispersionSamples = 1,
        downsampleFactor = 1,
        alpha = 0.45f,
        supportsEdgeHighlight = true,
    );

    /** 是否支持真正的像素位移折射。 */
    val supportsRefraction: Boolean
        get() = this == Full

    /** 是否启用离屏位图（模糊需要位图作为输入）。 */
    val requiresOffscreenBuffer: Boolean
        get() = this != Fallback

    companion object {
        /**
         * 根据系统版本与设备能力决策质量分级。
         *
         * 决策优先级（从高到低）：
         * 1. `isLowRamDevice == true` → 至少降一级，避免 OOM
         * 2. API 33+ → [Full]
         * 3. API 31-32 → [Medium]
         * 4. API 24-30 → [Minimal]
         * 5. API 21-23 → [Fallback]
         *
         * @param sdkInt Android SDK 版本，默认为 [Build.VERSION.SDK_INT]
         * @param isLowRamDevice 是否为低内存设备（`ActivityManager.isLowRamDevice`）
         * @param hasHardwareAcceleration 窗口是否开启硬件加速
         * @return 决策出的 [GlassQuality]
         */
        fun resolve(
            sdkInt: Int = Build.VERSION.SDK_INT,
            isLowRamDevice: Boolean = false,
            hasHardwareAcceleration: Boolean = true,
        ): GlassQuality {
            // 低内存设备降一级：Full -> Medium，其余保持
            if (isLowRamDevice) {
                return when {
                    sdkInt >= Build.VERSION_CODES.TIRAMISU -> Medium
                    else -> baseQualityFor(sdkInt)
                }
            }
            // 未开启硬件加速时，AGSL / RenderEffect 均不可用
            if (!hasHardwareAcceleration) {
                return when {
                    sdkInt >= Build.VERSION_CODES.TIRAMISU -> Medium
                    else -> baseQualityFor(sdkInt)
                }
            }
            return baseQualityFor(sdkInt)
        }

        /**
         * 便捷入口：从 [Context] 直接解析质量分级。
         *
         * @param context 任意 Context（推荐 Application 避免 Activity 泄漏）
         */
        fun resolve(context: Context): GlassQuality {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return resolve(
                sdkInt = Build.VERSION.SDK_INT,
                isLowRamDevice = am?.isLowRamDevice ?: false,
            )
        }

        /** 纯版本映射，不含设备能力因素。 */
        private fun baseQualityFor(sdkInt: Int): GlassQuality = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> Full      // API 33+
            sdkInt >= Build.VERSION_CODES.S -> Medium            // API 31-32
            sdkInt >= Build.VERSION_CODES.N -> Minimal           // API 24-30
            else -> Fallback                                      // API 21-23
        }
    }
}
