# 性能测试报告

> **重要说明**：本报告中的帧率数据**不是实测值**。构建环境为 CI 沙箱，**无任何物理设备与模拟器**，无法运行 `adb shell dumpsys gfxinfo` 或 Macrobenchmark。因此本文提供的是：**（1）已内置的自动化基准测试**、**（2）基于实现成本模型的预期区间**、**（3）降级链路的设计依据**。验收方按第 1 节执行即可自动产出真实数据填入第 3 节表格。
>
> 所有非实测内容均已显式标注，未伪装为实测结果。

---

## 1. 测量方法（已自动化）

工程内置 `:benchmark` 模块（`com.android.test` + `androidx.benchmark:benchmark-macro-junit4`），
**四个场景 × 四档质量分级 = 16 个基准测试**已全部编写完成，连接真机后一条命令即可产出数据。

### 1.1 运行

```bash
# 连接真机（推荐；模拟器数据无参考价值）
adb devices

# 跑全部 16 个基准
./gradlew :benchmark:connectedAndroidTest

# 只跑某一档（例：Full 分级的首页静止）
./gradlew :benchmark:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.example.liquidglass.benchmark.GlassBenchmark#homeStatic_Full
```

结果输出位置：

| 路径 | 内容 |
|------|------|
| `benchmark/build/outputs/connected_android_test_additional_output/` | JSON / Perfetto trace |
| `benchmark/build/reports/benchmark/` | HTML 报告 |

采集的指标为 `FrameTimingMetric`，输出 P50 / P90 / P95 / P99 与帧数，
`CompilationMode.Full()` 保证使用 AOT 编译后的产物，`iterations = 5` 取中位数。

### 1.2 基准如何驱动四档质量

示例程序支持通过 Intent extra 定向启动，因此基准测试可在同一次运行内对比四档表现：

| Extra | 取值 | 作用 |
|-------|------|------|
| `com.example.liquidglass.QUALITY` | `Full` / `Medium` / `Minimal` / `Fallback` | 强制质量分级，绕过自动决策 |
| `com.example.liquidglass.PAGE` | `Home` / `TabBar` / `Cards` | 直接进入指定演示页 |
| `com.example.liquidglass.ANIMATE` | `true` / `false` | 是否启用背景流动动画；基准测试传 `false` 以排除动画噪声 |

手工验证同一个入口：

```bash
adb shell am start -n com.example.liquidglass/.MainActivity \
  --es com.example.liquidglass.QUALITY Full \
  --es com.example.liquidglass.PAGE Cards \
  --ez com.example.liquidglass.ANIMATE false
```

### 1.3 四个场景

| 场景 | 方法 | 说明 |
|------|------|------|
| 静止 | `homeStatic_*` | 首页玻璃无交互，测基础绘制成本 |
| 触摸拖动 | `touchDrag_*` | 屏幕中部水平拖动，测触摸跟随高光的增量成本 |
| TabBar 切换 | `tabBar_*` | 底部 Tab 连续切换，测玻璃容器重组开销 |
| 列表滑动 | `cardScroll_*` | 卡片列表连续滑动，测多玻璃容器并发成本 |

### 1.4 手工兜底方式

若不便跑 Macrobenchmark，也可用 `gfxinfo` 手工采集：

```bash
./gradlew :app:installRelease
adb shell dumpsys gfxinfo com.example.liquidglass reset
# 手工操作四类场景各 10 秒
adb shell dumpsys gfxinfo com.example.liquidglass | grep -A 10 "Janky frames"
```

关注 **Janky frames (%)**（目标 < 5%）、**P95**（目标 < 33ms，即 ≥30fps）。

### 1.5 应用内自测指标

库暴露 `LiquidGlassState.lastRenderCostMs`，记录最近一次后台模糊耗时。
可在三页的 `QualityBanner` 上直接观察，或在基准测试中读取：

```kotlin
Text("后台模糊耗时：${state.lastRenderCostMs} ms")
```

这是**后台模糊单次耗时**，不代表整帧绘制耗时，但可用于判断 CPU 路径是否成为瓶颈。

---

## 2. 成本模型与预期区间

各路径的单帧成本来源：

| 路径 | 触发 | 主要成本 | 预期帧率区间 |
|------|------|---------|-------------|
| `Full` | API 33+ | AGSL 逐像素 SDF + 7 次纹理采样 + Fresnel；模糊在 1/4 分辨率 | 35-60 fps（中高端） |
| `Medium` | API 31-32 | `RenderEffect` 硬件模糊（GPU 侧），无逐像素折射 | 45-60 fps |
| `Minimal` | API 24-30 | CPU 三趟可分离 Box 模糊 @ 1/3~1/4 分辨率 | 30-45 fps |
| `Fallback` | API 21-23 | 无模糊，仅渐变矩形 | 55-60 fps |

### 2.1 为什么模糊风险可控

模糊 Pass 在**1/4 分辨率**执行，因此计算量是**全分辨率的 1/16**。

以 1080×2400 屏幕为例：

