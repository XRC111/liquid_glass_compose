<div align="center">

# Liquid Glass Compose · Demo

**Android 液态玻璃效果的完整示例程序（Jetpack Compose）**

四级降级链路 + 性能基准 + 真机验收清单。

[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/API-21%2B-3DDC84?style=flat-square)](#降级链路)

</div>

---

> 配套组件库：[liquid-glass-compose](https://github.com/XRC111/liquid-glass-compose)（可直接依赖）。
>
> Flutter 版本：[liquid_glass_demo](https://github.com/XRC111/liquid_glass_demo)。

## 项目结构

```
liquid_glass_compose/
├── app/                 # 演示 App（首页 / 列表 / TabBar / 设置）
├── benchmark/           # Macrobenchmark 性能基准
├── liquid-glass/        # 组件库模块（发布到 JitPack）
├── PERFORMANCE.md       # 性能测试报告与验收结论
└── gradle/
```

## 运行

```bash
# Debug 包
./gradlew :app:installDebug

# Release 包（R8 混淆 + 资源压缩）
./gradlew :app:assembleRelease

# Lint（验收要求零警告）
./gradlew :app:lintRelease

# 性能基准（需连接真机或模拟器，API 23+）
./gradlew :benchmark:connectedBenchmarkAndroidTest
```

## 演示内容

| 页面 | 观察点 |
| --- | --- |
| 首页 | 单块玻璃 + 参数调节，看折射/色散/边缘高光 |
| 列表 | 多块玻璃叠在滚动内容上，观察捕获与帧率 |
| TabBar | 悬浮玻璃底栏，折射下方列表 |
| 设置 | 手动切换 `GlassQuality` 档位，对比四档差异 |

## 降级链路

| 级别 | 触发条件 | 后端 | 折射 | 色散 | 模糊 |
| --- | --- | --- | --- | --- | --- |
| `Full` | API 33+ 且非低内存 | AGSL `RuntimeShader` + `RenderEffect` | 高度场 | 7 路 | 1/4 分辨率 |
| `Medium` | API 31–32 或低内存 | `RenderEffect.createBlurEffect` | 无 | 无 | 硬件两趟 |
| `Minimal` | API 24–30 | CPU 可分离 Box 模糊 | 无 | 无 | 1/3 降采样 |
| `Fallback` | API 21–23 或极低端 | 纯渐变 + 透明度 | 无 | 无 | 零离屏缓冲 |

`GlassQuality.resolve()` 为纯逻辑函数，不依赖图形 API，单测可完整覆盖。

## 性能

详见 [PERFORMANCE.md](PERFORMANCE.md)。设计目标（按档位）：

| 档位 | 目标帧率 | 单帧 GPU 预算 |
| --- | --- | --- |
| Full | ≥ 55 fps | ≤ 8 ms |
| Medium | ≥ 55 fps | ≤ 6 ms |
| Minimal | ≥ 50 fps | ≤ 10 ms（CPU） |
| Fallback | 60 fps | ≈ 0 |

实测方法与数据见 PERFORMANCE.md；不同机型差异较大，以真机为准。

## 验收清单

- [ ] 四档切换不崩溃、不闪屏
- [ ] 低内存设备（`isLowRamDevice`）自动降级
- [ ] 旋转屏幕后玻璃采样不错位
- [ ] 背景滚动时折射跟随，无撕裂
- [ ] Release 包 R8 混淆后纹理与着色器正常
- [ ] Lint 零警告

## 平台配置

- `minSdk 21` / `compileSdk 35` / `targetSdk 35`
- AGSL 仅在 API 33+ 可用；以下版本走 `RenderEffect` 或 CPU 路径

## 已知限制

- API < 33 无真实折射与色散（平台不提供运行时着色器）
- CPU 模糊档位在主线程执行，玻璃数量需控制
- 模拟器 GPU 行为与真机差异较大，性能结论以真机为准

## License

MIT
