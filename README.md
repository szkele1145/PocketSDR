# PocketSDR

竖屏单手操作的安卓 SDR（软件定义无线电）工具，RTL-SDR（RTL2832U + FC0013）即插即用，带仿真模式无硬件也能完整体验。

## 功能

- **频谱扫描**：1.024 Msps 实时 FFT 频谱 + 瀑布图，点峰跳频，拖动调谐
- **WFM 立体声收音**：87.5–108 MHz，19kHz 导频 PLL 立体声解码，50µs 去加重
- **NFM**：业余电台 / 对讲机（±5kHz 频偏）
- **AM**：航空波段（118–137 MHz）
- **仿真模式**：内置模拟 FM 广播台 + 航空台 + 2m 业余台 + 真实噪声底，硬件没插也有完整体验
- **竖屏拇指友好**：上半屏频谱瀑布，下半屏大按钮/滑块控制区，单手可操作

## 使用

1. 从 GitHub Releases（Actions 产物）下载 APK 安装
2. 无硬件直接体验：打开 App 即进入仿真模式，频谱上能看到一排模拟电台
3. RTL-SDR 到货后：OTG 线连接 → 弹出 USB 权限 → 自动切换真实硬件（v0.2 驱动）

## 技术栈

- Kotlin + Jetpack Compose（Material 3），单 Activity 竖屏
- 纯 Kotlin DSP：radix-2 FFT、Hamming 窗 FIR 多相抽取、biquad、PLL 立体声解码
- 采样率链：1.024 Msps → ÷4 → 256 ksps（WFM）/ ÷32 → 32 ksps（NFM/AM）→ AudioTrack 32kHz
- 仿真源：数字域多台合成（相位连续、带外台跳过合成），与 RTL 相同的 float IQ 接口

## 开发

```powershell
# Windows 本地构建（项目在中文路径下时用脚本，subst Z: 绕开 mojibake）
.\build.ps1 -Task assembleDebug

# 或 gradlew
./gradlew assembleDebug
```

## Roadmap

- [x] v0.1.0 频谱/瀑布 + WFM/NFM/AM + 仿真源 + 竖屏 UI
- [ ] v0.2 RTL2832U + FC0013 USB 直连驱动
- [ ] v0.3 录音、频率记忆、RDS 解码
- [ ] v0.4 SSB 解调 + 增益/带宽控制
