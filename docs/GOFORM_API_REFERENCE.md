# Goform API 参考文档

> **设备型号**: MU300 (MU300HW1.0)  
> **固件版本**: F50_FLYMODEM_ZYV1.0.0B13  
> **探测日期**: 2026-06-05  
> **API 端点**: `GET /api/goform/goform_get_cmd_process?is_all=true&cmd=xxx,yyy`

---

## 一、已验证可用命令（15个）

### 1. 信号参数

| 命令 | 示例值 | 说明 |
|------|--------|------|
| `Z5g_rsrp` | `-83` | 5G NR RSRP (Reference Signal Received Power)，单位 dBm，范围 -140 ~ -44 |
| `rssi` | `5` | 接收信号强度指示，此设备取值非 dBm（可能为 ASU 或自定义等级） |
| `network_type` | `5G` | 当前网络制式，可能值: `5G` / `LTE` / `WCDMA` / `GSM` / `No Service` |

**后续可参考**：结合 AT 命令 `AT+QENG` 可获取更详细信号（SINR/RSRQ/频段/PCI/CellID）。

### 2. 网络地址

| 命令 | 示例值 | 说明 |
|------|--------|------|
| `wan_ipaddr` | `10.4.130.159` | WAN 口 IPv4 地址（运营商分配的内网 IP，说明在 NAT 后面） |
| `ipv6_wan_ipaddr` | `2408:8459:6ab0:bab:...` | WAN 口 IPv6 地址（公网可达） |
| `pdp_type` | `IPv4v6` | PDP/承载类型，可能值: `IPv4` / `IPv6` / `IPv4v6` |

### 3. 设备标识

| 命令 | 示例值 | 说明 |
|------|--------|------|
| `imei` | `867306060791251` | 国际移动设备识别码（15位），设备唯一标识 |
| `imsi` | `460019316913679` | 国际移动用户识别码（15位），`46001` = 中国联通 |
| `iccid` | `89860124801702456586` | SIM 卡集成电路卡标识（20位），`898601` = 中国联通 |
| `hardware_version` | `MU300HW1.0` | 硬件版本号 |
| `web_version` | `F50_FLYMODEM_ZYV1.0.0B13` | Web/固件版本号 |
| `mac_address` | `54:1f:8d:2a:ed:92` | 设备 MAC 地址（WiFi AP 侧） |

**后续可参考**：
- `imei` → 可在 App 关于页面/设备详情中展示
- `imsi` / `iccid` → 运营商识别，可用于流量套餐匹配
- `web_version` → 固件升级检测

### 4. 月流量统计

| 命令 | 示例值 | 说明 |
|------|--------|------|
| `monthly_tx_bytes` | `32235493528` | 当月上行流量（字节），约 **30.0 GB** |
| `monthly_rx_bytes` | `3047789386` | 当月下行流量（字节），约 **2.8 GB** |

**后续可参考**：
- 当前 App 已通过 `monthly_data` 对象获取月流量，但命令返回空对象
- **直接使用 `monthly_tx_bytes` + `monthly_rx_bytes`** 这两个字段更可靠
- 可能存在对应的 `daily_tx_bytes` / `daily_rx_bytes`（本次探测未命中，需单独验证）

### 5. SIM 状态

| 命令 | 示例值 | 说明 |
|------|--------|------|
| `pin_status` | `0` | SIM PIN 码状态。`0` = PIN 已解锁或未设置，`1` = 需要输入 PIN，`2` = PUK 锁定 |

---

## 二、探测未命中命令（此设备不支持或需特殊参数）

以下命令探测返回空值，**不代表所有 MiFi 设备都不支持**，仅说明此 MU300 固件不暴露：