| 项 | 全分辨率 | 1/4 分辨率 |
|----|---------|-----------|
| 像素数 | 2,592,000 | 162,000 |
| 相对计算量 | 1× | 0.0625× |

`resolveDownsample()` 对长边 > 1080px 的屏幕再降一级（上限 6）：

```kotlin
if (longest > 1080) min(downsampleFactor + 1, 6) else downsampleFactor
```

### 2.2 CPU 路径成本

`CpuRenderer` 用**滑动窗口**实现 Box 模糊，每像素 O(1)（与半径无关），三趟（H+V+H 或 H+V 组合）近似高斯。

单次模糊的理论耗时（1/4 分辨率，162k 像素）：

| 设备档位 | 单趟 | 三趟合计 |
|---------|------|---------|
| 中端（A76 级） | ~0.5 ms | ~1.5 ms |
| 低端（A53 级） | ~1.5 ms | ~4.5 ms |

均低于 33ms 帧预算，且运行在后台单线程（`Thread.MIN_PRIORITY`），不阻塞 UI 线程。

### 2.3 内存

| 项 | 尺寸 | 说明 |
|----|------|------|
| 背景原图（1/4） | 1080×2400 → 270×600 | 约 0.65 MB（ARGB_8888） |
| 模糊结果 | 同上 | 约 0.65 MB |
| AGSL 中间缓冲 | 玻璃容器尺寸 | 由 GraphicsLayer 管理 |

全部缓冲合计 **< 2 MB**，对 `isLowRamDevice` 设备是安全的。

---

## 3. 实测数据表（待现场填写）

> 以下单元格留空，供验收方在真机执行第 1 节步骤后填入。

### 3.1 场景 A：首页静止

| 设备 | SoC | API | 质量级别 | Janky % | P50 (ms) | P95 (ms) | FPS |
|------|-----|:---:|:--------:|--------:|---------:|---------:|----:|
| — | — | 21 | Fallback | | | | |
| — | — | 24 | Minimal | | | | |
| — | — | 29 | Minimal | | | | |
| — | — | 33 | Full | | | | |

### 3.2 场景 B：触摸拖动（触摸跟随高光）

| 设备 | API | 质量级别 | Janky % | P50 (ms) | P95 (ms) | FPS |
|------|:---:|:--------:|--------:|---------:|---------:|----:|
| — | 21 | Fallback | | | | |
| — | 24 | Minimal | | | | |
| — | 29 | Minimal | | | | |
| — | 33 | Full | | | | |

### 3.3 场景 C：TabBar 切换

| 设备 | API | 质量级别 | Janky % | P95 (ms) | FPS |
|------|:---:|:--------:|--------:|---------:|----:|
| — | 21 | Fallback | | | |
| — | 24 | Minimal | | | |
| — | 29 | Minimal | | | |
| — | 33 | Full | | | |

### 3.4 场景 D：卡片列表滑动

| 设备 | API | 质量级别 | Janky % | P95 (ms) | FPS |
|------|:---:|:--------:|--------:|---------:|----:|
| — | 21 | Fallback | | | |
| — | 24 | Minimal | | | |
| — | 29 | Minimal | | | |
| — | 33 | Full | | | |

### 3.5 应用内指标

| 设备 | API | `lastRenderCostMs`（后台模糊，ms） |
|------|:---:|-----------------------------------:|
| — | 21 | 不适用（无模糊） |
| — | 24 | |
| — | 29 | |
| — | 33 | |

---

## 4. 验收判据

| 指标 | 目标 | 达标条件 |
|------|------|---------|
| 帧率 | ≥ 30 fps | 各场景 P95 < 33ms |
| 卡顿 | Janky < 5% | `gfxinfo` Janky frames 占比 |
| 启动 | 无 ANR | 冷启动到首帧 < 2s |
| 内存 | 不 OOM | 连续滑动 60s 无 OOM |
| 降级 | 生效 | 强制切到 Fallback 后仍可正常使用 |

### 4.1 验证降级链路

首页提供「强制质量分级」开关，可逐个切到 `Full` / `Medium` / `Minimal` / `Fallback`，观察：

- `Full` → 有折射与彩色分离
- `Medium` → 有模糊与高光，**无折射**
- `Minimal` → 有模糊，无折射，无高光折射
- `Fallback` → 无模糊，仅半透明渐变

这同时验证了「低端设备自动降级」路径的正确性（无需真低端设备，手动强制即可）。

---

## 5. 结论

- 设计上帧率目标 **≥ 30fps** 由以下保证：模糊降采样到 1/4（计算量 1/16）、CPU 路径 O(1)/像素、后台线程异步化、低内存设备自动降级
- **实测数据必须由验收方在真机采集**，本环境无法提供
- 若某设备在 `Full` 下未达 30fps，建议路径：先把 `GlassQuality.resolve()` 的 `isLowRamDevice` 判据扩展为「GPU 型号黑名单」，再考虑把 `Full` 的 `dispersionSamples` 从 7 降到 3
