/**
 * AT 命令探测脚本 — 批量验证设备 AT 命令支持情况
 *
 * 用法：
 *   node check_at.js                              # 探测所有已知 AT 命令
 *   node check_at.js 192.168.0.1 2333 admin        # 指定地址+口令
 *   node check_at.js --cmd "AT+QENG=\"servingcell\""  # 单条自定义命令
 *   node check_at.js --raw                         # 显示原始 AT 响应文本
 *
 * 认证逻辑与 App 端完全一致（kano-t + kano-sign + Authorization）
 */

const crypto = require('crypto');
const http = require('http');

// ============ 默认值 ============
const DEFAULT_HOST = '192.168.0.1';
const DEFAULT_PORT = 2333;
const DEFAULT_PASSWORD = 'admin';
const AT_PATH = '/api/AT';
const SECRET_KEY = 'minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd';

// ============ 已知 AT 命令列表 ============
const AT_COMMANDS = [
    // ── 设备标识 ──
    { cmd: 'AT+CGMI',                         desc: '制造商名称' },
    { cmd: 'AT+CGMM',                         desc: '模块型号' },
    { cmd: 'AT+CGMR',                         desc: '固件版本' },
    { cmd: 'AT+CGSN',                         desc: 'IMEI 序列号' },
    { cmd: 'AT+QCFG="imei"',                  desc: 'IMEI（Quectel 方式）' },

    // ── SIM 卡 ──
    { cmd: 'AT+CIMI',                         desc: 'IMSI' },
    { cmd: 'AT+QCCID',                        desc: 'ICCID (Quectel)' },
    { cmd: 'AT+CRSM=176,12258,0,0,10',        desc: 'ICCID (标准 SIM 读取)' },
    { cmd: 'AT+CPIN?',                        desc: 'PIN 状态' },
    { cmd: 'AT+QSIMSTAT?',                    desc: 'SIM 卡检测状态' },           // 0=removed, 1=inserted
    { cmd: 'AT+QSIMDET?',                     desc: 'SIM 热插拔配置' },

    // ── 网络注册 ──
    { cmd: 'AT+COPS?',                        desc: '运营商信息（格式+名称+ACT）' },
    { cmd: 'AT+CREG?',                        desc: '网络注册状态（LTE/5G）' },
    { cmd: 'AT+CEREG?',                       desc: 'EPS 注册状态' },
    { cmd: 'AT+C5GREG?',                      desc: '5G 注册状态' },
    { cmd: 'AT+CGREG?',                       desc: 'GPRS 注册状态' },

    // ── 信号强度 ──
    { cmd: 'AT+CSQ',                          desc: '信号强度（RSSI/BER 基础）' },
    { cmd: 'AT+CESQ',                         desc: '扩展信号质量（RSRP/RSRQ）' },
    { cmd: 'AT+QRSRP',                        desc: 'RSRP 单独查询 (Quectel)' },
    { cmd: 'AT+QENG="servingcell"',           desc: '服务小区详情（频段/PCI/RSRP/SINR/RSRQ）' },
    { cmd: 'AT+QENG="neighbourcell"',         desc: '邻区列表' },

    // ── 网络信息 ──
    { cmd: 'AT+QNWINFO',                      desc: '当前网络信息（制式/频段/带宽）' },
    { cmd: 'AT+QNETINFO',                     desc: '网络信息 (Quectel RG)' },
    { cmd: 'AT+QSPN',                         desc: '运营商名称 (SPN)' },
    { cmd: 'AT+QNWCFG?',                      desc: '网络模式配置' },

    // ── 频段/CA ──
    { cmd: 'AT+QCAINFO',                      desc: '载波聚合 CA 信息' },
    { cmd: 'AT+QBAND?',                       desc: '已配置频段' },
    { cmd: 'AT+QLTEFREQLOCK?',                desc: 'LTE 锁频配置' },
    { cmd: 'AT+QNWLOCK="common/5g"',           desc: '5G 锁频/锁小区' },

    // ── PDP/APN ──
    { cmd: 'AT+CGDCONT?',                     desc: 'PDP 上下文 / APN 配置' },
    { cmd: 'AT+QICSGP?',                      desc: 'APN 信息 (Quectel 内部)' },
    { cmd: 'AT+CGACT?',                       desc: 'PDP 激活状态' },
    { cmd: 'AT+CGCONTRDP=1',                  desc: 'PDP 上下文详情（IP/DNS/流量）' },

    // ── 签约速率 ──
    { cmd: 'AT+CGEQOSRDP=1',                  desc: '签约 QoS 速率（上下行 kbps）' },

    // ── 数据传输 ──
    { cmd: 'AT+QGDATAVOL?',                   desc: '数据流量统计' },
    { cmd: 'AT+QGDCN?',                       desc: 'PDP 上下文 IP 详情' },

    // ── 状态/配置 ──
    { cmd: 'AT+CFUN?',                        desc: '模块功能状态' },
    { cmd: 'AT+CPAS',                         desc: '模块活动状态' },
    { cmd: 'AT+CGATT?',                       desc: 'PS 域附着状态' },
    { cmd: 'AT+QTEMP',                        desc: '模块温度' },
    { cmd: 'AT+QADC?',                        desc: 'ADC 读取（电压等）' },
    { cmd: 'AT+QSYSINFO',                     desc: '系统信息（温度/电压等）' },

    // ── 版本/能力 ──
    { cmd: 'ATI',                             desc: '设备完整信息（多行）' },
    { cmd: 'AT+GCAP',                         desc: '模块能力列表' },

    // ── SMS ──
    { cmd: 'AT+CPMS?',                        desc: '短信存储状态' },
    { cmd: 'AT+CNMI?',                        desc: '新短信通知配置' },
];

