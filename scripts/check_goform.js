/**
 * Goform API 一键验证脚本
 *
 * 用法：
 *   node check_goform.js                          # 默认 192.168.0.1:2333 口令 admin
 *   node check_goform.js 192.168.0.1 2333         # 指定地址，口令 admin
 *   node check_goform.js 192.168.0.1 2333 admin    # 指定地址 + 口令
 *   node check_goform.js 192.168.1.1 80 mypass     # 自定义全部
 *
 * 与 App 端认证逻辑完全一致：
 *   kano-t       = 当前时间戳
 *   kano-sign    = generateKanoSign(GET, 纯路径, t)
 *   Authorization = sha256(口令)
 */

const crypto = require('crypto');
const http = require('http');

// ============ 默认值 ============
const DEFAULT_HOST = '192.168.0.1';
const DEFAULT_PORT = 2333;
const DEFAULT_PASSWORD = 'admin';

// API 路径（对应 SPUtil 默认值）
const GOFORM_PATH = '/api/goform/goform_get_cmd_process';
// 签名密钥（对应 SPUtil.DEFAULT_SECRET_KEY）
const SECRET_KEY = 'minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd';
// 一键查询命令
const ALL_CMDS = 'lte_rsrp,Z5g_rsrp,network_type,rssi,monthly_data';

// ============ 签名算法（与 NetUtil.generateKanoSign 一致） ============
function generateKanoSign(method, path, timestamp) {
    const rawData = `minikano${method.toUpperCase()}${path}${timestamp}`;

    // HMAC-MD5 → 16 bytes
    const hmac = crypto.createHmac('md5', SECRET_KEY);
    hmac.update(rawData);
    const hmacBytes = hmac.digest();

    // 二分：part1[0..7], part2[8..15]
    const part1 = hmacBytes.subarray(0, 8);
    const part2 = hmacBytes.subarray(8, 16);

    // SHA256(part1) + SHA256(part2) → 64 bytes → bytes
    const sha1 = crypto.createHash('sha256').update(part1).digest('hex');
    const sha2 = crypto.createHash('sha256').update(part2).digest('hex');
    const combined = Buffer.from(sha1 + sha2, 'hex');

    // SHA256(combined) → hex
    return crypto.createHash('sha256').update(combined).digest('hex');
}

// ============ HTTP 请求 ============
function fetchApi(host, port, path, authHeader) {
    return new Promise((resolve, reject) => {
        const t = Date.now();
        const purePath = path.includes('?') ? path.split('?')[0] : path;
        const sign = generateKanoSign('GET', purePath, t);

        const headers = {
            'kano-t': String(t),
            'kano-sign': sign,
            'Accept': 'application/json',
        };
        if (authHeader) {
            headers['Authorization'] = authHeader;
        }

        const options = { hostname: host, port, path, method: 'GET', timeout: 10000, headers };

        console.log(`[请求] GET http://${host}:${port}${path}`);
        console.log(`[签名] kano-t=${t}, kano-sign=${sign.slice(0, 16)}...`);

        const req = http.request(options, (res) => {
            let body = '';
            res.on('data', (chunk) => { body += chunk; });
            res.on('end', () => {
                console.log(`[响应] HTTP ${res.statusCode}`);
                try {
                    const json = JSON.parse(body);
                    resolve({ ok: true, status: res.statusCode, data: json });
                } catch (e) {
                    resolve({ ok: false, status: res.statusCode, raw: body.substring(0, 500) });
                }
            });
        });

        req.on('error', (err) => reject(new Error(`请求失败: ${err.message}`)));
        req.on('timeout', () => { req.destroy(); reject(new Error('请求超时 (10s)')); });
        req.end();
    });
}

// ============ 主流程 ============
async function main() {
    const host = process.argv[2] || DEFAULT_HOST;
    const port = parseInt(process.argv[3]) || DEFAULT_PORT;
    const password = process.argv[4] || DEFAULT_PASSWORD;

    // 计算认证头: sha256(口令)
    const authHeader = crypto.createHash('sha256').update(password).digest('hex');

    console.log('========================================');
    console.log('  Goform API 一键验证');
    console.log('========================================');
    console.log(`  设备地址:  ${host}:${port}`);
    console.log(`  Goform路径: ${GOFORM_PATH}`);
    console.log(`  口令:       ${password}`);
    console.log(`  认证头:     ${authHeader}`);
    console.log(`  查询命令:   ${ALL_CMDS}`);
    console.log('');

    const path = `${GOFORM_PATH}?is_all=true&cmd=${encodeURIComponent(ALL_CMDS)}`;

    try {
        const result = await fetchApi(host, port, path, authHeader);

        if (result.ok) {
            console.log(`✅ 请求成功! HTTP ${result.status}\n`);

            const d = result.data;

            console.log('── 信号参数 ──');
            console.log(`  LTE RSRP : ${d.lte_rsrp || '-'}`);
            console.log(`  5G  RSRP : ${d.Z5g_rsrp || '-'}`);
            console.log(`  RSSI     : ${d.rssi || '-'}`);
            console.log(`  网络类型 : ${d.network_type || '-'}`);
            const sigOk = d.lte_rsrp || d.Z5g_rsrp || d.rssi || d.network_type;
            console.log(`  👉 信号接口: ${sigOk ? '✅ 正常' : '⚠️ 无有效数据'}`);

            console.log('');
            console.log('── 月流量 ──');
            const monthly = d.monthly_data || {};
            const tx = typeof monthly === 'object' ? (monthly.monthly_tx_bytes || 0) : 0;
            const rx = typeof monthly === 'object' ? (monthly.monthly_rx_bytes || 0) : 0;
            console.log(`  上传: ${formatBytes(tx)}`);
            console.log(`  下载: ${formatBytes(rx)}`);
            const mOk = tx > 0 || rx > 0;
            console.log(`  👉 流量接口: ${mOk ? '✅ 正常' : '⚠️ 无有效数据'}`);

            console.log('');
            console.log('── 完整 JSON ──');
            console.log(JSON.stringify(d, null, 2));
        } else {
            console.log(`❌ 请求失败! HTTP ${result.status}`);
            console.log(`  原始响应: ${result.raw}`);
        }
    } catch (err) {
        console.log(`❌ ${err.message}`);
        console.log('  👉 请检查设备是否已连接，地址和端口是否正确');
    }

    console.log('');
    console.log('========================================');
    console.log('  一键命令: node check_goform.js [host] [port] [password]');
    console.log('  默认值:   node check_goform.js');
    console.log('========================================');
}

function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + units[i];
}

main().catch(console.error);
