# UFITOOLS-Widget

[![Build APK](https://github.com/Asunano/UFITOOLS-Widget/actions/workflows/build.yml/badge.svg)](https://github.com/Asunano/UFITOOLS-Widget/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

一个 Android 桌面小组件应用，用于实时监控随身 WiFi 设备（如 F50、U30 Air 等）的运行状态。通过设备 HTTP API 获取数据，在手机桌面和主界面仪表盘中直观展示。
<img width="6666" height="1284" alt="IMG_20260605_001025" src="https://blog.drxian.cn/wp-content/uploads/2026/06/IMG_20260605_001025.jpg" />

## ✨ 功能特性

### 设备监控
- 📶 **信号状态**：RSRP、SINR、RSRQ，支持 5 级信号格图标
- 🌡️ **温度监控**：CPU 核心温度、模组温度，分区显示（冷/暖/热/烫）
- ⚡ **CPU 与内存**：CPU 频率、使用率、核心数；内存使用量/总容量
- 📊 **流量统计**：日流量、月流量，自动适配字节单位（Bytes/KB/MB/GB）
- 🔋 **电池信息**：电量百分比（5 级图标）、充放电状态（⚡标识）、电流/电压
- 💾 **存储空间**：内部存储已用/总容量
- 📻 **网络详情**：运营商、网络类型（2G/3G/4G/4G+/5G NSA/SA）、频段、设备型号、固件版本

### 桌面小组件 (4×2)
- 小组件直接展示在桌面，无需打开应用
- 7 项显示可独立开关：温度 / 型号 / 信号 / 电池 / CPU / 内存 / 时间
- 独立小组件主题（跟随应用 / 强制浅色 / 强制深色）
- 错误状态覆盖层：设备离线时自动切换为离线提示

### 主题与外观
- 🎨 **应用主题**：跟随系统 / 浅色 / 深色 三段切换
- 🌈 **5 种预设配色**：默认白 / 科技蓝 / 薄荷绿 / 梦幻紫 / 活力橙
- 🎛️ **自定义颜色**：支持六位十六进制色值输入，实时预览
- 主题即时生效，全面覆盖所有页面

### 后台与更新
- ⏰ **后台自动刷新**：通过 WorkManager 定时采集数据（15/30/60/120 分钟可调）
- 🔄 **前台实时刷新**：主界面可配置 5s/10s/15s/30s 间隔自动刷新
- 🛡️ **智能故障处理**：TCP 检测设备可达性，网络/API 分级失败计数，自动暂停/恢复
- 🔍 **应用内更新**：从 GitHub 获取最新版本，支持官方源和国内镜像双源切换，SHA256 完整性校验

### 诊断与调试
- 🐛 **调试模式**：关于页连续点击版本号 5 次激活
- 📋 **调试日志**：内存 + 文件持久化，敏感信息脱敏，支持复制/分享/状态快照

## 📱 系统要求

| 项目 | 要求 |
|------|------|
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 14 (API 34) |
| 编译 SDK | 34 |
| 设备要求 | 需与 UFI 随身 WiFi 设备处于同一局域网 |

## 📥 使用方法

1. 从 [Releases](https://github.com/Asunano/UFITOOLS-Widget/releases) 下载最新 APK 安装
2. 确保手机已连接随身 WiFi 设备的 WiFi 网络
3. 打开应用，在首次配置向导中填写设备地址和管理口令（默认 `admin`）
4. 应用自动探测协议并同步设备信息
5. 回到桌面，长按添加「UFITOOLS Widget」小组件（4×2 尺寸）

## 🔧 工作原理

```
┌──────────────────┐     HTTP API (REST)    ┌───────────────────┐
│  Android 小组件   │ ◄──────────────────► │  UFI 随身 WiFi 设备  │
│  UFITOOLS Widget  │   HMAC-MD5+SHA256    │  (默认 192.168.0.1) │
│                   │   签名鉴权             │                    │
│  ┌─────────────┐  │                       │  /api/baseDeviceInfo│
│  │ WifiCrawl   │──┤◄── 设备数据            │  /api/version_info  │
│  │ 数据采集     │  │                       │  AT 指令查询         │
│  └─────────────┘  │                       └───────────────────┘
│  ┌─────────────┐  │
│  │ WorkManager │──┤◄── 后台周期刷新
│  └─────────────┘  │
│  ┌─────────────┐  │
│  │SharedPrefs  │──┤◄── 数据缓存与小组件渲染
│  └─────────────┘  │
└──────────────────┘
```

## 🏗️ 项目结构

```
UFITOOLSWidget/
├── app/src/main/java/com/ufi_toolswidget/
│   ├── MainActivity.kt                  # 主界面仪表盘
│   ├── SetupActivity.kt                 # 首次配置向导
│   ├── SettingsActivity.kt              # 设置主页（卡片导航）
│   ├── AppSettingsActivity.kt           # 主题、配色、刷新间隔
│   ├── ConfigModifyActivity.kt          # 连接配置修改
│   ├── WidgetSettingsActivity.kt        # 小组件显示项与主题
│   ├── AboutActivity.kt                 # 关于、更新检查、调试入口
│   ├── DebugLogActivity.kt              # 调试日志查看
│   ├── AddWidgetActivity.kt             # 小组件钉选代理
│   ├── widget/
│   │   └── WifiWidget.kt                # 桌面小组件提供者（4×2）
│   ├── worker/
│   │   └── WifiWorker.kt                # WorkManager 后台定时任务
│   └── util/
│       ├── WifiCrawl.kt                 # HTTP API 数据采集 + AT 指令解析
│       ├── NetUtil.kt                   # OkHttp 客户端 + 签名算法
│       ├── SPUtil.kt                    # SharedPreferences 统一管理
│       ├── UpdateChecker.kt             # 应用更新检查与下载
│       ├── ThemeColors.kt               # 5 套预设 + 自定义配色主题
│       ├── ThemeUtil.kt                 # 主题动态应用到控件
│       ├── DebugLogger.kt               # 调试日志系统
│       ├── AnimationUtil.kt             # 动画工具（模糊、弹窗、点击反馈）
│       ├── BackgroundUtil.kt            # 窗口背景管理
│       └── ScaleTouchListener.kt        # 触控缩放反馈
└── .github/workflows/build.yml          # CI/CD 自动构建与发布
```

## 🛠️ 技术栈

- **语言**：Kotlin
- **HTTP 客户端**：OkHttp 4


- **后台任务**：AndroidX WorkManager（CoroutineWorker）
- **UI**：AndroidX + Material Design + CardView
- **构建**：Gradle + AGP 8.7.3
- **CI/CD**：GitHub Actions（并行 Job、Gradle 缓存、自动发布 Release）

## 🔨 本地构建

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

> 项目 JAVA_HOME 需指向 **JDK 21**。建议使用 Android Studio 自带的 JBR。

## 📦 CI/CD

推送 `v*` 标签（如 `v0.2`）后，GitHub Actions 自动：

1. **并行构建** Debug + Release APK
2. 创建 GitHub Release 并上传 APK
3. 生成含完整 `apkUrl` / `apkSize` / `apkSha256` 的 `version.json`
4. 更新 `CHANGELOG.md`

应用内更新检查直接读取 `version.json`，支持 GitHub 官方源和国内镜像（gh-proxy）双源切换。

## 📄 许可

[MIT License](LICENSE)

## 🙏 致谢

- [UFI-TOOLS](https://github.com/kanoqwq/UFI-TOOLS) — 原版 UFI 设备管理工具，提供 API 接口参考
