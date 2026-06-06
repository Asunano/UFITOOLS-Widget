/**
 * Goform 命令发现脚本 — 批量探测设备支持的命令
 *
 * 用法：
 *   node goform_explore.js                           # 探测所有已知读命令
 *   node goform_explore.js 192.168.0.1 2333 admin     # 指定地址+口令
 *   node goform_explore.js --set REBOOT_DEVICE         # 执行写操作（重启）
 *   node goform_explore.js --cmd lte_rsrp,wifi_ssid    # 自定义读命令
 *
 * 认证逻辑与 check_goform.js 完全一致
 */

const crypto = require('crypto');
const http = require('http');
const querystring = require('querystring');

// ============ 默认值 ============
const DEFAULT_HOST = '192.168.0.1';
const DEFAULT_PORT = 2333;
const DEFAULT_PASSWORD = 'admin';

const GET_PATH  = '/api/goform/goform_get_cmd_process';
const SET_PATH  = '/api/goform/goform_set_cmd_process';
const SECRET_KEY = 'minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd';

// ============ 已知命令列表（每类一行，方便增删） ============
const CMD_LIST = [
    // ── 信号详情 ──
    'lte_rsrp','lte_rsrq','lte_sinr','lte_rssi','lte_cqi',
    'Z5g_rsrp','Z5g_rsrq','Z5g_sinr',
    'rssi','cell_id','pci','tac','earfcn','nrarfcn',
    'lte_band','nr_band','lte_ca_pcell_band','lte_ca_scell_band',
    'lte_ca_pcell_bandwidth','lte_ca_scell_bandwidth',
    // ── 网络 ──
    'network_type','wan_ipaddr','ipv6_wan_ipaddr','dns','apn','pdp_type',
    // ── WiFi ──
    'wifi_ssid','wifi_password','wifi_channel',
    'wifi_24g_state','wifi_5g_state','wifi_encryption',
    'wifi_sta_mac','wifi_ap_mac',
    // ── 设备标识 ──
    'imei','imsi','iccid','model','hardware_version',
    'software_version','web_version','device_name','mac_address',
    // ── 流量 ──
    'monthly_data','daily_data','total_data',
    'monthly_tx_bytes','monthly_rx_bytes',
    'daily_tx_bytes','daily_rx_bytes',
    'current_rx_bytes','current_tx_bytes',
    // ── 设备状态 ──
    'sim_state','pin_status','puk_times','sms_state','sms_count',
    'battery_percent','battery_voltage','battery_status','battery_temp',
    'uptime','connection_time','connected_devices','wifi_users',
    'cpu_usage','mem_usage',
    // ── 锁频/模式 ──
    'lte_lock','nr_lock','network_select','data_roaming','network_mode',
    // ── 存储 ──
    'sd_status','sd_total','sd_free',
    // ── 其它 ──
    'web_language','timezone','ntp_server',
];

// ============ 写操作列表 ============
const SET_LIST = {
    'REBOOT_DEVICE':    { desc: '重启设备',        params: {} },
    'FACTORY_RESET':    { desc: '恢复出厂设置',    params: {} },
    'WIFI_SWITCH':      { desc: 'WiFi 开关',       params: { wifi_24g_state: '1', wifi_5g_state: '1' } },
    'CHANGE_PASSWORD':  { desc: '修改密码',         params: { old_password: 'admin', new_password: 'admin123' } },  // 危险示例
    'SET_NETWORK_MODE': { desc: '设置网络模式',     params: { network_mode: '5g_prefer' } },
};

// ============ 签名算法 ============
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

// ============ 生成认证 headers ============
function authHeaders(method, path, authHeader) {
    const t = Date.now();
    const purePath = path.includes('?') ? path.split('?')[0] : path;
    const sign = generateKanoSign(method, purePath, t);
    const h = {
        'kano-t': String(t),
        'kano-sign': sign,
        'Accept': 'application/json',
    };
    if (authHeader) h['Authorization'] = authHeader;
    return { headers: h, t };
}

// ============ GET 请求 ============
function httpGet(host, port, path, authHeader) {
    return new Promise((resolve, reject) => {
        const { headers } = authHeaders('GET', path, authHeader);
        const opts = { hostname: host, port, path, method: 'GET', timeout: 10000, headers };
        const req = http.request(opts, (res) => {
            let body = '';
            res.on('data', (c) => { body += c; });
            res.on('end', () => {
                try { resolve({ ok: res.statusCode === 200, status: res.statusCode, data: JSON.parse(body) }); }
                catch (e) { resolve({ ok: false, status: res.statusCode, raw: body.substring(0, 200) }); }
            });
        });
        req.on('error', (e) => reject(e));
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        req.end();
    });
}

// ============ POST 请求（用于 goform_set_cmd_process） ============
function httpPost(host, port, path, bodyObj, authHeader) {
    return new Promise((resolve, reject) => {
        const body = querystring.stringify(bodyObj);
        const { headers } = authHeaders('POST', path, authHeader);
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        headers['Content-Length'] = Buffer.byteLength(body);

        const opts = { hostname: host, port, path, method: 'POST', timeout: 10000, headers };
        const req = http.request(opts, (res) => {
            let data = '';
            res.on('data', (c) => { data += c; });
            res.on('end', () => {
                try { resolve({ ok: res.statusCode === 200, status: res.statusCode, data: JSON.parse(data) }); }
                catch (e) { resolve({ ok: false, status: res.statusCode, raw: data.substring(0, 200) }); }
            });
        });
        req.on('error', (e) => reject(e));
        req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
        req.write(body);
        req.end();
    });
}

