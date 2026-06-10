# UFITOOLS-Widget

[![Build APK](https://github.com/Asunano/UFITOOLS-Widget/actions/workflows/build.yml/badge.svg)](https://github.com/Asunano/UFITOOLS-Widget/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

一个 Android 桌面小组件应用，用于实时监控随身 WiFi 设备（如 F50、U30 Air 等）的运行状态。通过设备 HTTP API 获取数据，在手机桌面和主界面仪表盘中直观展示，并提供完善的通知警报与后台保活机制。

<img width="6666" height="1284" alt="IMG_20260605_001025" src="https://blog.drxian.cn/wp-content/uploads/2026/06/IMG_20260605_001025.jpg" />

## ✨ 功能特性

### 主界面仪表盘

主界面以卡片形式展示设备全部运行状态，每张卡片均可点击弹出详情弹窗：

- 📻 **设备头部**：设备型号、固件版本号、UFI-TOOLS 构建号
- 📶 **网络信号**：RSRP/SINR/RSRQ 信号质量，5 级信号格矢量图标（根据 RSRP dBm 值自动推算：>-85 满格 / -95 四格 / -105 三格 / -115 两格 / 其余一格），网络制式图标（2G/3G/4G/4G+/5G 自动切换），运营商名称（PLMN 码自动映射中国移动/联通/电信/广电）
- 🌡️ **温度监控**：CPU 核心温度、模组温度，详情弹窗展示各模块温度列表（`cpu_temp_list`）
- ⚡ **CPU 详情**：总体使用率、各核心使用率、各核心频率（当前/最大 MHz），详情弹窗分核展示
- 💾 **内存使用**：使用率百分比，详情弹窗展示已用/可用/SWAP 详细容量
- 📊 **流量统计**：日流量、月流量双通道显示，自动适配字节单位（Bytes/KB/MB/GB），流量字段智能推断（明确字节字段直接使用，>500MB 视为 Bytes，模糊小值按 GB/MB 换算）
- 🔋 **电池信息**：电量百分比 + 5 级电池矢量图标（0-4 格动态切换）、充放电状态（⚡标识，电流 >50mA 判定充电中）、电流/电压详情
- 📁 **存储空间**：内部存储已用/总容量，详情弹窗展示内部/外部存储的已用/总量/可用
- 🌐 **网络地址**：客户端 IP，详情弹窗展示 WAN IPv4/IPv6、PDP 承载类型
- 📱 **固件版本**：固件版本号 + appVerCode，详情弹窗展示 appVer/webVersion/firmwareVer

**交互体验**：

- 🎬 **入场动画**：Header → 网络卡片 → 更新按钮 → 硬件卡片的交错淡入序列，卡片内数据行依次淡入（40ms 间隔）
- 🔄 **加载动画**："请稍候"点循环动画（500ms 间隔）+ 容器呼吸式脉冲缩放（1.0→1.03→1.0 循环）
- 📈 **数据变更检测**：快速哈希（`computeQuickUiHash`）计算核心字段哈希值，数据未变时跳过大部分 UI 更新以减少重绘
- 🪟 **弹窗实时刷新**：已打开的详情弹窗在后台数据刷新后自动更新内容（带哈希变更检测）
- 🛡️ **弹窗防拥堵**：300ms 内禁止重复打开弹窗，同类型复用，不同类型先关后开

**快捷操作**：

- ⚙️ 设置入口 → 跳转设置主页
- 🔔 警报历史 → 跳转警报历史页面（通知功能关闭时隐藏，有未读时显示红点）
- 🔄 检查更新 → 在线检查版本更新（"检查中..."点循环动画 → 结果反馈，3 秒后自动恢复按钮文字）

**离线/错误处理**：

设备离线时弹出不可关闭的错误弹窗，区分三种原因：`REASON_NETWORK`（"无法与设备通信" — 设备未开机/不在同一内网/IP 配置有误）、`REASON_API`（"连接配置异常" — 端口配置错误/Token 不正确/API 服务异常）、其他（"连接失败" — 通用提示）。提供"重新连接"和"修改连接配置"两个操作按钮，Worker 正常运行时自动关闭错误弹窗。

**崩溃恢复**：启动时检测上次崩溃标志，延迟 1 秒弹出崩溃信息弹窗（显示发生时间和异常摘要），可跳转日志分享页或忽略清除。

### 桌面小组件

#### 4×2 标准版（已启用）

桌面直接展示设备核心状态，无需打开应用。布局分为四行：

| 行 | 内容 | 说明 |
|---|------|------|
| 第一行 | 路由器图标 + 设备型号 + 固件版本 + 构建号 + 信号格图标 + 网络制式图标 + 电量图标 + 电量值 + 充电⚡标识 | 头部状态栏 |
| 第二行 | 今日流量（36sp 大字体）+ 本月流量（36sp 大字体） | 流量核心展示区，中间分割线 |
| 第三行 | 🌡️温度 + ⚡CPU% + 💾RAM% + 📶信号dBm | 硬件状态缩略行 |
| 第四行 | 更新时间 | 最后成功采集时间 |

**配置项**（通过「小组件设置」页面调整）：

- **显示项开关**（8 项可独立控制）：流量 / 温度 / 型号 / 信号 / 电池 / CPU / 内存 / 更新时间
- **小组件主题**：跟随应用 / 强制浅色 / 强制深色
- **主题配色**：跟随应用配色 或 独立选择颜色主题索引
- **背景透明度**：0-100% 滑块调节
- **自定义背景图片**：从相册选择图片，支持裁剪适配（通过 `ImageCropActivity`），JPEG 质量 90% 保存到内部存储，最多保留 3 条历史
- **圆角裁剪**：20dp 圆角（可通过兜底开关关闭为直角）

**错误状态覆盖层**：Worker 因连续失败被停止时，隐藏正常数据区，全屏居中显示路由器离线图标 + "设备连接失败" + "点击重试"。点击触发刷新广播。

**渲染优化**：

- 2 秒渲染去重锁（Worker 和 MainActivity 双重触发去重）
- 数据指纹哈希：14 个数据字段 + 20+ 个外观设置字段联合计算，数据未变时跳过整次渲染
- `WidgetBitmapCache` 位图缓存：分离纯色背景/自定义图片两条缓存链路，避免每次渲染重建 Bitmap

#### Material You 动态配色（实验性，Android 12+）

从系统壁纸或小组件背景图提取色调，自动适配文字颜色：

- **对比度**：柔和 / 标准 / 强烈（三级可调）
- **色源选择**：Primary / Secondary / Tertiary / Neutral / NeutralVariant（5 种 Material You 调色板）
- **高级设置**：浅色/深色模式独立调节背景亮度、文字亮度（滑条 0-100），饱和度增强（0-200%）

### 通知警报系统

#### 7 种警报类型

| 类型 | 触发条件 | 默认阈值 | 阈值范围 | 预设快捷值 | 应用内反馈 |
|------|---------|---------|---------|-----------|-----------|
| 📊 今日流量超限 | 日用量 >= 阈值 | 1 GB | 1-100 GB | 1/5/10/50/100 GB | 警告确认弹窗 |
| 📊 本月流量超限 | 月用量 >= 阈值 | 10 GB | 10-500 GB | 10/50/100/200 GB | 警告确认弹窗 |
| 🌡️ 温度过高 | 温度 >= 阈值 | 70°C | 30-100°C | 40/50/60/70/80°C | 警告 Toast |
| ⚡ CPU 异常占用 | CPU 占用 >= 阈值 | 80% | 20-100% | 30/50/70/90/100% | 警告 Toast |
| 💾 内存占用过高 | 内存占用 >= 阈值 | 90% | 50-100% | 50/60/70/80/90% | 警告 Toast |
| 🔋 电量过低 | 电量 <= 阈值 | 20% | 10-50% | 10/20/30/40/50% | 警告 Toast |
| 📡 设备上下线 | 在线状态变化 | — | — | — | 上线=信息 Toast / 下线=警告 Toast |

每种警报可单独启用/禁用。设备上下线不做防抖，状态变化即通知；其余类型均有智能防抖。

#### 通知渠道配置

- **警报通知渠道** (`device_alerts`)：`IMPORTANCE_HIGH` + 横幅 + 铃声（系统默认通知音）+ 震动（`[0, 300, 200, 300]ms` 模式）+ 橙色 LED（`#FF6B35`）+ 锁屏可见 + 角标
- **全屏意图**：`setFullScreenIntent()` 强制弹出横幅通知，确保国产 ROM 不被降级为静默通知
- **通知分类**：`CATEGORY_ALARM`，锁屏完整展示
- **通知 ID**：自增计数器（10000-15000 回绕），每次通知使用唯一 ID 不覆盖
- **无权限降级**：无通知权限时仍然记录到警报历史，但不发送系统通知

#### 智能防抖机制

防抖间隔动态读取用户设置的监控检查间隔（15-600 秒），每种类型独立维护最后通知时间戳。`synchronized(debounceLock)` 原子操作防止多线程并发触发，时间窗口判断与时间戳更新分离（`debouncePass` 仅判断，`updateLastNotifyTime` 发送时写入）。

#### 警报历史

Room 数据库持久化存储所有警报记录：

- **分页浏览**：每页 10/20/50/100 条可调，翻页栏支持首页/上一页/下一页/末页/跳转指定页
- **类型筛选**：全部 / 日用量 / 月用量 / 温度 / CPU / 内存 / 电池 / 设备（8 种）
- **状态筛选**：全部 / 未读 / 已读
- **操作**：全部标记已读 / 清空全部（红色确认弹窗）
- **滑动操作**：右滑标记已读（绿色背景）/ 左滑删除（红色背景），双阈值体系（慢拖 35% 或快扫 20%+600dp/s）
- **详情查看**：点击卡片弹窗显示标题、内容、触发时间、未读状态
- **自动清理**：最多保存 100/500/1000/不限条，超出后自动清理旧记录
- **实时更新**：`BroadcastReceiver` 监听数据变更广播，新警报产生时自动刷新列表
- **未读计数**：主界面警报按钮显示红点，副标题实时显示"共 N 条，M 条未读"

#### 国产 ROM 通知渠道降级检测

每次进入通知设置页面时自动检测渠道 importance 是否 >= `IMPORTANCE_HIGH`。若被 ColorOS/MIUI/EMUI 等国产 ROM 静默降级，弹窗引导用户跳转系统通知渠道设置页面手动开启横幅和铃声。

### 流量历史记录

Room 数据库持久化记录设备流量数据，前台（`MainViewModel`）和后台（`WifiWorker`）每次成功采集后自动写入。存储设备 API 返回的原始累计字节值，增量计算延迟到展示层完成。最多保留 366 条记录（约 1 年），总开关可一键启停。

#### 记录类型与查看模式

- **每日记录**（`dateKey = yyyy-MM-dd`，`recordType = daily`）：DB 分页查询，展示日期 + 日流量 + 月累计。每天仅保留最新一条（`OnConflictStrategy.REPLACE` 覆盖）
- **每小时记录**（`dateKey = yyyy-MM-dd-HH`，`recordType = hourly`，可选）：全量加载后计算相邻记录差值得到每小时增量（`computeHourlyDeltas()`），同日取差值、跨日取原值、`maxOf(0, ...)` 防负值，内存分页展示
- **月度聚合**：SQL `GROUP BY yyyy-MM` + `MAX(dateKey)` 取每月最后一天的累计值，DB 分页展示月流量

#### 界面功能

- 「每日」/「月度」Tab 切换 + `PaginationBarHelper` 胶囊分页栏（首页/上下页/末页/跳转），每页 10-100 条可调
- 设置弹窗：每小时记录开关 / 快捷入口开关 / 每页条数 / 每月重置日（1-28 号）
- `DailyTrafficAdapter` / `MonthlyTrafficAdapter`，基于 `ListAdapter` + `DiffUtil` 增量更新，主题跟随实时刷新
- 主界面流量卡片可配置为快捷入口，点击弹出流量详情弹窗（`MainDialogHelper.fillTrafficDetail()`），随数据刷新实时更新

### 后台保活机制

五层保活架构，确保通知功能在任何场景下正常工作：

**第一层 — NotificationMonitor 协程轮询**：独立于 Activity 生命周期的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，Application 启动时启动无限循环，最快 15 秒间隔（15-600 秒动态可调）。调用 `WifiCrawl.fetchNotificationBaseInfo()` 轻量级获取设备信息（仅请求 `/api/baseDeviceInfo` 一个端点，无冷却延迟，性能开销约为完整采集的 1/6~1/10）。进程死亡即失效。

**第二层 — 前台服务 (BackgroundMonitorService)**：`IMPORTANCE_LOW` 静默常驻通知，`PartialWakeLock` 持续持有防止 CPU 休眠，`START_STICKY` 被杀后自动重启。支持自定义通知标题和内容，修改后即时刷新。

**第三层 — AlarmReceiver 精确定时器**：`setAlarmClock()` 穿透 Doze 模式（Android 唯一不受任何限制的 API），showIntent 传 null 不显示状态栏图标，5 分钟最小间隔无频率上限。三级降级：`setAlarmClock` → `setExactAndAllowWhileIdle` → `setAndAllowWhileIdle`。`PARTIAL_WAKE_LOCK` 30 秒超时保护，链式调度。

**第四层 — WorkManager 周期任务**：`WifiWorker`（后台完整数据采集，1-1440 分钟可调）+ `KeepAlivePeriodicWorker`（保活补充，15-120 分钟，约束需网络连接，指数退避）。系统级持久化，进程死亡后仍可重新唤醒。

**第五层 — 无障碍服务 (KeepAliveAccessibilityService)**：注册 AccessibilityService 提高进程优先级，不执行任何无障碍操作，用户需在系统无障碍设置中手动启用。

**辅助功能**：

- **开机自启**：`BootReceiver` 接收 `BOOT_COMPLETED`，自动恢复前台服务、闹钟调度、周期 Worker
- **电池优化白名单**：优先 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 一步弹窗，失败回退列表页
- **自启动权限引导**：自动检测 ROM 品牌，支持 MIUI（小米/红米）、EMUI（华为/荣耀）、ColorOS（OPPO/realme）、FuntouchOS（vivo/iQOO）、Flyme（魅族）、OneUI（三星）
- **最近任务锁定引导**：根据 ROM 品牌显示不同操作说明
- **后台隐藏**：`setExcludeFromRecents(true)` 从最近任务隐藏（警告确认后生效）

### 主题与外观

- 🎨 **应用主题**：跟随系统 / 浅色 / 深色 三段切换，`UiModeManager` 读取真实系统模式，不变静默应用，变化触发圆形揭露脉冲动画，`AppCompatDelegate.setDefaultNightMode()` 全局即时生效
- 🌈 **5 种预设配色**：默认白 / 科技蓝 / 薄荷绿 / 梦幻紫 / 活力橙，GridLayout 双栏选择，选中态 accent 实色填充
- 🎛️ **自定义颜色**：六位十六进制色值输入 + 圆形预览色块，实时校验，深色模式自动计算变体（亮度 ×0.85）
- 主题切换即时生效，全面覆盖所有页面，同步刷新小组件 + 广播主题变更通知
- 🖼️ **自定义背景图片**（入口暂时隐藏）：相册选择 + 尺寸检测 + `SimpleCropView` 裁切 + JPEG 90% 保存

### 数据采集与协议

#### API 端点

| 端点 | 认证 | 用途 |
|------|------|------|
| `/api/baseDeviceInfo` | 需要 | 设备基础信息（CPU/内存/温度/流量/电池/存储等） |
| `/api/goform/goform_get_cmd_process` | 需要 | Goform 信号与设备身份信息 |
| `/api/AT` | 需要 | AT 指令透传通道（网络信号详情） |
| `/api/version_info` | 不需要 | 设备固件版本信息 |
| `/api/need_token` | 不需要 | 检测是否需要登录认证 |

所有路径均可通过高级配置自定义。

#### 认证机制（Kano 签名）

每个认证请求携带三个 HTTP Header：`kano-t`（时间戳）、`kano-sign`（HMAC-MD5+SHA256 混合签名）、`Authorization`（用户 Token）。

签名算法：构造 `"minikano" + METHOD + PATH + TIMESTAMP` → HMAC-MD5 签名 16 字节 → 二分前后 8 字节 → 分别 SHA256 → 拼接 64 字节 → 最终 SHA256 → 64 字符十六进制字符串。`MessageDigest`/`Mac` 实例使用 `ThreadLocal` 缓存。

#### 两阶段采集策略

设计目的：确保 CPU 读数准确性（避免并发请求导致设备 CPU 飙高）。

**第一阶段（串行）**：注入 1-2 秒随机冷却 → 单独请求 baseDeviceInfo 采集 CPU 基准值 → 再次冷却 1-2 秒。

**第二阶段（5 路并发）**：Goform 信号信息 + Goform 设备身份 + version_info（1h 缓存）+ need_token（1h 缓存）+ AT 网络详情（内部 10-13 路并发 AT 请求）。

#### AT 指令通道

AT 指令通过 `/api/AT?command=<URLEncoded>&slot=0` 透传到基带芯片。首次调用通过 `AT+CGMI` 自动检测芯片平台（Spreadtrum / Quectel），结果缓存到磁盘。

| AT 指令 | 用途 | 平台 |
|---------|------|------|
| `AT+COPS?` | 运营商 + 接入技术 | 通用 |
| `AT+C5GREG?` | 5G NR 注册状态 | 展讯专用 |
| `AT+QENG="servingcell"` | 服务小区信号详情 | Quectel 专用 |
| `AT+CESQ` | 信号质量（3GPP 编码值） | 通用 |
| `AT+CGEQOSRDP=1` | 签约速率 | 通用 |
| `AT+CGSN`/`CGMM`/`CGMR` | IMEI/模块型号/固件版本 | 通用（1h 缓存） |
| `AT+CREG?`/`CGCONTRDP=1`/`CPIN?`/`CFUN?`/`CPAS`/`CGATT?` | 注册/WAN/PIN/射频/状态/附着 | 通用 |

**3GPP 信号换算**：LTE `RSRP = raw-140` dBm / `RSRQ = raw/2-19.5` dB / `SINR = raw/5-20` dB；NR 5G `RSRP = raw-156` / `RSRQ = raw/2-43` / `SINR = raw*0.5-23`。智能 RAT 检测：RSRP>97 或 RSRQ>34 自动切换 NR 公式。

**信号值优先级**：AT RSRP → Goform RSRP（正值自动修正）→ Goform RSSI。

#### 协议自动探测

私有 IP → 跳过探测始终 HTTP；域名/公网 IP → HTTPS 优先（请求 `/api/need_token` + 签名验证，响应为合法 JSON 确认）→ HTTP 回退 → 均失败返回 null。

#### 多级缓存策略

1 小时 TTL：version_info、need_token、AT 静态字段（CGMM/CGMR/CGSN）。永久缓存：月流量本地值、芯片平台探测结果。进程内缓存：baseUrl 内存缓存。

### 后台数据刷新与故障处理

- ⏰ **后台 Worker 刷新**：WorkManager `PeriodicWorkRequest`，间隔 1-1440 分钟可调（默认 15 分钟）
- 🔄 **前台实时刷新**：主界面协程定时器，5-3600 秒可调（默认 5 秒），`onPause` 停止 / `onResume` 恢复，预设 5s/10s/15s/30s/60s Chip 快捷值 + 滑块精确调节
- 🛡️ **TCP 可达性检测**：每次 Worker 执行前 TCP ping 设备（1 秒超时）
- 🛡️ **两级独立失败计数器**：网络失败连续 2 次 或 API 失败连续 3 次 → 标记 stopped，返回 `Result.retry()`（非 `failure()`，确保 ping 恢复后自动解除）

### 应用内更新

- 🔍 读取 GitHub 仓库 `version.json` 静态文件检查更新，无请求频率限制
- 🌐 **双源切换**：GitHub 官方源 / 国内镜像（gh-proxy），一键切换
- 📋 弹窗展示版本号 + 格式化更新日志 + APK 大小
- 🔐 下载完成后 SHA256 校验，校验失败删除文件提示重新下载
- 📲 `DownloadManager` 系统下载 → `FileProvider` → 调起系统 APK 安装器
- ⏱️ 启动时静默自动检查（受开关控制），每 24 小时最多一次
- 网络错误自动识别，官方源失败时建议切换镜像

### 首次配置向导

设备连接地址（IP:端口 或 域名，默认 `192.168.0.1:2333`）+ 认证口令（默认 `admin`，SHA256 哈希存储）。保存后自动触发协议探测（私有 IP 跳过）。可跳过使用默认配置。异常容错：配置页面崩溃时自动跳过。

### 高级连接配置

分为基础连接（地址 + 口令）和高级配置（需红色警告确认后编辑，留空使用默认值）：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 设备信息接口 | `/api/baseDeviceInfo` | API 路径 |
| AT 命令接口 | `/api/AT` | AT 指令端点 |
| Goform 命令接口 | `/api/goform/goform_get_cmd_process` | Goform 端点 |
| 签名密钥 | `minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd` | HMAC 签名密钥 |
| 设备平台 | `auto`（自动探测） | auto / spreadtrum / quectel |

配置变更后自动重置 Worker 失败状态、触发协议探测、刷新小组件。高级配置保存时额外清除所有 API 响应缓存。

### 诊断与调试

- 🐛 **调试模式**：关于页连续点击版本号 5 次（1.5 秒超时窗口）激活
- 📋 **调试日志**：内存最多 800 条 + 文件持久化（3MB 上限截断），自动落盘阈值 20 条，5 种分类（API/数据、UI 渲染、系统、生命周期、异常），敏感信息脱敏（IP/IMEI/Token/Authorization），线程安全
- 📊 **全量诊断报告**：系统信息 + UI 视图快照 + 分类统计 + 最近 50 条 API/30 条 UI/20 条异常日志 + API 连接状态
- 📤 通过 FileProvider 分享诊断文件（`ufitools_diagnostic.txt`）
- 🔧 **崩溃处理**：`CrashHandler` 独立进程（`:crash_handler`）捕获未处理异常，下次启动弹窗展示


## 📱 系统要求

| 项目 | 要求 |
|------|------|
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 14 (API 34) |
| 编译 SDK | 34 |
| JDK 版本 | 21 |
| 设备要求 | 需与 UFI 随身 WiFi 设备处于同一局域网 |

## 📥 使用方法

1. 从 [Releases](https://github.com/Asunano/UFITOOLS-Widget/releases) 下载最新 APK 安装
2. 确保手机已连接随身 WiFi 设备的 WiFi 网络
3. 打开应用，在首次配置向导中填写设备地址和管理口令（默认 `admin`）
4. 应用自动探测协议并同步设备信息
5. 回到桌面，长按添加「UFI 状态 (4x2)」小组件

### 通知功能配置

1. 进入「设置」→「通知管理」，开启通知总开关
2. 根据需要启用各类警报（流量/温度/CPU/内存/电量/设备在线），设置触发阈值
3. 调整监控检查间隔（15-600 秒）
4. 进入「后台保活配置」，按需开启前台保活通知、电池优化白名单、自启动权限等
5. 建议同时启用无障碍保活服务和周期性 Worker，构建多层保活

### 小组件配置

1. 长按桌面小组件 → 编辑 → 进入「小组件设置」
2. 调整显示项开关（流量/温度/型号/信号/电池/CPU/内存/时间）
3. 选择小组件主题和配色
4. 可选：设置自定义背景图片、调整透明度

## 🔧 工作原理

```
┌─────────────────────────────────────────────────────────────────┐
│                      Android 客户端                              │
│                                                                 │
│  ┌─────────────┐   ┌────────────────┐   ┌───────────────────┐  │
│  │ MainActivity│   │ WifiWidget 4×2 │   │ NotificationHelper│  │
│  │ (仪表盘)     │   │ (桌面小组件)    │   │ (通知/警报/防抖)   │  │
│  └──────┬──────┘   └───────┬────────┘   └────────┬──────────┘  │
│         │                  │                     │              │
│         ▼                  ▼                     ▼              │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    数据采集引擎                           │    │
│  │                                                         │    │
│  │  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐   │    │
│  │  │NotificationM│  │AlarmReceiver │  │ WifiWorker   │   │    │
│  │  │onitor(协程) │  │(setAlarmClock│  │(WorkManager) │   │    │
│  │  │ 15-600s     │  │ Doze穿透)    │  │ 1-1440min    │   │    │
│  │  │ 轻量单请求   │  │ 5min+       │  │ 完整采集      │   │    │
│  │  └──────┬──────┘  └──────┬───────┘  └──────┬───────┘   │    │
│  │         └────────────────┼──────────────────┘           │    │
│  │                          ▼                              │    │
│  │                    WifiCrawl                             │    │
│  │         /api/baseDeviceInfo (串行+冷却)                  │    │
│  │         Goform 信号/身份 + AT 指令 (并发)                │    │
│  │         HMAC-MD5+SHA256 签名认证                         │    │
│  └─────────────────────────┬───────────────────────────────┘    │
│                            │                                    │
│  ┌─────────────────────────┼───────────────────────────────┐    │
│  │ 持久化层               │                                │    │
│  │  ├─ SharedPreferences → 配置缓存 + 小组件渲染           │    │
│  │  ├─ Room DB (AlertDao) → 警报历史持久化                 │    │
│  │  ├─ Room DB (TrafficDao) → 流量历史记录（每日/每小时）   │    │
│  │  └─ WidgetBitmapCache → 小组件位图缓存                  │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ 后台保活层                                               │    │
│  │  ├─ BackgroundMonitorService (前台服务+WakeLock)        │    │
│  │  ├─ KeepAliveAccessibilityService (无障碍保活)           │    │
│  │  ├─ KeepAlivePeriodicWorker (WorkManager保活)           │    │
│  │  ├─ BootReceiver (开机自启)                              │    │
│  │  └─ BatteryOptimizationHelper (电池优化白名单)           │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                            │
                   HTTP API (REST)
                   HMAC-MD5+SHA256
                      签名鉴权
                            │
                            ▼
                ┌─────────────────────┐
                │  UFI 随身 WiFi 设备   │
                │  (默认 192.168.0.1)   │
                │                     │
                │  /api/baseDeviceInfo │
                │  /api/goform/...    │
                │  /api/AT            │
                │  /api/version_info  │
                └─────────────────────┘
```

## 🏗️ 项目结构

```
UFITOOLSWidget/
├── app/src/main/java/com/ufi_toolswidget/
│   ├── MainActivity.kt                  # 主界面仪表盘（卡片布局 + 详情弹窗 + 入场/加载动画）
│   ├── MainViewModel.kt                 # 主界面 ViewModel（数据刷新 + 缓存恢复 + 失败计数）
│   ├── SetupActivity.kt                 # 首次配置向导（地址 + 口令 + 协议探测）
│   ├── SettingsActivity.kt              # 设置主页（6 个导航卡片）
│   ├── AppSettingsActivity.kt           # 应用设置（主题/配色/刷新频率）
│   ├── NotificationSettingsActivity.kt  # 通知管理（7 种警报开关/阈值/监控间隔/保活入口）
│   ├── AlertHistoryActivity.kt          # 警报历史（分页/筛选/滑动操作/Room 持久化）
│   ├── AlertHistoryViewModel.kt         # 警报历史 ViewModel
│   ├── AlertItemAdapter.kt              # 警报列表 RecyclerView 适配器
│   ├── TrafficHistoryActivity.kt        # 流量历史（每日/每小时/月度三视图 + 分页 + 增量计算）
│   ├── BackgroundKeepAliveActivity.kt   # 后台保活配置（8 项保活措施）
│   ├── ConfigModifyActivity.kt          # 连接配置修改（基础 + 高级 API 路径/密钥/平台）
│   ├── WidgetSettingsActivity.kt        # 小组件设置（显隐/主题/配色/背景/透明度）
│   ├── WidgetDynamicColorActivity.kt    # 动态配色（Android 12+ Material You）
│   ├── AboutActivity.kt                 # 关于（更新检查/双源切换/赞赏/调试模式入口）
│   ├── DebugLogActivity.kt              # 调试日志（分类查看/全量诊断/崩溃模式/分享）
│   ├── AddWidgetActivity.kt             # 小组件钉选代理
│   ├── ImageCropActivity.kt             # 背景图片裁切（SimpleCropView）
│   ├── ExperimentalFeaturesActivity.kt  # 实验功能入口（动态取色）
│   ├── UfiToolsApplication.kt           # Application 初始化（日志/数据库/通知/服务/闹钟）
│   ├── WidgetAddedReceiver.kt           # 小组件添加广播
│   ├── db/
│   │   ├── AppDatabase.kt               # Room 数据库（单例，版本管理，v4）
│   │   ├── AlertDao.kt                  # 警报 DAO（分页查询/筛选/标记/删除/统计）
│   │   ├── AlertRecord.kt               # 警报记录实体（type/title/message/timestamp/read）
│   │   ├── TrafficDao.kt                # 流量 DAO（每日/每小时/月度聚合查询/分页/清理）
│   │   └── TrafficRecord.kt             # 流量记录实体（dateKey/dailyRaw/monthlyRaw/recordType）
│   ├── service/
│   │   ├── BackgroundMonitorService.kt  # 前台保活服务（WakeLock + 自定义通知 + STICKY）
│   │   ├── AlarmReceiver.kt             # Doze 穿透闹钟（setAlarmClock + 三级降级 + 链式调度）
│   │   ├── BootReceiver.kt              # 开机自启（恢复服务/闹钟/Worker）
│   │   └── KeepAliveAccessibilityService.kt  # 无障碍保活（仅注册，不处理事件）
│   ├── widget/
│   │   └── WifiWidget.kt                # 桌面小组件（4×2/2×1/4×1/4×4 + 渲染去重 + 数据指纹）
│   ├── worker/
│   │   ├── WifiWorker.kt                # 后台数据刷新（TCP ping + 两级失败计数 + retry 自愈）
│   │   └── KeepAlivePeriodicWorker.kt   # 保活补充 Worker（轻量通知检查）
│   ├── ui/                              # UI 组件
│   ├── view/                            # 自定义 View
│   └── util/
│       ├── WifiCrawl.kt                 # 数据采集核心（两阶段采集 + 13 路 AT 并发 + 平台自适应）
│       ├── NetUtil.kt                   # OkHttp 客户端 + Kano 签名算法 + Cookie 管理
│       ├── SPUtil.kt                    # SharedPreferences 统一管理（100+ 配置项）
│       ├── UpdateChecker.kt             # 应用更新（version.json + 双源 + SHA256 + DownloadManager）
│       ├── ThemeColors.kt               # 5 套预设 + 自定义配色主题
│       ├── ThemeUtil.kt                 # 主题动态应用到控件（递归遍历 + 跳过条件）
│       ├── ThemeChangeNotifier.kt       # 主题变更广播通知
│       ├── ThemedSliderUtil.kt          # 主题化滑块组件
│       ├── NotificationHelper.kt        # 通知渠道 + 7 种警报触发 + 防抖 + 渠道检测
│       ├── NotificationMonitor.kt       # 通知监控协程（无限循环 + 一次性检查）
│       ├── AlertHistoryManager.kt       # 警报历史管理（Room + 写锁串行化 + 自动清理）
│       ├── TrafficRecordManager.kt      # 流量记录管理（每日/每小时记录 + 366 条上限 + 自动清理）
│       ├── BatteryOptimizationHelper.kt # 电池优化白名单 + 6 大 ROM 自启动引导
│       ├── DebugLogger.kt               # 调试日志（800 条内存 + 文件持久化 + 脱敏 + 诊断报告）
│       ├── CrashHandler.kt              # 崩溃捕获（独立进程 + 信息记录）
│       ├── AnimationUtil.kt             # 动画工具（模糊、弹窗、点击反馈、揭露脉冲）
│       ├── BackgroundUtil.kt            # 窗口背景管理
│       ├── ToastUtil.kt                 # Toast 工具（普通/警告/确认弹窗/下落动画）
│       ├── CommonDialogHelper.kt        # 通用对话框（输入/选择/确认）
│       ├── MainDialogHelper.kt          # 主界面详情弹窗（12 种数据卡片弹窗）
│       ├── PaginationBarHelper.kt       # 分页栏组件（首页/上下页/末页/跳转）
│       ├── PopupViewUtil.kt             # 弹窗工具
│       ├── WidgetBitmapCache.kt         # 小组件位图缓存（纯色/图片分离链路）
│       ├── SimpleCropView.kt            # 图片裁切视图（触摸拖拽/缩放）
│       ├── ScaleTouchListener.kt        # 触控缩放反馈
│       └── CommonSettingsItemHelper.kt  # 设置项辅助
└── .github/workflows/build.yml          # CI/CD 自动构建与发布
```

## 🛠️ 技术栈

- **语言**：Kotlin
- **HTTP 客户端**：OkHttp 4（15s 连接/读/写超时 + 20s 总超时 + 自定义 CookieJar）
- **后台任务**：AndroidX WorkManager（CoroutineWorker）
- **数据库**：Room（警报历史持久化）+ Paging 3（分页加载）
- **UI**：AndroidX + Material Design + CardView
- **构建**：Gradle + AGP 8.7.3 + KSP
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