// ============ 签名算法（与 goform 脚本一致） ============
function generateKanoSign(method, path, timestamp) {
    const rawData = `minikano${method.toUpperCase()}${path}${timestamp}`;
    const hmac = crypto.createHmac('md5', SECRET_KEY);
    hmac.update(rawData);
    const hmacBytes = hmac.digest();
    const part1 = hmacBytes.subarray(0, 8);
    const part2 = hmacBytes.subarray(8, 16);
    const sha1 = crypto.createHash('sha256').update(part1).digest('hex');
    const sha2 = crypto.createHash('sha256').update(part2).digest('hex');
    const combined = Buffer.from(sha1 + sha2, 'hex');
    return crypto.createHash('sha256').update(combined).digest('hex');
}

// ============ HTTP 请求 ============
function fetchAt(host, port, atCmd, slot, authHeader) {
    return new Promise((resolve, reject) => {
        const encoded = encodeURIComponent(atCmd);
        const fullPath = `${AT_PATH}?command=${encoded}&slot=${slot}`;
        const t = Date.now();
        const sign = generateKanoSign('GET', AT_PATH, t);

        const opts = {
            hostname: host, port,
            path: fullPath,
            method: 'GET',
            timeout: 15000,
            headers: {
                'kano-t': String(t),
                'kano-sign': sign,
                'Accept': 'application/json',
                'Authorization': authHeader,
            },
        };

        const req = http.request(opts, (res) => {
            let body = '';
            res.on('data', (c) => { body += c; });
            res.on('end', () => {
                try {
                    const json = JSON.parse(body);
                    // 优先取 result 字段，没有则取 response
                    const raw = json.result || json.response || body;
                    resolve({
                        ok: res.statusCode === 200,
                        status: res.statusCode,
                        raw: typeof raw === 'string' ? raw : JSON.stringify(raw),
                    });
                } catch (e) {
                    resolve({ ok: false, status: res.statusCode, raw: body.substring(0, 500) });
                }
            });
        });
        req.on('error', (e) => reject(e));
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        req.end();
    });
}

// ============ 解析 AT 响应（提取纯净数据行） ============
function parseAtResponse(raw) {
    if (!raw) return { ok: false, parsed: '' };

    // 去掉命令回声、OK/ERROR 结尾
    let cleaned = raw
        .replace(/\r\n/g, '\n').replace(/\r/g, '\n');

    // 移除 OK / ERROR 结尾行
    cleaned = cleaned.replace(/\nOK\n?$/i, '').replace(/\nERROR\n?$/i, '').trim();

    // 移除以 "AT" 开头的命令回声行
    const lines = cleaned.split('\n').filter(l => {
        const t = l.trim();
        if (t === '') return false;
        return true;
    });

    const dataLines = lines.filter(l => {
        const t = l.trim();
        return !t.startsWith('AT+') && !t.startsWith('ATI');
    });

    if (dataLines.length === 0 && lines.length > 0) {
        // 有些响应就一行，没有命令回声
        return { ok: true, parsed: lines.join(' | ') };
    }

    return { ok: dataLines.length > 0, parsed: dataLines.join(' | ') };
}

