const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors');
const XLSX = require('xlsx');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
let clipboardy;
try {
    clipboardy = require('clipboardy');
} catch (e) {
    console.log('[警告] clipboardy 不可用，跳过剪贴板功能');
}

const app = express();
const PORT = 8121;

app.use(cors());
app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));

app.use((req, res, next) => {
    const start = Date.now();
    const clientIp = req.ip || req.connection.remoteAddress || 'unknown';

    console.log(`\n[${new Date().toLocaleString('zh-CN')}]`);
    console.log(`[请求] ${req.method} ${req.originalUrl}`);
    console.log(`[来源] IP: ${clientIp}`);
    console.log(`[参数] ${JSON.stringify(req.query || {})}`);

    if (req.body && Object.keys(req.body).length > 0) {
        console.log(`[Body] ${JSON.stringify(req.body)}`);
    }

    const originalJson = res.json.bind(res);
    res.json = (data) => {
        console.log(`[响应] ${res.statusCode} - ${JSON.stringify(data)}`);
        console.log(`[耗时] ${Date.now() - start}ms`);
        return originalJson(data);
    };

    req.on('close', () => {
        console.log(`[断开] 客户端断开连接`);
    });

    next();
});

const DATA_FILE = path.join(__dirname, 'sms_records.xlsx');

let devices = new Map();
let pendingRequests = [];

function loadWorkbook() {
    if (fs.existsSync(DATA_FILE)) {
        return XLSX.readFile(DATA_FILE);
    }
    return null;
}

function saveToExcel(record) {
    let workbook = loadWorkbook();
    let sheetName = 'SMS Records';

    if (!workbook) {
        workbook = XLSX.utils.book_new();
        workbook.SheetNames = [sheetName];
        workbook.Sheets = {
            [sheetName]: XLSX.utils.aoa_to_sheet([
                ['设备ID', '设备名称', '手机号', '时间戳', '验证码内容', '完整短信内容', '接收时间']
            ])
        };
    } else {
        if (!workbook.SheetNames.includes(sheetName)) {
            workbook.SheetNames.push(sheetName);
            workbook.Sheets[sheetName] = XLSX.utils.aoa_to_sheet([
                ['设备ID', '设备名称', '手机号', '时间戳', '验证码内容', '完整短信内容', '接收时间']
            ]);
        }
    }

    let worksheet = workbook.Sheets[sheetName];
    let range = XLSX.utils.decode_range(worksheet['!ref'] || sheetName);
    let rowIndex = range.e.r + 1;

    let newRow = [
        record.deviceId,
        record.deviceName || 'Unknown',
        record.phoneNumber || '',
        record.timestamp || Date.now(),
        record.code || '',
        record.content || '',
        new Date().toLocaleString('zh-CN')
    ];

    XLSX.utils.sheet_add_aoa(worksheet, [newRow], { origin: rowIndex });
    worksheet['!ref'] = XLSX.utils.encode_range(
        { s: { r: 0, c: 0 }, e: { r: rowIndex, c: 6 } }
    );

    XLSX.writeFile(workbook, DATA_FILE);
}

function extractVerificationCode(message) {
    if (!message || message.length === 0) {
        return null;
    }

    const patterns = [
        /验证码[：:]?\s*(\d{4,8})/i,
        /校验码[：:]?\s*(\d{4,8})/i,
        /动态码[：:]?\s*(\d{4,8})/i,
        /密码[：:]?\s*(\d{4,8})/i,
        /确认码[：:]?\s*(\d{4,8})/i,
        /交易码[：:]?\s*(\d{4,8})/i,
        /支付码[：:]?\s*(\d{4,8})/i,
        /签约码[：:]?\s*(\d{4,8})/i,
        /提取码[：:]?\s*(\d{4,8})/i,
        /提取密码[：:]?\s*(\d{4,8})/i,
        /【[^】]*】[：:]?\s*(\d{4,8})/,
        /code[：:]?\s*(\d{4,8})/i,
        /(\d{6})/,
        /(\d{4,8})/
    ];

    for (let pattern of patterns) {
        const match = message.match(pattern);
        if (match) {
            return match[1];
        }
    }

    const digitMatch = message.match(/(\d{4,8})/);
    if (digitMatch) {
        return digitMatch[1];
    }

    return null;
}

console.log('\n========================================');
console.log('  SMS Forwarder Server 启动中...');
console.log('========================================\n');