| 分类 | 命令 | 备注 |
|------|------|------|
| 信号 | `lte_rsrp`, `lte_rsrq`, `lte_sinr`, `lte_rssi`, `lte_cqi` | LTE 信号详情，5G 在线时无 LTE 锚点数据 |
| 信号 | `Z5g_rsrq`, `Z5g_sinr` | 5G 信号补充参数 |
| 信号 | `cell_id`, `pci`, `tac`, `earfcn`, `nrarfcn` | 小区/频点信息，可能需 AT 命令获取 |
| 频段 | `lte_band`, `nr_band`, `lte_ca_*` | 频段/CA 信息 |
| WiFi | `wifi_ssid`, `wifi_password`, `wifi_channel`, `wifi_*` | WiFi 配置，可能需 `goform_set_cmd_process` 读写 |
| 设备 | `model`, `software_version`, `device_name` | 被 `hardware_version` / `web_version` 替代 |
| 流量 | `monthly_data`, `daily_data`, `total_data`, `daily_*`, `current_*` | 月流量用独立的 `monthly_tx/rx_bytes`；日流量需验证 |
| 电池 | `battery_*` | MU300 为 USB 供电 CPE，可能无电池 |
| SIM | `sim_state`, `puk_times`, `sms_state`, `sms_count` | SIM/SMS 状态 |
| 网络 | `dns`, `apn`, `network_select`, `data_roaming`, `network_mode` | DNS/APN/模式配置 |
| 其他 | `uptime`, `connection_time`, `connected_devices`, `wifi_users` | 运行时间/连接设备数 |
| 其他 | `cpu_usage`, `mem_usage`, `sd_*`, `web_language`, `timezone`, `ntp_server` | 系统参数 |
| 锁频 | `lte_lock`, `nr_lock` | 频段锁 |

---

## 三、API 端点总览

| 端点 | 方法 | 认证 | 用途 |
|------|------|------|------|
| `/api/baseDeviceInfo` | GET | ✅ | 设备全量信息（主数据源） |
| `/api/goform/goform_get_cmd_process` | GET | ✅ | Goform 读命令（本文档焦点） |
| `/api/goform/goform_set_cmd_process` | POST | ✅ | Goform 写命令（配置修改/重启等） |
| `/api/AT` | GET | ✅ | AT 命令代理（信号详情/IMEI/QoS） |
| `/api/version_info` | GET | ❌ | 设备型号/固件版本 |
| `/api/need_token` | GET | ❌ | 是否需要鉴权 |

---

## 四、App 集成建议

### 已有数据源对比

| 数据 | 当前来源 | Goform 可替代/补充 |
|------|----------|---------------------|
| 信号 RSRP | `goform Z5g_rsrp` | 已在使用 |
| 网络类型 | `goform network_type` | 已在使用 |
| 月流量 | `baseDeviceInfo` → `goform monthly_data` | **建议改用 `goform monthly_tx/rx_bytes`**，更可靠 |
| IMEI | AT `AT+CGSN` | `goform imei` 更直接 |
| 固件版本 | `/api/version_info` | `goform web_version` |
| 硬件型号 | `/api/version_info` → model | `goform hardware_version` |
| 运营商 | 从 IMSI 前5位推断 | `goform imsi` |
| WAN IP | 暂无 | `goform wan_ipaddr` / `ipv6_wan_ipaddr` |
| PIN 状态 | 暂无 | `goform pin_status` |

### 优化方向

1. **月流量改用 `monthly_tx_bytes` + `monthly_rx_bytes`**  
   当前 `monthly_data` 对象返回空，但这两个独立字段有值。需修改 `extractTrafficBytes()` 或新增解析逻辑。

2. **新增 WAN IP 显示**  
   `wan_ipaddr` / `ipv6_wan_ipaddr` 可在 App 中展示，方便判断是否获取到公网地址。

3. **将 `imei`/`imsi`/`iccid` 纳入设备详情页**  
   当前 App 只在 AT 响应中解析 IMEI，Goform 直出更简洁。

4. **`pin_status` 预警**  
   SIM PIN 锁定时可弹提示。

5. **写操作（goform_set_cmd_process）谨慎使用**  
   重启、改 WiFi 配置等功能涉及写操作，需在 UI 上加二次确认，避免误操作。

---

## 五、一键验证命令

```bash
# 基础验证
node scripts/check_goform.js

# 探测所有命令
node scripts/goform_explore.js

# 自定义命令组合
node scripts/goform_explore.js --cmd "wan_ipaddr,imei,monthly_tx_bytes,monthly_rx_bytes,pin_status"
```
