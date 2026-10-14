# 夜间开发日志（持续更新 · 当前 07:10）

用户睡觉期间自主开发，48 个版本全部构建验证后推送 GitHub。**v1.0.0 里程碑已达成**。

## 版本总览（v0.1.0 → v1.0.4）

| 阶段 | 内容 |
|------|------|
| v0.1.0 | 竖屏 SDR 基础：FFT 频谱/瀑布、WFM 立体声、NFM/AM、仿真源 |
| v0.2.0 | RTL2832U+FC0013 驱动（librtlsdr 寄存器级移植） |
| v0.3.x | WAV 录音、收藏、MHz 输入、瀑布 Bitmap 优化、导频修复 |
| v0.4.x | SSB（Hilbert）、状态恢复、**验证器上线+修 4 个数学 bug** |
| v0.5.x | 搜台、LNA 增益 UI、前台服务保活 |
| v0.6.x | RDS（实验性）、日志面板、步进旋钮、NOAA/70cm 波段 |
| v0.7.x | HF 直采、谱线 EMA、频率刻度、录音收尾修复 |
| v0.8.x | SSB AGC、搜台存收藏、offset tuning、录音浏览器、RTL AGC |
| v0.9.x | 捏合变焦、调谐原子化、窄带 AGC、FFT 平均、S 表、静噪防抖、性能优化、并发三连修 |
| **v1.0.x** | **里程碑**、步进标签修复、录音时长显示、录制计时、40m/20m SSB 仿真台 |

## 质量记录

- **DspVerify**（tools/，Java 镜像同构验证器）：9 项全 PASS
- 修复严重 bug 9 个：Biquad 极点不稳、立体声鉴相器/系数、SSB 方向反转、RDS 块索引/Costas 极性、录音收尾丢失、调谐并发竞争、WavRecorder 数据竞争、引擎切换竞态
- 性能：DSP 链 270M→185M MAC/s（65 抽头优化），瀑布单次 drawImage
- 仿真覆盖：WFM（含立体声+RDS）/NFM/AM/LSB/USB 五种调制全可无硬件验证

## 待办

1. RDS 位同步精调（Gardner BER 10% → 目标 <1%，方案：4x 过采样扫描）
2. RTL-SDR 到货真机联调（日志面板就绪）
3. **GitHub Actions：需用户提供带 workflow scope 的 token**（workflow 文件已在本地）

## 已知事项

- workflow 文件在本地 `PocketSDR/.github/workflows/build.yml`，用户给新 token 后 `git checkout` 该文件推送即可
- 仿真模式已可完整体验所有功能（含 RDS "SIM RADIO" 台在 96.6MHz）
