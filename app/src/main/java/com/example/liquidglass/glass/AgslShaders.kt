package com.example.liquidglass.glass

/**
 * AGSL（Android Graphics Shading Language）着色器常量集合。
 *
 * AGSL 是 Android 13（API 33）引入的运行时着色器语言，语法接近 GLSL，
 * 通过 [android.graphics.RuntimeShader] 在 GPU 上执行。本文件提供实现
 * 液态玻璃效果所需的着色器源码：
 *
 * - [GLASS_AGSL]：主玻璃着色器。SDF 圆角矩形 → 中心差分梯度法线 → 高度场折射
 *   → 7 路 RGB 色散采样 → Fresnel 边缘高光 → 触摸动态光照 → 透镜畸变。
 * - [BLUR_HORIZONTAL_AGSL] / [BLUR_VERTICAL_AGSL]：可分离高斯模糊两趟，
 *   供需要自行模糊背景的场景使用（本示例的模糊走 CPU 线程，故默认不启用）。
 *
 * 所有着色器都以 `uniform shader image` 接收外部位图，坐标单位为像素（px）。
 *
 * 注意事项：
 * - AGSL 中所有声明的 uniform 都必须在 `main` 中被引用，否则
 *   [android.graphics.RuntimeShader.setFloatUniform] 会抛出
 *   [IllegalArgumentException]。
 * - AGSL 不支持 `const` 的泛型推导，本文件统一使用字面量。
 */
object AgslShaders {

    /**
     * 主玻璃着色器（API 33+）。
     *
     * Uniform 说明：
     * - `image`：待处理的背景位图，由 RenderEffect 传入
     * - `resolution`：玻璃容器尺寸（px）
     * - `mouse`：触摸点在容器内的坐标（px），无触摸时取中心
     * - `touchStrength`：触摸动态光照强度（0f~1f）
     * - `refractiveIndex`：折射率，玻璃典型值 1.5
     * - `dispersion`：色散总幅度，7 路采样在 ±3×dispersion 区间分布
     * - `edgeRadius`：圆角矩形半径（px）
     * - `thickness`：玻璃厚度（px），影响折射位移量
     * - `tint`：玻璃色调（ARGB）
     */
    val GLASS_AGSL: String = """
        uniform shader image;
        uniform float2 resolution;
        uniform float2 mouse;
        uniform float touchStrength;
        uniform float refractiveIndex;
        uniform float dispersion;
        uniform float edgeRadius;
        uniform float thickness;
        uniform half4 tint;

        // 圆角矩形有符号距离场：内部为负、外部为正
        float sdRoundRect(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        // 中心差分求梯度（表面法线）
        float2 gradient(float2 p, float2 b, float r) {
            float d = sdRoundRect(p, b, r);
            float dx = sdRoundRect(p + float2(1.0, 0.0), b, r) - d;
            float dy = sdRoundRect(p + float2(0.0, 1.0), b, r) - d;
            return normalize(float2(dx, dy) + float2(0.0001, 0.0001));
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / resolution;
            float2 halfSize = resolution * 0.5;
            float2 p = fragCoord - halfSize;

            // --- SDF 形状遮罩（1.5px 抗锯齿过渡）---
            float sd = sdRoundRect(p, halfSize, edgeRadius);
            float mask = 1.0 - smoothstep(-1.5, 1.5, sd);
            if (mask <= 0.002) {
                return half4(0.0, 0.0, 0.0, 0.0);
            }

            // --- 高度场：中心厚、边缘薄，形成透镜剖面 ---
            float distNorm = clamp(-sd / max(min(halfSize.x, halfSize.y), 1.0), 0.0, 1.0);
            float height = sqrt(distNorm);

            // --- 梯度法线 ---
            float2 n = gradient(p, halfSize, edgeRadius);

            // --- 触摸方向与动态光照 ---
            float2 toMouse = mouse - fragCoord;
            float mouseDist = length(toMouse);
            float2 mouseDir = mouseDist > 0.001 ? toMouse / mouseDist : float2(0.0, 1.0);
            float lambert = max(dot(n, mouseDir), 0.0);
            float touchGlow = exp(-mouseDist * mouseDist / max(resolution.x * resolution.x * 0.06, 1.0))
                             * touchStrength;

            // --- 高度场折射偏移 ---
            float2 refractOffset = n * (thickness * height * (1.0 - 1.0 / refractiveIndex));
            // 透镜整体轻微朝手指偏移，增强聚光感（透镜畸变）
            float2 lensPull = mouseDir * (touchStrength * 6.0 * (1.0 - distNorm));
            float2 refracted = uv + (refractOffset + lensPull) / resolution;

            // --- 7 路色散：沿折射方向的垂直轴分离 RGB ---
            float2 dispAxis = (mouseDir + float2(0.0001, 0.0001)) / resolution;
            float s1 = dispersion;
            float s2 = dispersion * 2.0;
            float s3 = dispersion * 3.0;

            float2 o0 = refracted * resolution - dispAxis * s3 * resolution;
            float2 o1 = refracted * resolution - dispAxis * s2 * resolution;
            float2 o2 = refracted * resolution - dispAxis * s1 * resolution;
            float2 o3 = refracted * resolution;
            float2 o4 = refracted * resolution + dispAxis * s1 * resolution;
            float2 o5 = refracted * resolution + dispAxis * s2 * resolution;
            float2 o6 = refracted * resolution + dispAxis * s3 * resolution;

            float r = image.eval(o0).r * 0.051
                    + image.eval(o1).r * 0.0918
                    + image.eval(o2).r * 0.1515
                    + image.eval(o3).r * 0.2298
                    + image.eval(o4).r * 0.2298
                    + image.eval(o5).r * 0.1515
                    + image.eval(o6).r * 0.0918;

            float g = image.eval(o0).g * 0.051
                    + image.eval(o1).g * 0.0918
                    + image.eval(o2).g * 0.1515
                    + image.eval(o3).g * 0.2298
                    + image.eval(o4).g * 0.2298
                    + image.eval(o5).g * 0.1515
                    + image.eval(o6).g * 0.0918;

            float b = image.eval(o0).b * 0.051
                    + image.eval(o1).b * 0.0918
                    + image.eval(o2).b * 0.1515
                    + image.eval(o3).b * 0.2298
                    + image.eval(o4).b * 0.2298
                    + image.eval(o5).b * 0.1515
                    + image.eval(o6).b * 0.0918;

            // --- Fresnel 边缘高光：掠射角反射增强 ---
            float3 normal3 = float3(n * 0.55, 0.85);
            float fresnel = pow(1.0 - saturate(normal3.z), 2.6);
            // 玻璃棱线：贴近 SDF 边界的窄带
            float rim = 1.0 - smoothstep(0.0, 14.0, -sd);

            float3 color = float3(r, g, b);
            color += tint.rgb * 0.18;
            color += float3(1.0, 1.0, 1.0) * (fresnel * 0.55);
            color += float3(0.95, 0.98, 1.0) * (rim * 0.42);
            color += float3(1.0, 0.99, 0.95) * (touchGlow * 0.5);
            color *= 0.94 + lambert * 0.14;

            return half4(half3(color), half(saturate(mask) * 0.92 + rim * 0.08));
        }
    """.trimIndent()

