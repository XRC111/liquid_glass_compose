# 性能测试报告

> **重要说明**：本报告中的帧率数据**不是实测值**。构建环境为 CI 沙箱，**无任何物理设备与模拟器**，无法运行 `adb shell dumpsys gfxinfo` 或 Macrobenchmark。因此本文提供的是：**（1）可复现的测量方法**、**（2）基于实现成本模型的预期区间**、**（3）降级链路的设计依据**。验收方按第 1 节步骤在真机执行即可得到真实数据填入第 3 节表格。
>
> 所有非实测内容均已显式标注，未伪装为实测结果。

---

## 1. 测量方法（可复现）

### 1.1 环境要求

| 项 | 要求 |
|----|------|
| 设备 | 至少覆盖 API 21 / 24 / 29 / 33 各一台 |
| 构建 | `./gradlew :app:installRelease`（Release + R8，避免 Debug 的额外开销失真） |
| 工具 | `adb`（platform-tools）、`gfxinfo`、可选 Macrobenchmark |

### 1.2 采集步骤

```bash
# 1) 安装 Release 包
./gradlew :app:installRelease

# 2) 清空统计
adb shell dumpsys gfxinfo com.example.liquidglass reset

# 3) 手动执行测试场景（每场景持续 10 秒）
#    场景 A：首页静止（玻璃无交互）
#    场景 B：首页按住卡片并匀速拖动（触摸跟随）
#    场景 C：TabBar 页反复切换 4 个 Tab
#    场景 D：卡片页持续上下滑动列表

# 4) 读取统计
adb shell dumpsys gfxinfo com.example.liquidglass | grep -A 10 "Janky frames"
```

关注三项：

- **Janky frames (%)** — 目标 < 5%
- **90th / 95th / 99th percentile** — 目标 95th < 33ms（≥30fps）
- **Total frames rendered** — 用于交叉验证采样时长

### 1.3 应用内自测指标

库暴露了 `LiquidGlassState.lastRenderCostMs`，记录最近一次后台模糊耗时。可在三页的 `QualityBanner` 上直接观察：

```kotlin
Text("后台模糊耗时：${state.lastRenderCostMs} ms")
```

这是**后台模糊单次耗时**，不代表整帧绘制耗时，但可用于判断 CPU 路径是否成为瓶颈。

### 1.4 自动化替代方案（推荐）

Macrobenchmark 的 `FrameTimingMetric` 可产出 P50/P90/P99，比 `gfxinfo` 更稳定：

```kotlin
@get:Rule val rule = MacrobenchmarkRule()

@Test
fun glassScroll() = rule.measureRepeated(
    packageName = "com.example.liquidglass",
    metrics = listOf(FrameTimingMetric()),
    iterations = 5,
    startupMode = StartupMode.WARM,
) {
    startActivityAndWait()
    device.swipe(/* 列表滑动 */)
}
```

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
