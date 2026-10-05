package com.example.liquidglass.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 液态玻璃性能基准测试。
 *
 * 覆盖任务要求的四个验证场景：
 *
 * | 场景 | 说明 |
 * |------|------|
 * | 静止 | 首页玻璃无交互，测量基础绘制成本 |
 * | 触摸拖动 | 按住卡片拖动，验证触摸跟随高光的增量成本 |
 * | TabBar 切换 | 反复切换 Tab，测量玻璃容器重组开销 |
 * | 列表滑动 | 卡片列表快速滑动，测量多玻璃容器并发成本 |
 *
 * 每档质量分级（`Full` / `Medium` / `Minimal` / `Fallback`）各跑一遍，
 * 用于对照 `PERFORMANCE.md` 中的降级表现。
 *
 * ## 运行方式
 *
 * ```bash
 * # 连接真机（推荐）或启动模拟器后：
 * ./gradlew :benchmark:connectedAndroidTest
 *
 * # 只跑某一档：
 * ./gradlew :benchmark:connectedAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.liquidglass.benchmark.GlassBenchmark#homeStatic_Full
 * ```
 *
 * 结果输出在 `benchmark/build/outputs/connected_android_test_additional_output/`
 * 与 `benchmark/build/reports/benchmark/`。
 */
@RunWith(AndroidJUnit4::class)
class GlassBenchmark {

    @get:JUnitRule
    val benchmarkRule: MacrobenchmarkRule = MacrobenchmarkRule()

    /* ---------------------------------------------------------------------- */
    /* 场景 1：首页静止                                                        */
    /* ---------------------------------------------------------------------- */

    @Test
    fun homeStatic_Full() = homeStatic("Full")

    @Test
    fun homeStatic_Medium() = homeStatic("Medium")

    @Test
    fun homeStatic_Minimal() = homeStatic("Minimal")

    @Test
    fun homeStatic_Fallback() = homeStatic("Fallback")

    private fun homeStatic(quality: String) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome() },
    ) {
        launch(quality)
        // 静止观察 5 秒，采样稳定态的绘制成本
        Thread.sleep(5_000)
    }

    /* ---------------------------------------------------------------------- */
    /* 场景 2：触摸拖动（触摸跟随高光）                                        */
    /* ---------------------------------------------------------------------- */

    @Test
    fun touchDrag_Full() = touchDrag("Full")

    @Test
    fun touchDrag_Medium() = touchDrag("Medium")

    @Test
    fun touchDrag_Minimal() = touchDrag("Minimal")

    @Test
    fun touchDrag_Fallback() = touchDrag("Fallback")

    private fun touchDrag(quality: String) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome() },
    ) {
        launch(quality)
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), TIMEOUT_MS)

        // 在屏幕中部水平拖动，覆盖玻璃边缘高光的跟踪路径
        val (cx, cy) = device.center()
        device.swipe(
            /* startX = */ (cx * 0.6f).toInt(),
            /* startY = */ cy,
            /* endX   = */ (cx * 1.4f).toInt(),
            /* endY   = */ cy,
            /* steps  = */ 60,
        )
    }

    /* ---------------------------------------------------------------------- */
    /* 场景 3：TabBar 切换                                                     */
    /* ---------------------------------------------------------------------- */

    @Test
    fun tabBar_Full() = tabBar("Full")

    @Test
    fun tabBar_Medium() = tabBar("Medium")

    @Test
    fun tabBar_Minimal() = tabBar("Minimal")

    @Test
    fun tabBar_Fallback() = tabBar("Fallback")

    private fun tabBar(quality: String) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome() },
    ) {
        launch(quality, page = "TabBar")
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), TIMEOUT_MS)

        // TabBar 位于屏幕底部，在三个 Tab 之间来回切换
        val (_, h) = device.size()
        val y = (h * 0.90f).toInt()
        repeat(3) { index ->
            val x = (device.size().first * (0.25f + index * 0.25f)).toInt()
            device.click(x, y)
            Thread.sleep(400)
        }
    }

    /* ---------------------------------------------------------------------- */
    /* 场景 4：卡片列表滑动                                                    */
    /* ---------------------------------------------------------------------- */

    @Test
    fun cardScroll_Full() = cardScroll("Full")

    @Test
    fun cardScroll_Medium() = cardScroll("Medium")

    @Test
    fun cardScroll_Minimal() = cardScroll("Minimal")

    @Test
    fun cardScroll_Fallback() = cardScroll("Fallback")

    private fun cardScroll(quality: String) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = { pressHome() },
    ) {
        launch(quality, page = "Cards")
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), TIMEOUT_MS)

        repeat(3) {
            device.swipe(
                /* startX = */ 500,
                /* startY = */ 1600,
                /* endX   = */ 500,
                /* endY   = */ 500,
                /* steps  = */ 40,
            )
        }
    }

    /* ---------------------------------------------------------------------- */

    private fun androidx.benchmark.macro.MacrobenchmarkScope.launch(
        quality: String,
        page: String = "Home",
    ) {
        startActivityAndWait(
            intent = android.content.Intent()
                .setAction(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setPackage(TARGET_PACKAGE)
                .putExtra("com.example.liquidglass.QUALITY", quality)
                .putExtra("com.example.liquidglass.PAGE", page)
                // 关闭背景动画，排除动画自身重绘对帧率统计的干扰
                .putExtra("com.example.liquidglass.ANIMATE", false),
        )
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.pressHome() {
        device.pressHome()
        device.waitForIdle()
    }

    private fun UiDevice.center(): Pair<Int, Int> {
        val (w, h) = size()
        return w / 2 to h / 2
    }

    private fun UiDevice.size(): Pair<Int, Int> = displayWidth to displayHeight

    private companion object {
        const val TARGET_PACKAGE = "com.example.liquidglass"

        /** 迭代次数，取官方推荐值以平衡耗时与置信度。 */
        const val ITERATIONS = 5

        const val TIMEOUT_MS = 5_000L
    }
}
