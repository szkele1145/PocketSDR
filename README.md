# PocketSDR

竖屏单手操作的安卓 SDR（软件定义无线电）工具，RTL-SDR（RTL2832U + FC0013）即插即用，带仿真模式无硬件也能完整体验。

## 功能

### 接收
- **WFM 立体声**：19kHz 导频 PLL 立体声解码，50µs 去加重，RDS 电台名（实验性）
- **NFM**：业余电台 / 对讲机（±5kHz 频偏）
- **AM**：航空波段（118–137 MHz）
- **USB / LSB**：Hilbert 相位法单边带 + 慢速 AGC（90dB 动态范围压缩）+ 100Hz 步进——短波收听
- **HF 直接采样**：RTL2832 ADC 直收（无 tuner），HF 天线接上即可听短波

### 频谱与调谐
- 实时 FFT 频谱 + 瀑布（Bitmap 环形缓冲，~15 行/秒）
- 谱线 EMA 余晖（SDR# 风格：上升快跟/下降慢衰减）
- 每 MHz 网格 + 频率标签；点峰跳频、拖动平移
- 模式自适应步进（WFM 100k / NFM 12.5k / AM 25k / SSB 100Hz）+ 长按连续步进

### 硬件（RTL-SDR）
- RTL2832U + FC0013 寄存器级移植（librtlsdr 同源逻辑）
- 23 档 LNA 增益（-9.9 ~ +19.7 dB）、HF 直采、offset tuning（1/f 噪声抑制）、RTL 内部 AGC 开关
- 全链路日志面板（长按状态行）：USB/基带/tuner/采样率/调谐 + bulk 超时诊断，可落盘

### 收听体验
- WAV 录音（文件浏览器 + 删除 + 系统分享）
- FM 全波段自动搜台（100kHz 网格 + 峰值检出，结果一键存收藏）
- 收藏频道（点击跳台）、启动状态恢复、后台收音前台服务保活
- 仿真模式：8 个 FM 台（含立体声/RDS 台）+ 航空 AM + 2m NFM + 真实噪声底

## 使用

1. 下载 APK 安装（仓库 releases 目录 或自构建）
2. 无硬件：直接打开即仿真模式，频谱可见多个模拟电台
3. RTL-SDR：OTG 连接 → USB 权限 → 自动切换硬件（日志面板可排障）

## 质量

- **DspVerify**：9 项独立 JVM 验证全 PASS（FFT bin 定位/FM 鉴频精度/立体声 PLL/SSB 镜像抑制/RDS 块解码/Costas/射频链/AGC 压缩）
- 夜间自主开发抓出并修复 **6 个严重数学 bug**（Biquad 极点不稳、立体声鉴相器、SSB 方向反转、RDS 块索引等，详见 NIGHTLOG.md）

## 开发

```powershell
.\build.ps1 -Task assembleDebug   # 本地构建（subst Z: 绕中文路径）
java tools/DspVerify 验证器编译运行见 NIGHTLOG.md
```

## Roadmap

- [x] v0.1–0.8：全模式接收 + RTL 全硬件链 + 录音/搜台/收藏/日志
- [ ] RDS 全链路位同步（Gardner BER 10% → 目标 <1%，方案：4x 过采样扫描）
- [ ] RTL-SDR 到货真机联调
- [ ] GitHub Actions（待 workflow scope token）
