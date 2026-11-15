# 项目管理台 · 手机端

Android + Jetpack Compose 客户端：通过 **PushGo（Pull 原型）** 或旧版 ntfy 连接一台或多台桌面端「项目管理器」，接收待审核与提问，在手机上通过 / 打回 / 拒绝 / 回答，答案加密回传。

- PushGo 协议见参考项目 `D:\job\peoject-manager\doc\pushgo-link.md`（本版桌面端默认使用）。
- 旧 ntfy 协议见 `D:\job\peoject-manager\doc\mobile-link.md`（手机端仍兼容 v1 连接信息）。

## 功能

- **多连接**：每台桌面端一条独立连接；PushGo 连接有独立网关 / 频道 / 密码，ntfy 连接有独立 server / topic / key；待办聚合展示并标注来源，可按连接筛选。
- **PushGo Pull**：设备注册 + 频道订阅，每 15 秒 Pull/ACK；单条 32 KB，公共网关无每日条数（受每分钟频率限制）。当前为 Pull 原型，最多 15 秒延迟。
- **扫码接入**：扫桌面端二维码，或手动粘贴连接信息；本地命名、重命名、重新扫码、移除。
- **审核与提问**：通过 / 打回（意见必填）/ 拒绝；候选答案 + 自定义输入 + 倒计时与过期态。
- **后台保活**：前台服务持有全部连接（ntfy 长连接 / PushGo 轮询），断网指数退避重连，网络恢复立即重连；开机解锁后自动恢复。
- **通知**：新审核 / 新提问各发一条，同一消息只提醒一次；汇总通知「N 条待办 · M 台桌面端」；点击直达对应条目。
- **端到端加密**：PBKDF2-HMAC-SHA256 派生 + AES-256-GCM，AAD 绑定 topic / 频道 ID；连接密钥存 EncryptedSharedPreferences。

## 构建

环境：

- JDK 17+（本机使用 `D:\Android\Studio\jbr`，OpenJDK 21）
- Android SDK（本机 `D:\Android\Sdk`，platform 35 / build-tools 35）
- Gradle 8.14.4（wrapper 已配置腾讯镜像）

```bash
export JAVA_HOME="D:/Android/Studio/jbr"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 已写入 `sdk.dir=D:/Android/Sdk`（按本机路径调整）。

## 使用

1. 桌面端打开「设置与数据 → 通知与集成 → 手机端通信」，开启通道并显示二维码。
2. 手机端点「添加桌面端」扫码；没有相机时可用「手动输入连接信息」粘贴二维码里的 JSON。
3. 给连接起名（工作电脑 / 家里电脑），添加后进入待办列表。
4. 退到后台即可；有审核或提问时会收到通知，点击直达详情。
5. 首次使用建议：
   - 允许通知权限；
   - 在「连接 → 电池优化」里设为「不限制」；
   - 国产系统再到「手机管家 / 自启动管理」里允许后台运行。

## 后台策略

- 前台服务类型为 `remoteMessaging`：语义是接收配对设备的消息，允许开机后恢复，也不受 Android 15 `dataSync` 6 小时上限影响。
- `targetSdk = 34`：避免 Android 15 对 `dataSync` 的额外限制；`compileSdk = 35`。
- `START_STICKY` + 网络回调立即重连；服务通知常驻，退出应用不停止。
- 开机广播在用户首次解锁后触发；若系统拦截后台启动，App 下次打开会再次拉起服务。
- 被用户「强行停止」后系统不会自动恢复，这是 Android 的限制。

## 联调

仓库内附带一个本地「桌面端」测试桩，会推送审核 / 提问并打印手机回传的答案：

```bash
node tools/mobile-peer.mjs --name 工作电脑
```

把输出的 `CONNECT_JSON` 粘贴到手机端「手动输入连接信息」即可。可用 `--interval 30` 调整重发间隔，或用 `--topic/--key` 复用已有连接。

## 测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖：加解密互逆、错误 topic / key、密文篡改、连接信息校验、答案 id 规则、派生密钥稳定性。

## 结构

```
app/src/main/java/com/projectmanager/mobile/
  core/        协议常量、加解密、连接信息校验、Intent 常量
  data/        连接与待办模型、加密存储、仓库与去重
  link/        ntfy 订阅 / 发布、PushGo 设备 / 频道 / Pull / ACK、多连接会话、答案重发
  notify/      通知渠道、条目通知与汇总
  service/     前台服务、开机恢复
  ui/          Compose 页面、主题、ViewModel
tools/mobile-peer.mjs   本地测试桩
doc/mobile-app-design.md 页面设计与技术说明
design/                  高保真原型与截图
```

## 已知限制

- PushGo Pull 原型最多 15 秒延迟；设备路由使用随机 provider token 进入 Pull 队列，`send_status` 可能显示 `partially_failed`；正式版建议切 PushGo 私有 WSS 或自建 Gateway。
- 公共 PushGo 网关没有每日条数额度，但受每分钟请求频率限制；本项目 15 秒轮询 + 每分钟心跳约 10 次/分钟，远低于限制。
- ntfy 公共服单条消息 4 KB、每天 250 条；本版桌面端已改用 PushGo，旧 ntfy 连接仅保留兼容。
- 手机端只处理「待审核」与「待回答」，不提供需求创建、历史记录、diff 查看。
- 通知权限被拒绝时，待办仍会同步到列表，但不会有系统通知。
