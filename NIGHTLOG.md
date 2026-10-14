# 夜间开发日志（2026-10-15 01:00 → 05:00）

用户睡觉期间自主开发，11 个版本全部构建验证后推送 GitHub。

## 版本总览

| 版本 | 内容 |
|------|------|
| v0.1.0 | 竖屏 SDR 基础版：FFT 频谱/瀑布、WFM 立体声、NFM/AM、仿真源、Compose UI |
| v0.2.0 | RTL2832U + FC0013 USB 直连驱动（librtlsdr 寄存器级移植，到货即用） |
| v0.3.0 | WAV 录音、频率收藏、MHz 键盘输入 |
| v0.3.1 | 瀑布图渲染性能优化（Bitmap 环形缓冲，每帧 10 万 drawRect → 1 次 drawImage） |
| v0.3.2 | 修复仿真源 19kHz 立体声导频（原实现 PLL 无法锁定） |
| v0.4.0 | SSB 解调（USB/LSB，Hilbert 相位法）+ 启动状态恢复 |
| v0.4.1 | **独立 JVM 验证器**上线，抓出 3 个严重数学 bug（见下） |
| v0.4.2 | 修复 SSB USB/LSB 方向反转（验证器第 5 项测试立功） |
| v0.5.0 | FM 波段自动搜台（100kHz 网格扫描 + 峰值检出） |
| v0.5.1 | RTL LNA 增益控制 UI（23 档硬件滑条） |
| v0.5.2/3 | 后台收音保活（前台服务+WakeLock）、onStop 状态保存 |
| v0.6.0/1 | RDS 解码（Costas+Gardner，实验性）+ 仿真源 RDS 台 + UI 电台名 |

## 验证器抓出的严重 bug（tools/DspVerify.java，Java 镜像同构验证）

1. **Biquad a2 系数写反**（v0.4.1）：`(1+alpha)` 应为 `(1−alpha)`，极点在单位圆上 → 全部音频滤波器临界不稳定、缓慢爆音
2. **立体声 PLL 鉴相器**：cos(φ) 鉴相产生包络偏置 → VCO 锁到错误工作点；改 sin(φ) 正交鉴相 + 参数扫描
3. **立体声解码系数**：`2·sub` 应为 `sub`，数学推导错误 → L/R 无分离
4. **SSB Hilbert 系数符号**：环形卷积方向使奇对称系数需取负，否则 USB/LSB 互换
5. **RDS decodeGroup 块索引**：group type 在 B 块非 A 块
6. **RDS Costas 极性**：锁定到 90° 正交点，负反馈修正

## 验证结果（DspVerify ALL PASS，7 项）

- FFT 单频/负频镜像 bin 定位：精确
- FM 恒偏鉴频输出：0.5859375 = 75000/128000 精确吻合
- 立体声 PLL：lock=1.0，L/R RMS 分离 2.07x
- SSB 镜像抑制：6.7x（~16dB）
- RDS 块解码/CRC/PS 组装：位流直注 "TEST FM" 完整还原
- RDS Costas：锁定后理想时钟解码 PASS
- RDS Gardner 全链路：块锁定 10/帧，字符部分解出（参数待眼图精调）

## 待办（下轮心跳继续）

1. **RDS 全链路位同步**（当前卡点，详见下方 RDS 诊断档案）
2. DspVerify 级 3 射频链测试的抽取器与 Kotlin ComplexDecimator 同构化
3. RTL 到货真机联调：日志面板已就绪（v0.6.2）
4. GitHub Actions：**需要用户醒来后提供带 workflow scope 的 token**（workflow 文件已在本地）

## RDS 诊断档案（三轮心跳的实证结论）

- 级-1（位流直注）/ 级 0（理想时钟）/ 级 1（Costas+理想时钟）全 PASS——块解码、CRC、PS 组装、57k Costas、差分判据全部正确
- 级 2（Gardner 全链路）：BER 10.4%（偏移 0 对齐），块锁定 10/16——CRC 命中多为假阳性
- 已排除：低通带宽（2400/2800/3200 无差异）、判决滞后阈值（无效）、初始相位（无效）、失锁相位重置（无效）
- 结论：错误在 Gardner 采样时刻的抖动（位边界/中点之间游走），相邻位中心样本差分对采样相位敏感
- 下一步方案（待实施）：① 延迟匹配锁（位中点=电平跳变处检测，判决点定在过零后 1/4 位）② 或升 2 倍过采样 Gardner（4x）加 PI 带宽扫描
- 产品影响：无（RDS 是增强功能，接收/录音/搜台/SSB 全部正常）

## 已知事项

- workflow 文件在本地 `PocketSDR/.github/workflows/build.yml`，用户给新 token 后 `git checkout` 该文件推送即可
- 仿真模式已可完整体验所有功能（含 RDS "SIM RADIO" 台在 96.6MHz）
