# NMSMS Forwarder - 短信验证码转发器

一个简单的安卓APP，用于自动读取手机短信验证码并转发到电脑后端。

***

## 📌 项目说明

**开源协议**: MIT License (可自由使用、修改和分发)
**开发工具**:

- 后端: VS Code (Visual Studio Code)
- APP: Android Studio

***

## 项目架构

```
手机收到短信 → APP发送原始内容 → 后端提取验证码 → 推送给等待的客户端
```

### 职责分工

| 组件      | 职责            |
| ------- | ------------- |
| **APP** | 只负责读取并发送原始短信  |
| **后端**  | 智能提取验证码、存储、推送 |

***

## 项目结构

```
CC/
├── README.md                   # 本文档
├── SPEC.md                    # 项目规范文档
├── backend/                   # Node.js 后端服务
│   ├── package.json
│   └── server.js              # 主服务文件
└── android/                   # 安卓APP
    └── SmsForwarder/
        └── app/src/main/
            ├── AndroidManifest.xml
            ├── java/com/smsforwarder/
            │   ├── MainActivity.java         # 主界面
            │   ├── SmsReceiver.java          # 短信接收
            │   ├── SmsForwarderService.java  # 前台服务
            │   └── NetworkUtil.java          # 网络工具
            └── res/
                └── layout/activity_main.xml
```

***

## 一、后端部署

### 1. 安装依赖

```bash
cd backend
npm install
```

### 2. 启动服务

```bash
npm start
```

服务将在端口 **8121** 上运行，监听所有IP（0.0.0.0）

### 3. 查看控制台输出

启动成功后会显示：

```
========================================
  SMS Forwarder Server 已启动
  端口: 8121
========================================

API接口:
  POST /api/sms              - 接收短信（后端提取验证码）
  GET  /api/devices          - 获取设备列表
  GET  /api/sms              - 获取所有验证码记录
  GET  /api/sms/:id          - 获取指定设备的验证码
  GET  /api/sms/wait         - 【实时等待】等待新验证码到达
  GET  /api/sms/listen       - 【SSE推送】实时订阅新验证码
  GET  /health               - 健康检查
```

***

## 二、后端API说明

### 实时获取验证码（推荐）

#### 1. 长轮询 - /api/sms/wait

等待新验证码到达后立即返回，超时默认60秒。

```bash
curl http://localhost:8121/api/sms/wait
```

参数：

- `timeout` - 超时时间（毫秒），默认60000
- `deviceId` - 只等待指定设备的验证码

响应：

```json
{
  "success": true,
  "record": {
    "deviceId": "xxx",
    "deviceName": "PJF110",
    "phoneNumber": "1069295890011962",
    "code": "065432",
    "timestamp": 1777999367534,
    "content": "【电子牵】验证码：065432，5分钟内有效。请勿将验证码泄漏给他人！"
  },
  "waitTime": 16400
}
```

#### 2. SSE推送 - /api/sms/listen

持续订阅，每收到新验证码就推送一条。

```bash
curl -N http://localhost:8121/api/sms/listen
```

#### 3. JSON流 - /api/sms/subscribe

类似SSE，但用JSON格式输出。

### 其他API

| 接口             | 方法   | 说明            |
| -------------- | ---- | ------------- |
| `/api/sms`     | POST | 接收短信（后端提取验证码） |
| `/api/devices` | GET  | 获取已注册设备列表     |
| `/api/sms`     | GET  | 获取所有验证码记录     |
| `/health`      | GET  | 健康检查          |

***

## 三、安卓APP编译和安装

### 1. 使用Android Studio打开项目

```bash
cd android/SmsForwarder
# 用Android Studio打开此文件夹
```

### 2. 等待Gradle同步

首次打开会自动下载依赖，等待同步完成。

### 3. 构建APK

在Android Studio中选择：
**Build → Build Bundle(s) / APK(s) → Build APK(s)**

或使用快捷键：**Ctrl + Shift + F9**

### 4. 获取APK文件

构建完成后，点击提示中的 "locate"，APK位置：
`app/build/outputs/apk/debug/app-debug.apk`

### 5. 安装到手机

将APK文件传输到手机并安装

***

## 四、APP使用说明

### 1. 网络配置