// ============ 批量探测读命令 ============
async function exploreReadCommands(host, port, authHeader) {
    // 分批：一次发太多 cmd 可能导致 URL 过长，每批 20 个
    const BATCH = 20;
    const results = [];

    for (let i = 0; i < CMD_LIST.length; i += BATCH) {
        const batch = CMD_LIST.slice(i, i + BATCH);
        const path = `${GET_PATH}?is_all=true&cmd=${encodeURIComponent(batch.join(','))}`;

        try {
            const r = await httpGet(host, port, path, authHeader);
            if (r.ok && r.data) {
                // 统计哪些字段有值（非空、非 null、非 "null"、非 ""）
                for (const key of batch) {
                    const val = r.data[key];
                    const hasVal = val !== undefined && val !== null && val !== '' && val !== 'null';
                    results.push({ cmd: key, ok: hasVal, val: hasVal ? String(val).substring(0, 80) : '(空)' });
                }
            } else {
                for (const key of batch) results.push({ cmd: key, ok: false, val: `HTTP ${r.status}` });
            }
        } catch (e) {
            for (const key of batch) results.push({ cmd: key, ok: false, val: e.message });
        }
    }
    return results;
}

// ============ 主流程 ============
async function main() {
    const args = process.argv.slice(2);

    // ── 解析参数 ──
    let mode = 'explore';    // explore | set | cmd
    let setGoformId = null;
    let customCmd = null;
    let host = DEFAULT_HOST;
    let port = DEFAULT_PORT;
    let password = DEFAULT_PASSWORD;

    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--set') {
            mode = 'set';
            setGoformId = args[++i] || 'REBOOT_DEVICE';
        } else if (args[i] === '--cmd') {
            mode = 'cmd';
            customCmd = args[++i] || ALL_CMDS;
        } else if (i === 0) {
            host = args[i];
        } else if (i === 1) {
            port = parseInt(args[i]) || DEFAULT_PORT;
        } else if (i === 2) {
            password = args[i];
        }
    }

    const authHeader = crypto.createHash('sha256').update(password).digest('hex');

    console.log('========================================');
    console.log('  Goform 探测工具');
    console.log('========================================');
    console.log(`  设备: ${host}:${port}  口令: ${password}`);
    console.log('');

    // ==================== 模式1：读命令探测 ====================
    if (mode === 'explore') {
        console.log(`正在探测 ${CMD_LIST.length} 个命令...\n`);
        const results = await exploreReadCommands(host, port, authHeader);

        // 分类显示
        const okCmds   = results.filter(r => r.ok);
        const failCmds = results.filter(r => !r.ok);

        console.log(`✅ 有效命令 (${okCmds.length}):`);
        if (okCmds.length === 0) {
            console.log('  (无)');
        } else {
            for (const r of okCmds) {
                console.log(`  [${r.cmd}] = ${r.val}`);
            }
        }

        console.log(`\n❌ 无效/不支持 (${failCmds.length}):`);
        if (failCmds.length === 0) {
            console.log('  (无)');
        } else {
            for (const r of failCmds) {
                console.log(`  ${r.cmd} → ${r.val}`);
            }
        }

        // 建议：把有效命令拼成 App 可用的逗号列表
        if (okCmds.length > 0) {
            const cmdStr = okCmds.map(r => r.cmd).join(',');
            console.log('\n── App 可用的一键命令 ──');
            console.log(`  cmd=${cmdStr}`);
        }
    }

    // ==================== 模式2：自定义读命令 ====================
    else if (mode === 'cmd') {
        const path = `${GET_PATH}?is_all=true&cmd=${encodeURIComponent(customCmd)}`;
        try {
            const r = await httpGet(host, port, path, authHeader);
            if (r.ok) {
                console.log(`✅ 成功\n`);
                console.log(JSON.stringify(r.data, null, 2));
            } else {
                console.log(`❌ HTTP ${r.status}`);
                if (r.raw) console.log(r.raw);
            }
        } catch (e) {
            console.log(`❌ ${e.message}`);
        }
    }

    // ==================== 模式3：写操作 ====================
    else if (mode === 'set') {
        const op = SET_LIST[setGoformId] || { desc: '未知操作', params: {} };

        console.log(`⚠️  写操作: ${op.desc} (goformId=${setGoformId})`);
        console.log(`  参数: ${JSON.stringify(op.params)}`);
        console.log('');

        // 危险操作二次确认
        if (setGoformId === 'FACTORY_RESET' || setGoformId === 'REBOOT_DEVICE') {
            console.log('🔴 这是危险操作！如果你想执行，请用 --force 参数（未实现，手动改脚本）');
        }

        const bodyObj = {
            isTest: 'false',
            goformId: setGoformId,
            ...op.params,
        };

        try {
            const r = await httpPost(host, port, SET_PATH, bodyObj, authHeader);
            console.log(`响应: HTTP ${r.status}`);
            if (r.ok) {
                console.log('✅ 执行成功');
                console.log(JSON.stringify(r.data, null, 2));
            } else {
                console.log(`⚠️  ${r.raw || '(无内容)'}`);
            }
        } catch (e) {
            console.log(`❌ ${e.message}`);
            console.log('  set_cmd_process 端点可能不存在或需要特殊参数');
        }
    }

    console.log('\n========================================');
    console.log('  读命令探测: node goform_explore.js [host] [port] [pw]');
    console.log('  自定义查询: node goform_explore.js --cmd lte_rsrp,wifi_ssid');
    console.log('  执行写操作: node goform_explore.js --set REBOOT_DEVICE');
    console.log('========================================');
}

main().catch(console.error);
