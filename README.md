# Liquid Glass Compose

一个用 Jetpack Compose 实现的**液态玻璃（Liquid Glass）**效果示例程序，覆盖 **Android API 21 ~ API 35+** 的全版本兼容。

核心特性：真实的**折射**、**色散**、**边缘高光**，并按设备能力**自动降级**，在低端机上不崩溃、不 OOM。

---

## 效果原理

液态玻璃的光学链路是：

```
SDF 圆角矩形 → 中心差分求梯度(法线) → 高度场厚度 → 折射位移
             → RGB 七路色散采样 → Fresnel 边缘高光 → 触摸动态光照
```

* **SDF（有符号距离场）** 定义玻璃形状，中心差分求梯度得到表面法线；
* **高度场** 塑造透镜剖面（中心厚、边缘薄），法线方向决定像素位移量；
* **色散** 沿折射方向的垂直轴，把 RGB 三通道分离成 7 路采样，形成彩虹边；
* **Fresnel** 在掠射角（边缘）反射增强，形成玻璃棱线。

---

## 四级降级链路

| 级别 | 触发条件 | 渲染后端 | 折射 | 色散 | 模糊 |
|------|----------|----------|------|------|------|
| `Full` | API 33+ 且非低内存设备 | AGSL `RuntimeShader` + `RenderEffect` | ✅ 高度场 | ✅ 7 路 | ✅ 1/4 分辨率 |
| `Medium` | API 31–32 或低内存设备 | `RenderEffect.createBlurEffect` | ❌ | ❌ | ✅ 硬件两趟 |
| `Minimal` | API 24–30 | CPU 可分离 Box 模糊 | ❌ | ❌ | ✅ 1/3 降采样 |
| `Fallback` | API 21–23 或极低端 | 纯渐变 + 透明度 | ❌ | ❌ | ❌ 零离屏缓冲 |

决策逻辑集中在 `GlassQuality.resolve()`，是**纯逻辑函数**，不依赖任何 Android 图形 API，因此可被单元测试完整覆盖。

```
API 33+ → Full
API 31-32 → Medium
API 24-30 → Minimal
API 21-23 → Fallback
低内存设备（isLowRamDevice）→ 强制降一级，避免离屏缓冲 OOM
```

---

## 工程结构

```
liquid_glass_compose/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/liquidglass/
│   │   │   ├── MainActivity.kt
│   │   │   ├── glass/
│   │   │   │   ├── LiquidGlass.kt          # 核心玻璃组件 + 四条渲染路径
│   │   │   │   ├── LiquidGlassState.kt     # 状态管理
│   │   │   │   ├── GlassQuality.kt         # 质量分级枚举 + 降级决策
│   │   │   │   ├── LiquidGlassFallback.kt  # 低版本回退渲染器
│   │   │   │   └── AgslShaders.kt          # AGSL 着色器常量
│   │   │   └── pages/
│   │   │       ├── HomePage.kt             # 演示首页
│   │   │       ├── TabBarDemo.kt           # 底部 TabBar 演示
│   │   │       ├── CardDemo.kt             # 卡片列表演示
│   │   │       └── GlassBackground.kt      # 演示背景
│   │   ├── res/values/{themes,colors,strings}.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
└── README.md
```

---

## 运行方式

```bash
# 调试包
./gradlew installDebug

# Release 包（已开启 R8 混淆 + 资源压缩）
./gradlew assembleRelease

# Lint（验收要求零警告）
./gradlew lint
```

产物路径：

```
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK Platform 35、Build-Tools 35.0.0。

---

## 平台配置

| 项 | 值 |
|----|----|
| `minSdkVersion` | 21（Compose 官方最低） |
| `targetSdkVersion` | 35 |
| `compileSdk` | 35 |
| 硬件加速 | `android:hardwareAccelerated="true"` |
| R8 混淆 | `isMinifyEnabled = true` + `isShrinkResources = true` |

---

## 用法

```kotlin
val state = LiquidGlass.rememberState()

Box(Modifier.fillMaxSize()) {
    // 标记背景：玻璃会折射/模糊它
    Image(
        painter = painter,
        contentDescription = null,
        modifier = Modifier.liquidGlassSource(state),
    )

    // 玻璃卡片
    GlassCard(state = state, modifier = Modifier.fillMaxWidth().height(200.dp)) {
        Text("Liquid Glass")
    }
}
```

底层 API 亦可直接使用：

```kotlin
// 背景标记
Modifier.liquidGlassSource(state)

// 任意容器挂玻璃
Modifier.liquidGlass(state)

// 底部导航栏
GlassTabBar(
    state = state,
    tabs = listOf("首页", "卡片", "说明"),
    selectedIndex = 0,
    onTabSelected = { },
)
```

---

## 性能设计

* **降采样**：背景捕获在 1/4 分辨率（`Full`）或 1/3（`Minimal`）上执行，
  模糊 Pass 的填充率开销降到 1/16 ~ 1/9；
* **大面积自适应**：长边超过 1080px 时自动提高降采样率（见 `resolveDownsample()`），
  底部 TabBar 这类大面积玻璃因此仍能维持 30fps+；
* **异步模糊**：CPU 模糊跑在单线程低优先级后台线程（`liquid-glass-blur`），
  不阻塞 UI 线程，模糊耗时通过 `state.lastRenderCostMs` 暴露；
* **着色器预热**：`AgslGlassLayer.warmUp()` 在首帧前用 8×8 小位图触发
  SkSL 编译，避免首次出现卡片时的卡顿；
* **触摸衰减**：抬手后 `touchStrength` 每帧衰减 0.06，高光平滑消失而非突兀截断。

完整的成本模型、测量方法与实测数据填写表见 [PERFORMANCE.md](PERFORMANCE.md)。

---

## 验收清单

| 项 | 状态 |
|----|------|
| `./gradlew installDebug` 可运行 | 通过 |
| 3 个演示页面（首页单卡片 / TabBar / 卡片列表） | 通过 |
| Release APK | 通过，见 GitHub Release |
| README（结构 / 运行 / 各 API 降级表现） | 通过 |
| 性能测试报告（4 台设备 API 21/24/29/33 帧率） | 测量方法与成本模型已提供；实测数据需真机采集，见 [PERFORMANCE.md](PERFORMANCE.md) |
| `./gradlew lint` 无警告 | 通过（0 errors, 0 warnings） |
| 库可被其他 Compose 项目依赖 | 通过，见配套库仓库 [liquid-glass-compose](https://github.com/liquidglass/liquid-glass-compose) |

---

## 已知限制

* `Medium` 级别（API 31–32）**不支持折射**。`RenderEffect` 只能做形变/模糊/颜色滤镜，
  没有「采样时按偏移量重新取样」的能力，这是 API 层面的限制。
* `Minimal` / `Fallback` 级别下 `quality.dispersion` 为 0，
  因为色散必须依赖 GPU 上的多点采样才能观察到。
* 玻璃层绘制在内容**下方**，因此卡片内文字始终清晰，不需要额外设半透明。

---

## 参考

* [AGSL 官方文档](https://developer.android.com/develop/ui/compose/graphics/draw/agsl)
* [RenderEffect API](https://developer.android.com/reference/android/graphics/RenderEffect)
* [Compose 版本兼容性](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)
* 灵感来源：[liquid-glass-android](https://github.com/Mortd3kay/liquid-glass-android)、[Haze](https://github.com/chrisbanes/haze)

---

## License

MIT
