# AT 命令参考文档

> **设备型号**: MU300 (MU300HW1.0)  
> **芯片平台**: 展讯 Spreadtrum (MOCORTM_V2_22C_W25.39.4)  
> **固件版本**: QogirN6Pro_PSBASE 5G  
> **探测日期**: 2026-06-05  
> **API 端点**: `GET /api/AT?command=<AT命令>&slot=0`

---

## 一、已验证可用命令（24/46）

### 1. 设备标识

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+CGMI` | `Spreadtrum Communication CO.` | 制造商名称（展讯通信） |
| `AT+CGMM` | `V1.0.1-B7` | 模块型号/基带版本 |
| `AT+CGMR` | `Platform Version: MOCORTM_V2_22C_W25.39.4_Debug`<br>`Project Version: QogirN6Pro_PSBASE 5G` | 平台+固件详细版本 |
| `AT+CGSN` | `867306060791251` | IMEI（15位），与 Goform `imei` 值一致 |

### 2. SIM 卡

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+CIMI` | `460019316913679` | IMSI（15位），`46001`=中国联通 |
| `AT+CRSM=176,12258,0,0,10` | `98681042087120545668` | ICCID（标准 SIM 文件读取），`898601`=中国联通 |
| `AT+CPIN?` | `+CPIN: READY` | PIN 状态，`READY`=已解锁无需输入 PIN |

### 3. 网络注册状态

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+COPS?` | `+COPS: 0,2,"46001",11` | 运营商查询：`0`=自动注册，`"46001"`=中国联通，`11`=E-UTRAN/NR |
| `AT+CREG?` | `%+CREG: 2,8,"0000","050C6180",11` | 网络注册：`2`=禁用URC，`8`=已注册归属网络，LAC+CI，`11`=LTE |
| `AT+CEREG?` | `+CEREG: 2,1,"835500","050C6180",11` | EPS 注册：`1`=已注册归属网络，TAC=`835500`（hex），CI=`050C6180`（hex） |
| `AT+C5GREG?` | `+C5GREG: 2,1,"835500","8350C6180",11,9,01` | 5G NR 注册：`1`=已注册，TAC 同上，NR-CI=`8350C6180`（hex），`9`=NSA模式 |
| `AT+CGREG?` | `+CGREG: 2,1,"0000","050C6180",11` | GPRS 注册：同 CREG |

**关键信息提取**：
- `C5GREG` act=11, mode=9 → **5G NSA（非独立组网）模式**
- `CEREG`/`C5GREG` stat=1 表示已成功注册归属网络
- TAC = `0x835500` = 8606976，NR Cell ID = `0x8350C6180`

### 4. 信号强度

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+CSQ` | `+CSQ: 99,99` | 基础信号：RSSI=99（无意义/不支持），BER=99 |
| `AT+CESQ` | `+CESQ: 99,99,255,255,255,255,75,68,80` | 扩展信号质量 |

**CESQ 字段解析**：
| 索引 | 值 | 含义 |
|------|-----|------|
| 0-1 | 99,99 | RSSI/BER（无意义） |
| 2-5 | 255,255,255,255 | RSCP/EcNo（WCDMA，未使用=255） |
| 6 | **75** | **LTE RSRP** = (75 × 2) - 256 + 75/2 ≈ **-106 dBm** |
| 7 | **68** | **LTE RSRQ** = (68 × 2) - 256 + 68/2 ≈ **-120 dBm**（5G NSA 下 RSRQ 不适合） |
| 8 | **80** | **LTE SINR** = (80 × 2) - 256 + 80/2 ≈ **-96 dB**（5G NSA 下的参考 SINR） |

> ⚠️ **注意**：`AT+CESQ` 返回的是 LTE 锚点的信号，在 5G NSA 模式下，这些值不代表 5G NR 真实信号。5G 信号请用 Goform `Z5g_rsrp` 或尝试其他 NR 信号 AT 命令。