// ============ 主流程 ============
async function main() {
    const args = process.argv.slice(2);

    let mode = 'explore';
    let customCmd = null;
    let showRaw = false;
    let host = DEFAULT_HOST;
    let port = DEFAULT_PORT;
    let password = DEFAULT_PASSWORD;

    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--cmd') {
            mode = 'single';
            customCmd = args[++i] || 'AT+COPS?';
        } else if (args[i] === '--raw') {
            showRaw = true;
        } else if (i === 0 && !args[0].startsWith('--')) {
            host = args[i];
        } else if (i === 1) {
            port = parseInt(args[i]) || DEFAULT_PORT;
        } else if (i === 2) {
            password = args[i];
        }
    }

    const authHeader = crypto.createHash('sha256').update(password).digest('hex');

    console.log('========================================');
    console.log('  AT 命令探测');
    console.log('========================================');
    console.log(`  设备: ${host}:${port}    口令: ${password}`);
    console.log(`  API:  ${AT_PATH}?command=<AT命令>&slot=0`);
    console.log('');

    // ==================== 单条模式 ====================
    if (mode === 'single') {
        console.log(`命令: ${customCmd}\n`);
        try {
            const r = await fetchAt(host, port, customCmd, 0, authHeader);
            console.log(`HTTP ${r.status}`);
            if (showRaw) {
                console.log(`\n[原始响应]\n${r.raw}`);
            } else {
                const p = parseAtResponse(r.raw);
                console.log(`解析: ${p.parsed || '(空)'}\n`);
                console.log(`[原始]\n${r.raw}`);
            }
        } catch (e) {
            console.log(`❌ ${e.message}`);
        }
        console.log('');
        console.log('========================================');
        return;
    }

    // ==================== 批量探测 ====================
    console.log(`正在探测 ${AT_COMMANDS.length} 条 AT 命令...\n`);

    const results = [];
    for (const item of AT_COMMANDS) {
        process.stdout.write(`  [${String(results.length + 1).padStart(2, '0')}/${AT_COMMANDS.length}] ${item.cmd} ...`);

        try {
            const r = await fetchAt(host, port, item.cmd, 0, authHeader);
            if (r.ok && r.raw) {
                const p = parseAtResponse(r.raw);
                const hasData = p.ok && p.parsed !== '' && p.parsed !== 'ERROR'
                    && !p.parsed.startsWith('+CME ERROR')
                    && !p.parsed.startsWith('+CMS ERROR');
                results.push({
                    cmd: item.cmd,
                    desc: item.desc,
                    ok: hasData,
                    parsed: hasData ? p.parsed.substring(0, 100) : '(无数据)',
                    raw: r.raw.substring(0, 200),
                });
                console.log(hasData ? ' ✅' : ' ⚠️');
            } else {
                results.push({
                    cmd: item.cmd,
                    desc: item.desc,
                    ok: false,
                    parsed: `HTTP ${r.status}` + (r.raw ? ` ${r.raw.substring(0, 40)}` : ''),
                    raw: r.raw,
                });
                console.log(' ❌');
            }
        } catch (e) {
            results.push({ cmd: item.cmd, desc: item.desc, ok: false, parsed: e.message, raw: '' });
            console.log(' ❌');
        }
    }

    // ==================== 分类汇总 ====================
    const okCmds = results.filter(r => r.ok);
    const failCmds = results.filter(r => !r.ok);

    console.log(`\n========================================`);
    console.log(`  ✅ 有效命令 (${okCmds.length}/${AT_COMMANDS.length})`);
    console.log(`========================================`);
    if (okCmds.length === 0) {
        console.log('  (无)');
    } else {
        for (const r of okCmds) {
            console.log(`  [${r.cmd}] ${r.desc}`);
            console.log(`    → ${r.parsed}`);
            console.log('');
        }
    }

    console.log(`\n========================================`);
    console.log(`  ❌ 不支持/无响应 (${failCmds.length}/${AT_COMMANDS.length})`);
    console.log(`========================================`);
    if (failCmds.length === 0) {
        console.log('  (全部支持)');
    } else {
        for (const r of failCmds) {
            console.log(`  ${r.cmd} (${r.desc}) → ${r.parsed}`);
        }
    }

    if (showRaw) {
        console.log('\n\n========== 原始响应 ==========');
        for (const r of results) {
            console.log(`\n── ${r.cmd} ──`);
            console.log(r.raw);
        }
    }

    console.log('\n========================================');
    console.log('  批量探测: node check_at.js [host] [port] [pw]');
    console.log('  单条命令: node check_at.js --cmd "AT+QENG=\\"servingcell\\""');
    console.log('  显示原始: node check_at.js --raw');
    console.log('========================================');
}

main().catch(console.error);