app.post('/api/sms', (req, res) => {
    try {
        const { deviceId, deviceName, phoneNumber, content, timestamp } = req.body;

        console.log('\n========== 收到POST /api/sms ==========');
        console.log(`设备ID: ${deviceId}`);
        console.log(`设备名称: ${deviceName}`);
        console.log(`手机号: ${phoneNumber}`);
        console.log(`短信内容: ${content}`);

        if (!deviceId) {
            console.log('[错误] 设备ID为空');
            return res.status(400).json({
                success: false,
                message: '设备ID不能为空'
            });
        }

        if (!content) {
            console.log('[错误] 短信内容为空');
            return res.status(400).json({
                success: false,
                message: '短信内容不能为空'
            });
        }

        if (!devices.has(deviceId)) {
            devices.set(deviceId, {
                deviceId,
                deviceName: deviceName || 'Unknown',
                firstSeen: new Date()
            });
            console.log(`[注册新设备] ID: ${deviceId}, 名称: ${deviceName || 'Unknown'}`);
        }

        const code = extractVerificationCode(content);

        console.log(`[提取验证码] ${code || '未提取到'}`);

        const record = {
            deviceId,
            deviceName: devices.get(deviceId).deviceName,
            phoneNumber: phoneNumber || '',
            code: code,
            timestamp: timestamp || Date.now(),
            content: content
        };

        saveToExcel(record);

        console.log(`\n[验证码已存储] 设备: ${deviceId}, 手机号: ${phoneNumber}, 验证码: ${code}`);
        console.log(`[待推送客户端数] ${pendingRequests.length}`);

        if (code && clipboardy) {
            clipboardy.writeSync(code);
            console.log(`[剪贴板] 已将验证码复制到剪贴板: ${code}`);
        }

        pendingRequests.forEach((pending, index) => {
            try {
                console.log(`[推送中] 客户端${index + 1}, clientId: ${pending.clientId}`);
                pending.sendEvent(record);
                console.log(`[推送成功] 客户端${index + 1}`);
            } catch (e) {
                console.error(`[推送失败] 客户端${index + 1}:`, e.message);
            }
        });

        const pushedCount = pendingRequests.length;
        pendingRequests = [];

        console.log(`[推送完成] 共推送给 ${pushedCount} 个客户端`);

        res.json({
            success: true,
            message: '短信已处理',
            code: code,
            hasCode: !!code
        });

    } catch (error) {
        console.error('[错误]', error);
        res.status(500).json({
            success: false,
            message: '服务器内部错误'
        });
    }
});

app.post('/api/heartbeat', (req, res) => {
    try {
        const { deviceId, deviceName } = req.body;

        console.log('\n========== 收到心跳 /api/heartbeat ==========');
        console.log(`设备ID: ${deviceId}`);
        console.log(`设备名称: ${deviceName}`);
        console.log(`时间: ${new Date().toLocaleString('zh-CN')}`);

        if (!devices.has(deviceId)) {
            devices.set(deviceId, {
                deviceId,
                deviceName: deviceName || 'Unknown',
                firstSeen: new Date()
            });
            console.log(`[注册新设备] ID: ${deviceId}, 名称: ${deviceName || 'Unknown'}`);
        }

        if (devices.has(deviceId)) {
            const device = devices.get(deviceId);
            device.lastHeartbeat = new Date();
        }

        res.json({
            success: true,
            message: '心跳已接收',
            timestamp: Date.now()
        });
    } catch (error) {
        console.error('[错误] 处理心跳:', error);
        res.status(500).json({
            success: false,
            message: '服务器内部错误'
        });
    }
});

app.get('/api/devices', (req, res) => {
    try {
        let deviceList = Array.from(devices.values()).map(d => ({
            deviceId: d.deviceId,
            deviceName: d.deviceName,
            firstSeen: d.firstSeen,
            lastHeartbeat: d.lastHeartbeat || null
        }));

        res.json({
            success: true,
            devices: deviceList
        });
    } catch (error) {
        res.status(500).json({
            success: false,
            message: '获取设备列表失败'
        });
    }
});

app.get('/api/sms', (req, res) => {
    try {
        let workbook = loadWorkbook();
        if (!workbook) {
            return res.json({
                success: true,
                records: []
            });
        }

        let sheetName = workbook.SheetNames[0];
        let worksheet = workbook.Sheets[sheetName];
        let data = XLSX.utils.sheet_to_json(worksheet);

        res.json({
            success: true,
            records: data
        });
    } catch (error) {
        res.status(500).json({
            success: false,
            message: '获取验证码记录失败'
        });
    }
});

app.get('/api/sms/listen', (req, res) => {
    console.log('\n========== 客户端订阅 SSE /api/sms/listen ==========');
    console.log(`设备ID过滤: ${req.query.deviceId || '全部'}`);

    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('Access-Control-Allow-Origin', '*');

    const clientId = Date.now();

    const sendEvent = (data) => {
        console.log(`[SSE推送] 发送给 clientId: ${clientId}`);
        res.write(`event: sms\n`);
        res.write(`data: ${JSON.stringify(data)}\n\n`);
    };

    pendingRequests.push({
        clientId,
        sendEvent,
        timestamp: Date.now(),
        type: 'sse'
    });

    console.log(`[SSE连接成功] clientId: ${clientId}, 当前待推送: ${pendingRequests.length}`);

    res.on('finish', () => {
        console.log(`[SSE断开] clientId: ${clientId}`);
    });

    req.on('close', () => {
        pendingRequests = pendingRequests.filter(p => p.clientId !== clientId);
        console.log(`[SSE清理] clientId: ${clientId}, 剩余: ${pendingRequests.length}`);
    });
});

