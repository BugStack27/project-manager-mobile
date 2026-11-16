# 项目管理台 · 手机端

Android + Jetpack Compose 客户端：配对一台或多台桌面端「项目管理器」，通过桌面端的 `/remote` 接口长轮询取待办，在手机上通过 / 打回 / 拒绝 / 回答。PushGo 只负责把接入点（隧道地址 + 令牌）送到手机上，不承载审批内容。

- PushGo 协议见参考项目 `D:\job\peoject-manager\doc\pushgo-link.md`（本版桌面端默认使用）。

## 功能

- **多连接**：每台桌面端一条独立连接，各有独立频道与密码；接入点由桌面端随心跳下发，无需手填。
- **PushGo Pull**：设备注册 + 频道订阅，每 15 秒 Pull/ACK；单条 32 KB，公共网关无每日条数（受每分钟频率限制）。当前为 Pull 原型，最多 15 秒延迟。
- **扫码接入**：扫桌面端二维码，或手动粘贴连接信息；本地命名、重命名、重新扫码、移除。
- **审核与提问**：通过 / 打回（意见必填）/ 拒绝；候选答案 + 自定义输入 + 倒计时与过期态。
- **后台保活**：前台服务持有长轮询（`RemoteWatcher`）与接入点通道，断网指数退避重连，网络恢复立即重连；开机解锁后自动恢复。
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

## 发布

推一个 `v` 前缀的 tag 即触发 `.github/workflows/release.yml`：跑单元测试、构建 release APK，挂到同名 GitHub Release（附 `sha256` 校验文件），手机浏览器打开 Release 页即可下载安装。

```bash
# 先把 app/build.gradle.kts 的 versionName 改成 0.1.1 并提交，再打 tag
git tag -a v0.1.1 -m "修复息屏后通知延迟"
git push origin v0.1.1
```

- tag 去掉 `v` 必须与 `versionName` 完全一致，否则流水线直接失败；`versionCode` 由 tag 推导为 `major*10000 + minor*100 + patch`，保证新包能覆盖旧包装上。
- 签名：配置仓库 secrets 后使用正式密钥，未配置时退回 debug 签名（可直接安装，但不适合长期分发）。需要配置的 secrets：
  - `ANDROID_KEYSTORE_BASE64`：keystore 文件 `base64 -w0 release.keystore`
  - `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`
- 本机签名打包：在根目录放 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`，已 gitignore），`./gradlew :app:assembleRelease` 自动使用它。

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
- 前台服务持有部分唤醒锁（`pm-mobile:link`）：PushGo 靠 15 秒定时器 Pull，息屏后系统挂起 CPU 会让轮询停摆，待办要等点亮屏幕才被拉走；唤醒锁保证息屏后仍能按时拉取，代价是待机耗电略增。
- 服务改为在 Activity 前台时拉起：Android 12+ 禁止从后台启动前台服务，`Application.onCreate` 里启动会被拒（`ForegroundServiceStartNotAllowedException`），进程重建后服务就起不来。
- 服务不会再因为「进不了前台」而自我终止：后台被系统重启时 `startForeground` 可能被拒，此时只记录日志、继续轮询并把这一轮待办通知出去，下次 `onStartCommand`（打开 App / 系统重启服务）再重试进入前台。
- 首次进入「连接」页且尚未加入电池优化白名单时，弹一次说明并提供直达系统授权页的按钮；这项设置是后台能持续联网拉取的前提。
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
  link/        PushGo 频道与设备注册、接入点心跳、远程待办长轮询（RemoteWatcher）
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

- 手机端只处理「待审核」与「待回答」，不提供需求创建、历史记录、diff 查看。
- 通知权限被拒绝时，待办仍会同步到列表，但不会有系统通知。
