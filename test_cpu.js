const crypto = require('crypto');

const SECRET_KEY = 'minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd';
const DEVICE_INFO_PATH = '/api/baseDeviceInfo';

function sha256Hex(input) {
    return crypto.createHash('sha256').update(input, 'utf8').digest('hex');
}

function sha256Bytes(input) {
    return crypto.createHash('sha256').update(input).digest();
}

function hmacMd5(data, key) {
    return crypto.createHmac('md5', key).update(data, 'utf8').digest();
}

function generateKanoSign(method, path, timestamp) {
    const rawData = `minikano${method.toUpperCase()}${path}${timestamp}`;
    const hmacBytes = hmacMd5(rawData, SECRET_KEY);
    const mid = hmacBytes.length / 2;
    const part1 = hmacBytes.subarray(0, mid);
    const part2 = hmacBytes.subarray(mid);
    const sha1 = sha256Bytes(part1);
    const sha2 = sha256Bytes(part2);
    const combined = Buffer.concat([sha1, sha2]);
    return sha256Bytes(combined).toString('hex');
}

async function fetchCpuUsage(address, password) {
    const baseUrl = address.startsWith('http') ? address.replace(/\/+$/, '') : `http://${address}:2333`;
    const t = Date.now();
    const sign = generateKanoSign('GET', DEVICE_INFO_PATH, t);
    const auth = sha256Hex(password);

    const url = `${baseUrl}${DEVICE_INFO_PATH}`;
    const headers = {
        'kano-t': String(t),
        'kano-sign': sign,
        'Authorization': auth
    };

    console.log(`\n=== ${new Date().toLocaleTimeString()} ===`);

    try {
        const resp = await fetch(url, { headers, signal: AbortSignal.timeout(15000) });
        const text = await resp.text();
        if (!resp.ok) {
            console.log(`HTTP ${resp.status}: ${text}`);
            return;
        }
        const data = JSON.parse(text);
        const rootCpuUsage = data.cpu_usage;
        const cpuUsageInfo = data.cpuUsageInfo;
        const infoCpu = cpuUsageInfo?.cpu;

        console.log(`[根字段] cpu_usage = ${rootCpuUsage}`);
        console.log(`[cpuUsageInfo] cpu = ${infoCpu}`);

        // 判断哪个值被使用
        let used;
        if (rootCpuUsage != null && rootCpuUsage >= 0 && rootCpuUsage <= 100) {
            used = rootCpuUsage;
            console.log('→ 使用根字段 cpu_usage');
        } else if (infoCpu != null) {
            const parsed = parseFloat(infoCpu);
            if (!isNaN(parsed)) {
                used = parsed;
                console.log('→ 回退到 cpuUsageInfo["cpu"]');
            }
        }
        if (used != null) {
            console.log(`→ 最终显示: ${used.toFixed(1)}%`);
        }

        console.log('\n各核心使用率:');
        if (cpuUsageInfo) {
            for (const [key, value] of Object.entries(cpuUsageInfo)) {
                if (key === 'cpu') continue; // 已在上面单独显示
                console.log(`  ${key}: ${value}%`);
            }
        }
    } catch (err) {
        console.log(`请求失败: ${err.message}`);
    }
}

async function main() {
    const args = process.argv.slice(2);
    let address = '192.168.0.1';
    let password = 'admin';

    if (args.length >= 1) {
        address = args[0];
        if (args.length >= 2) password = args[1];
    } else {
        console.log('用法: node test_cpu.js <设备地址> [管理口令]');
        console.log(`示例: node test_cpu.js 192.168.0.1 admin\n`);
    }

    console.log(`设备地址: ${address}, 管理口令: ${password}`);
    console.log(`按 Ctrl+C 停止\n`);

    await fetchCpuUsage(address, password);
    setInterval(() => fetchCpuUsage(address, password), 5000);
}

main().catch(console.error);