app.get('/api/sms/wait', (req, res) => {
    const timeout = parseInt(req.query.timeout) || 60000;
    const deviceId = req.query.deviceId;

    console.log(`\n========== 客户端等待验证码 /api/sms/wait ==========`);
    console.log(`设备ID过滤: ${deviceId || '全部'}`);
    console.log(`超时时间: ${timeout}ms`);

    let timeoutId;
    const startTime = Date.now();

    const sendResponse = (record) => {
        if (timeoutId) clearTimeout(timeoutId);
        console.log(`[长轮询返回] 耗时: ${Date.now() - startTime}ms, 验证码: ${record.code}`);
        res.json({
            success: true,
            record: record,
            waitTime: Date.now() - startTime
        });
    };

    const checkTimeout = () => {
        pendingRequests = pendingRequests.filter(p => p.clientId !== clientId);
        console.log(`[长轮询超时] clientId: ${clientId}, 超时: ${timeout}ms`);
        res.json({
            success: false,
            message: '等待超时',
            waitTime: timeout
        });
    };

    const clientId = Date.now();

    pendingRequests.push({
        clientId,
        sendEvent: (record) => {
            console.log(`[长轮询匹配] clientId: ${clientId}, 设备: ${record.deviceId}, 验证码: ${record.code}`);
            if (!deviceId || record.deviceId === deviceId) {
                sendResponse(record);
            } else {
                console.log(`[长轮询跳过] clientId: ${clientId} 等待设备 ${deviceId}, 收到设备 ${record.deviceId}`);
            }
        },
        timestamp: Date.now(),
        type: 'wait'
    });

    console.log(`[长轮询注册成功] clientId: ${clientId}, 当前待推送: ${pendingRequests.length}`);

    timeoutId = setTimeout(checkTimeout, timeout);

    req.on('close', () => {
        if (timeoutId) clearTimeout(timeoutId);
        pendingRequests = pendingRequests.filter(p => p.clientId !== clientId);
        console.log(`[长轮询断开] clientId: ${clientId}, 剩余: ${pendingRequests.length}`);
    });
});

app.get('/api/sms/subscribe', (req, res) => {
    const deviceId = req.query.deviceId;

    console.log(`\n========== 客户端订阅流 /api/sms/subscribe ==========`);
    console.log(`设备ID过滤: ${deviceId || '全部'}`);

    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.flushHeaders();

    const clientId = Date.now();

    const sendRecord = (record) => {
        if (!deviceId || record.deviceId === deviceId) {
            console.log(`[JSON流推送] clientId: ${clientId}, 验证码: ${record.code}`);
            res.write(`\n${JSON.stringify({
                success: true,
                record: record,
                type: 'new_sms'
            })}\n`);
        }
    };

    pendingRequests.push({
        clientId,
        sendEvent: sendRecord,
        timestamp: Date.now(),
        type: 'subscribe'
    });

    console.log(`[JSON流连接成功] clientId: ${clientId}, 当前: ${pendingRequests.length}`);

    req.on('close', () => {
        pendingRequests = pendingRequests.filter(p => p.clientId !== clientId);
        console.log(`[JSON流断开] clientId: ${clientId}, 剩余: ${pendingRequests.length}`);
    });
});

app.get('/api/sms/:deviceId', (req, res) => {
    try {
        const { deviceId } = req.params;

        let workbook = loadWorkbook();
        if (!workbook) {
            return res.json({
                success: true,
                records: []
            });
        }

        let sheetName = workbook.SheetNames[0];
        let worksheet = workbook.Sheets[sheetName];
        let data = XLSX.utils.sheet_to_json(worksheet);

        let filteredRecords = data.filter(row => row['设备ID'] === deviceId);

        res.json({
            success: true,
            records: filteredRecords
        });
    } catch (error) {
        res.status(500).json({
            success: false,
            message: '获取验证码记录失败'
        });
    }
});

app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        timestamp: Date.now(),
        devices: devices.size,
        pendingListeners: pendingRequests.length
    });
});

app.listen(PORT, '0.0.0.0', () => {
    console.log('========================================');
    console.log('  SMS Forwarder Server 已启动');
    console.log(`  端口: ${PORT}`);
    console.log(`  访问地址: http://0.0.0.0:${PORT}`);
    console.log('========================================');
    console.log('');
    console.log('API接口:');
    console.log('  POST /api/sms              - 接收短信（后端提取验证码）');
    console.log('  GET  /api/devices          - 获取设备列表');
    console.log('  GET  /api/sms              - 获取所有验证码记录');
    console.log('  GET  /api/sms/:id          - 获取指定设备的验证码');
    console.log('  GET  /api/sms/wait        - 【实时等待】等待新验证码到达');
    console.log('  GET  /api/sms/listen       - 【SSE推送】实时订阅新验证码');
    console.log('  GET  /api/sms/subscribe    - 【JSON流】持续接收验证码');
    console.log('  GET  /health               - 健康检查');
    console.log('');
    console.log(`数据文件: ${DATA_FILE}`);
    console.log('');
});