### 5. PDP / APN / 数据传输

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+CGDCONT?` | `+CGDCONT:1,IPV4V6,"3gnet",...`<br>`+CGDCONT:11,IPV4V6,"ims",...` | PDP 上下文定义：**cid=1** 为数据 APN `3gnet`（联通），**cid=11** 为 IMS APN |
| `AT+CGACT?` | `+CGACT:1,1`<br>`+CGACT:11,1` | PDP 激活状态：cid 1 和 11 均已激活（`1`=active） |
| `AT+CGCONTRDP=1` | IP=`10.4.130.159`, DNS=`120.80.80.80`,`221.5.88.88` | PDP 上下文详情：当前已分配 IPv4 地址及 DNS 服务器 |
| `AT+CGEQOSRDP=1` | **下行 500000 kbps, 上行 100000 kbps** | **运营商签约速率**：下行 **500 Mbps**，上行 **100 Mbps** |

**关键信息**：
- CGEQOSRDP 下行 500Mbps / 上行 100Mbps → **运营商签约带宽上限**，可用于网速展示封顶参考
- CGCONTRDP 返回的 DNS `120.80.80.80` / `221.5.88.88` 是运营商下发的 DNS 服务器

### 6. 模块状态

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+CFUN?` | `+CFUN: 1` | 模块功能状态：`1`=全功能模式（射频开启） |
| `AT+CPAS` | `+CPAS: 0` | 模块活动状态：`0`=就绪（可接受 AT 命令） |
| `AT+CGATT?` | `+CGATT: 1` | PS 域附着状态：`1`=已附着（数据连接可用） |
| `AT+GCAP` | `+GCAP: +CGSM` | 模块能力列表（仅报告基础 GSM 命令集） |

### 7. SMS 短信

| 命令 | 响应 | 说明 |
|------|------|------|
| `AT+CPMS?` | `+CPMS: "SM",0,50,"SM",0,50,"SM",0,50` | 短信存储：SIM 卡容量 50 条，当前 0 条 |
| `AT+CNMI?` | `+CNMI: 3,2,1,1,1` | 新短信通知模式：`3`=直接显示到 TE，`2`=带存储索引 |

---

## 二、不支持的命令（22/46）

这些命令均为 **Quectel 私有 AT 扩展**（`AT+Q*` 系列），展讯平台不支持：

| 命令 | 分类 | 说明 |
|------|------|------|
| `AT+QCFG="imei"` | 设备标识 | Quectel 私有 IMEI 读取，展讯用 `AT+CGSN` |
| `AT+QCCID` | SIM 卡 | Quectel 私有 ICCID，展讯用 `AT+CRSM` |
| `AT+QSIMSTAT?` | SIM 卡 | Quectel SIM 检测状态 |
| `AT+QSIMDET?` | SIM 卡 | Quectel SIM 热插拔配置 |
| `AT+QRSRP` | 信号 | Quectel 私有 RSRP 查询 |
| `AT+QENG="servingcell"` | 信号 | **Quectel 服务小区详情**（App 当前使用，此设备无效！） |
| `AT+QENG="neighbourcell"` | 信号 | Quectel 邻区列表 |
| `AT+QNWINFO` | 网络 | Quectel 网络信息 |
| `AT+QNETINFO` | 网络 | Quectel RG 网络信息 |
| `AT+QSPN` | 网络 | Quectel 运营商名称 |
| `AT+QNWCFG?` | 网络 | Quectel 网络模式配置 |
| `AT+QCAINFO` | 频段/CA | Quectel 载波聚合信息 |
| `AT+QBAND?` | 频段/CA | Quectel 频段配置 |
| `AT+QLTEFREQLOCK?` | 频段/CA | Quectel LTE 锁频配置 |
| `AT+QNWLOCK="common/5g"` | 频段/CA | Quectel 5G 锁频/锁小区 |
| `AT+QICSGP?` | APN | Quectel 内部 APN 信息 |
| `AT+QGDATAVOL?` | 流量 | Quectel 数据流量统计 |
| `AT+QGDCN?` | 流量 | Quectel PDP IP 详情 |
| `AT+QTEMP` | 状态 | Quectel 模块温度 |
| `AT+QADC?` | 状态 | Quectel ADC 读取 |
| `AT+QSYSINFO` | 状态 | Quectel 系统信息 |
| `ATI` | 版本 | 完整设备信息（展讯平台不响应多行格式） |

> 🔑 **关键发现**：本设备为**展讯平台**（非 Quectel），App 当前使用的 `AT+QENG="servingcell"` 在此设备上**不会返回有效数据**！需要修改 `fetchAtNetworkInfo()` 以适配展讯 AT 命令集。

---

## 三、AT 响应解析指南

### 3.1 标准响应格式

展讯平台 AT 响应格式（echo 模式关，默认 `ATE0`）：

```
[命令回声（可能无）]
+RESPONSE_NAME: <参数>
OK
```

或错误时：
```
+CME ERROR: <error_code>
```

### 3.2 关键值解析

#### 网络注册状态码