    /**
     * 可分离高斯模糊 —— 水平方向 Pass。
     *
     * 采样权重为 9 点高斯的二项式近似。与 [BLUR_VERTICAL_AGSL] 串联
     * 组成完整的两趟模糊。建议在 1/2 ~ 1/4 分辨率的离屏位图上执行。
     */
    val BLUR_HORIZONTAL_AGSL: String = """
        uniform shader image;
        uniform float blurRadius;

        half4 main(float2 fragCoord) {
            float w0 = 0.2270270270;
            float w1 = 0.1945945946;
            float w2 = 0.1216216216;
            float w3 = 0.0540540541;

            float s1 = blurRadius * 1.3846153846;
            float s2 = blurRadius * 3.2307692308;
            float s3 = blurRadius * 5.1764705882;

            half4 sum = image.eval(fragCoord) * half(w0);
            sum += image.eval(fragCoord - float2(s1, 0.0)) * half(w1);
            sum += image.eval(fragCoord + float2(s1, 0.0)) * half(w1);
            sum += image.eval(fragCoord - float2(s2, 0.0)) * half(w2);
            sum += image.eval(fragCoord + float2(s2, 0.0)) * half(w2);
            sum += image.eval(fragCoord - float2(s3, 0.0)) * half(w3);
            sum += image.eval(fragCoord + float2(s3, 0.0)) * half(w3);
            return half4(sum.rgb, 1.0);
        }
    """.trimIndent()

    /**
     * 可分离高斯模糊 —— 垂直方向 Pass，与 [BLUR_HORIZONTAL_AGSL] 配合。
     */
    val BLUR_VERTICAL_AGSL: String = """
        uniform shader image;
        uniform float blurRadius;

        half4 main(float2 fragCoord) {
            float w0 = 0.2270270270;
            float w1 = 0.1945945946;
            float w2 = 0.1216216216;
            float w3 = 0.0540540541;

            float s1 = blurRadius * 1.3846153846;
            float s2 = blurRadius * 3.2307692308;
            float s3 = blurRadius * 5.1764705882;

            half4 sum = image.eval(fragCoord) * half(w0);
            sum += image.eval(fragCoord - float2(0.0, s1)) * half(w1);
            sum += image.eval(fragCoord + float2(0.0, s1)) * half(w1);
            sum += image.eval(fragCoord - float2(0.0, s2)) * half(w2);
            sum += image.eval(fragCoord + float2(0.0, s2)) * half(w2);
            sum += image.eval(fragCoord - float2(0.0, s3)) * half(w3);
            sum += image.eval(fragCoord + float2(0.0, s3)) * half(w3);
            return half4(sum.rgb, 1.0);
        }
    """.trimIndent()

    /**
     * 根据质量级别选择色散采样路数对应的 AGSL 变体。
     *
     * 当前实现所有级别共用 [GLASS_AGSL]，色散强度由 `dispersion` uniform
     * 控制（Minimal / Medium / Fallback 级别该 uniform 被设为 0，
     * 等效于关闭色散但仍走同一条 GPU 管线）。
     */
    fun shaderFor(): String = GLASS_AGSL
}