确保手机和电脑在**同一WiFi网络下**

### 2. 获取电脑IP地址

在电脑命令行运行：

```bash
# Windows
ipconfig
# 或
ipconfig | findstr "IPv4"

# Mac/Linux
ifconfig | grep "inet "
```

记下电脑的IP地址（如 `192.168.1.100`）

### 3. APP配置

1. 打开APP
2. 输入电脑的IP地址
3. 端口默认8121
4. 点击"保存配置"
5. 点击"测试连接"确认连接成功

### 4. 配置后台权限

1. 点击"加入电池白名单"
2. 在系统设置中允许APP忽略电池优化
3. （可选）在手机设置中添加为"受保护应用"

### 5. 自动运行

配置完成后，APP会自动：

1. 在后台持续运行（有前台通知）
2. 监听收到的短信
3. 发送原始内容到后端
4. 后端智能识别验证码并推送

***

## 五、验证码提取规则

后端支持多种格式：

| 格式          | 示例             |
| ----------- | -------------- |
| 验证码：xxxxxx  | 【验证码：123456】   |
| 校验码: xxxxxx | 校验码: 654321    |
| 动态码 xxxxxx  | 动态码 888888     |
| 密码 xxxxxxx  | 密码 666666      |
| 【服务名】xxxxxx | 【京东】验证码：666666 |
| 纯6位数字       | 888888         |
| 纯4-8位数字     | 1234, 12345678 |

***

## 六、数据存储

收到的验证码会自动保存到：
`backend/sms_records.xlsx`

表格结构：

| 设备ID | 设备名称   | 手机号     | 时间戳        | 验证码内容  | 完整短信内容   | 接收时间                |
| ---- | ------ | ------- | ---------- | ------ | -------- | ------------------- |
| xxx  | PJF110 | 1069... | 1777999... | 065432 | 【电子牵】... | 2026-05-06 22:00:00 |

***

## 七、控制台日志说明

运行时会显示详细日志：

### 收到短信时

```
========== 收到POST /api/sms ==========
设备ID: xxx
设备名称: PJF110
手机号: 1069295890011962
短信内容: 【电子牵】验证码：065432...

[提取验证码] 065432
[验证码已存储] 设备: xxx, 手机号: 1069295890011962, 验证码: 065432
[待推送客户端数] 1
[推送完成] 共推送给 1 个客户端
```

### 客户端等待时

```
========== 客户端等待验证码 /api/sms/wait ==========
设备ID过滤: 全部
超时时间: 60000ms
[长轮询注册成功] clientId: 1777999350120, 当前待推送: 1

[长轮询匹配] clientId: 1777999350120, 设备: xxx, 验证码: 065432
[长轮询返回] 耗时: 16400ms, 验证码: 065432
```

***

## 八、常见问题

### 1. APP在后台收不到短信？

可能原因：

- 电池优化限制了APP后台运行
- APP没有加入受保护应用
- 手机锁屏后网络断开

解决：

- 点击APP内"加入电池白名单"
- 在手机设置中允许APP后台运行
- 允许APP在锁屏时保持网络连接

### 2. 验证码提取错误？

后端已经支持多种格式，如果还有错误，请：

1. 查看控制台日志中的完整短信内容
2. 如果格式特殊，在 `server.js` 的 `extractVerificationCode` 中添加新规则

### 3. 测试连接失败？

检查：

- 电脑和手机是否在同一WiFi
- 后端服务是否正常运行
- IP地址是否正确（注意不要输入 `http://`）
- 端口8121是否被防火墙拦截
- 电脑防火墙是否允许8121端口

***

## 九、技术栈

| 组件    | 技术/工具                    |
| ----- | ------------------------ |
| 后端开发  | VS Code                  |
| 后端技术  | Node.js + Express        |
| APP开发 | Android Studio           |
| APP技术 | Android (Java) + Gradle  |
| 数据存储  | Excel (XLSX)             |
| 实时推送  | Server-Sent Events (SSE) |

***

## 十、开发建议

### 修改验证码提取规则

编辑 `backend/server.js` 中的 `extractVerificationCode` 函数

### 自定义APP界面

编辑 `android/SmsForwarder/app/src/main/res/layout/activity_main.xml`

***

## License

MIT