| 值 | 含义 |
|----|------|
| 0 | 未注册，不在搜索 |
| 1 | 已注册归属网络 |
| 2 | 未注册，正在搜索 |
| 3 | 注册被拒绝 |
| 4 | 未知 |
| 5 | 已注册漫游 |
| 8 | 已注册归属网络（仅 PS） |

#### 接入技术 (ACT) 值

| 值 | 制式 |
|----|------|
| 0 | GSM |
| 2 | UTRAN (3G) |
| 7 | E-UTRAN (LTE) |
| 9 | E-UTRAN + NR (NSA) |
| 11 | NR (SA) / E-UTRAN + NR |

#### CPIN PIN 状态

| 值 | 说明 |
|----|------|
| `READY` | PIN 已解锁 |
| `SIM PIN` | 需要输入 PIN |
| `SIM PUK` | PIN 锁定，需要 PUK |
| `SIM PIN2` | 需要 PIN2（特殊功能） |

---

## 四、App 适配建议（展讯平台）

### 4.1 当前问题

`WifiCrawl.kt` 的 `fetchAtNetworkInfo()` 中硬编码了 Quectel 命令：
```kotlin
val qengCmd = URLEncoder.encode("AT+QENG=\"servingcell\"", "UTF-8")  // ❌ 展讯不支持
```

在展讯设备上此命令返回空，导致 `atNetworkInfo` 缺失信号详情。

### 4.2 替换方案

| 当前 Quectel 命令 | 展讯替代命令 | 获取内容 |
|-------------------|-------------|----------|
| `AT+QENG="servingcell"` | `AT+CESQ` | RSRP/RSRQ/SINR（已有） |
| `AT+CGSN` | `AT+CGSN` | IMEI（通用，保留） |
| `AT+COPS?` | `AT+COPS?` | 运营商（通用，保留） |
| `AT+CGEQOSRDP=1` | `AT+CGEQOSRDP=1` | 签约速率（通用，保留） |
| — | **`AT+CREG?`** | 网络注册+小区ID+ACT |
| — | **`AT+C5GREG?`** | 5G 注册状态+NR Cell ID+NSA/SA 判定 |
| — | **`AT+CGCONTRDP=1`** | WAN IP + DNS |

### 4.3 建议的 App 修改方向

1. **检测芯片平台**：首次获取 `AT+CGMI` 判断是 "Quectel" 还是 "Spreadtrum"，分流命令集
2. **用 `AT+C5GREG?` 替代 `AT+QENG`**：获取 5G 注册状态（SA/NSA 判断）和 NR Cell ID
3. **用 `AT+CESQ` 补充信号**：虽然只有 LTE 锚点数据，但比无数据好
4. **用 `AT+CGEQOSRDP=1` 展示签约速率**：可在 App 中显示"签约带宽 500M↓/100M↑"
5. **用 `AT+CGCONTRDP=1` 获取 WAN IP**：与 Goform `wan_ipaddr` 互为校验

---

## 五、一键验证

```bash
# 批量探测全部 AT 命令（在 scripts 目录下）
node check_at.js

# 单条自定义命令
node check_at.js --cmd "AT+CESQ"

# 显示原始响应文本
node check_at.js --raw

# 指定设备地址+口令
node check_at.js 192.168.0.1 2333 admin
```

---

## 六、与 Goform 数据互补关系

| 数据 | Goform | AT 命令 | 推荐来源 |
|------|--------|---------|----------|
| IMEI | `imei` ✅ | `AT+CGSN` ✅ | Goform（更简洁） |
| IMSI | `imsi` ✅ | `AT+CIMI` ✅ | Goform |
| ICCID | `iccid` ✅ | `AT+CRSM` ✅ | Goform（无需解析 hex） |
| 5G RSRP | `Z5g_rsrp` ✅ | — | Goform（唯一来源） |
| 网络类型 | `network_type` ✅ | 从 `C5GREG` act 推断 | Goform |
| 运营商 | 从 IMSI 推断 | `AT+COPS?` ✅ | AT 更直接 |
| WAN IP | `wan_ipaddr` ✅ | `AT+CGCONTRDP=1` ✅ | 两者一致即可信 |
| 签约速率 | — | `AT+CGEQOSRDP=1` ✅ | AT（Goform 无此数据） |
| 5G 模式(SA/NSA) | — | `AT+C5GREG?` ✅ | AT（Goform 无此数据） |
| PIN 状态 | `pin_status` ✅ | `AT+CPIN?` ✅ | Goform |
| 短信状态 | — | `AT+CPMS?` ✅ | AT |
| RSSI | `rssi` ✅ | `AT+CSQ` ❌ | Goform（CSQ 返回 99 无意义） |
