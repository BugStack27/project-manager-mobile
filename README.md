<h1 align="center">项目管理台 · 手机端</h1>

<p align="center">把桌面端的「待审核」和「待回答」装进口袋：扫码接入，随时通过、打回、回答。</p>

<p align="center">
  <code>Kotlin</code> · <code>Jetpack Compose</code> · <code>Material 3</code> · <code>Android 8.0+</code>
</p>

<p align="center">
  <a href="#功能">功能</a>
  &nbsp;·&nbsp;
  <a href="#页面">页面</a>
  &nbsp;·&nbsp;
  <a href="#快速开始">快速开始</a>
  &nbsp;·&nbsp;
  <a href="#发布">发布</a>
  &nbsp;·&nbsp;
  <a href="#后台策略">后台策略</a>
  &nbsp;·&nbsp;
  <a href="#联调">联调</a>
  &nbsp;·&nbsp;
  <a href="#目录">目录</a>
</p>

桌面端 [项目管理器](https://github.com/BugStack27/project-manager) 的 Android 客户端：配对一台或多台桌面端，通过桌面端的 `/remote` 接口长轮询取待办，在手机上通过 / 打回 / 拒绝 / 回答。PushGo 只负责把接入点（隧道地址 + 令牌）送到手机上，不承载审批内容；协议见参考项目 `D:\job\peoject-manager\doc\pushgo-link.md`（桌面端默认使用）。

## 功能

- **多连接** — 每台桌面端一条独立连接，各有独立频道与密码、各自的 `/remote` 接入点；项目页按电脑分组，项目卡片直接标出哪个项目有待审核 / 待回答，待办与通知都标注来源电脑。接入点由桌面端随心跳下发，无需手填。
- **扫码接入** — 扫桌面端二维码，或手动粘贴连接信息；本地命名、重命名、重新扫码、移除。
- **待办聚合** — 待审核在上、待回答在下，可按来源连接筛选；同一消息只提醒一次，过期提问不响铃、不计入角标。
- **审核与提问** — 通过 / 打回（意见必填）/ 拒绝；候选答案 + 自定义输入 + 倒计时与过期态；答案按来源连接加密回传，绝不跨连接。
- **后台保活** — 前台服务持有长轮询（`RemoteWatcher`）与接入点通道，断网指数退避重连，网络恢复立即重连；开机解锁后自动恢复。
- **通知** — 新审核 / 新提问各发一条，汇总通知「N 条待办 · M 台桌面端」；点击直达对应条目；权限被拒时会引导到系统通知设置。
- **连接诊断** — 连不上时直接说明原因（网络不通 / 密码错误 / 频道失效等），能靠重新扫码解决的就在原地给按钮。
- **自动更新** — 启动时检查 GitHub Release（6 小时内最多一次），发现新版本在「连接」页顶部提示，一键下载并调起系统安装器；下载带 SHA-256 校验，连接与设置原样保留。
- **端到端加密** — PBKDF2-HMAC-SHA256 派生 + AES-256-GCM，AAD 绑定 topic / 频道 ID；连接密钥存 EncryptedSharedPreferences。

## 页面

| # | 页面 | 作用 |
|---|---|---|
| 01 | 待办 | 多连接聚合；按来源筛选；待审核 / 待回答分组 |
| 02 | 连接 | 多台桌面端状态、添加连接、通知与关于 |
| 03 | 添加连接 | 扫码 + 本地命名；支持手动粘贴连接信息 |
| 04 | 审核交付 | 查看来源、标题、交付 agent、完成说明摘要与本次改动 diff；通过 / 打回 / 拒绝 |
| 05 | 打回意见 | 底部弹层，意见必填，快捷原因 |
| 06 | 回答提问 | 候选答案 + 自定义输入，倒计时与过期态 |
| 07 | 连接详情 | 单台桌面端的状态、会话、重命名、重新扫码、移除 |

页面细节与技术方案见 [手机端设计](doc/mobile-app-design.md)。

## 快速开始

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

### 使用

1. 桌面端打开「设置与数据 → 通知与集成 → 手机端通信」，开启通道并显示二维码。
2. 手机端点「添加桌面端」扫码；没有相机时可用「手动输入连接信息」粘贴二维码里的 JSON。
3. 给连接起名（工作电脑 / 家里电脑），添加后进入待办列表。
4. 退到后台即可；有审核或提问时会收到通知，点击直达详情。
5. 首次使用建议：
   - 允许通知权限；
   - 在「连接 → 电池优化」里设为「不限制」；
   - 国产系统再到「手机管家 / 自启动管理」里允许后台运行。

## 发布

推一个 `v` 前缀的 tag 即触发 `.github/workflows/release.yml`：跑单元测试、构建 release APK，挂到同名 GitHub Release（附 `sha256` 校验文件），手机浏览器打开 Release 页即可下载安装。

```bash
# 先把 app/build.gradle.kts 的 versionName 改成目标版本并提交，再打 tag
git tag -a v0.0.2 -m "修复息屏后通知延迟"
git push origin v0.0.2
```

- tag 去掉 `v` 必须与 `versionName` 完全一致，否则流水线直接失败；`versionCode` 由 tag 推导为 `major*10000 + minor*100 + patch`，保证新包能覆盖旧包装上。
- 签名：全仓库统一使用公开的 `keystore/project-manager.jks`，本地 debug、本机 release 与 CI Release 都是这一套密钥，包之间可以互相覆盖升级。口令写在 `app/build.gradle.kts`，不再需要配置签名 secrets。
- 密钥随仓库公开（开源项目），它只用来保证包来源一致，不具备防篡改作用；换密钥会导致已安装的旧包无法覆盖升级，只能卸载重装。


### 自动更新

手机端直接读取本仓库的 `releases/latest`：取正式 Release 里第一个 `.apk` 资产，按 tag 换算 `versionCode` 与已装版本比较。启动时自动检查（6 小时内不重复请求），「连接 → 关于 → 检查更新」可手动触发。

- 发现新版本：连接页顶部出现提示，点开弹层显示 Release 说明（取 annotated tag 注释）；「下载更新」带进度，完成后自动调起系统安装器。
- 首次更新需要允许本项目「安装未知应用」；系统安装器仍会要求用户确认，无法静默安装。
- 下载后校验同名 `.sha256` 资产（存在时）与 APK 包名，校验不过直接删除重试。
- **签名必须一致**：只能覆盖安装同签名的包。仓库统一使用 `keystore/project-manager.jks`，本地 debug 包、CI Release 包与自动更新包签名相同，可直接覆盖安装。
- 自动检查失败时静默忽略，不影响正常使用；手动检查失败会提示原因。

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

覆盖：加解密互逆、错误 topic / key、密文篡改、连接信息校验、答案 id 规则、派生密钥稳定性、通知权限判定、过期规则。

## 目录

```
app/src/main/java/com/projectmanager/mobile/
  core/        协议常量、加解密、连接信息校验、Intent 常量
  data/        连接与待办模型、加密存储、仓库与去重
  link/        PushGo 频道与设备注册、接入点心跳、远程待办长轮询（RemoteWatcher）
  notify/      通知渠道、条目通知与汇总、通知权限判定
  service/     前台服务、开机恢复
  ui/          Compose 页面、主题、ViewModel
  update/      版本检查（GitHub Release）、APK 下载校验与安装
.github/workflows/release.yml   tag 触发的 APK 发布流水线
tools/mobile-peer.mjs           本地测试桩
doc/mobile-app-design.md        页面设计与技术说明
```

## 已知限制

- PushGo Pull 原型最多 15 秒延迟；设备路由使用随机 provider token 进入 Pull 队列，`send_status` 可能显示 `partially_failed`；正式版建议切 PushGo 私有 WSS 或自建 Gateway。
- 公共 PushGo 网关没有每日条数额度，但受每分钟请求频率限制；本项目 15 秒轮询 + 每分钟心跳约 10 次/分钟，远低于限制。
- 手机端只处理「待审核」与「待回答」，不提供需求创建、历史记录；审核页可直接查看工作区改动与单文件 diff。
- 通知权限被拒绝时，待办仍会同步到列表，但不会有系统通知。
- 部分国产 ROM 需额外允许自启动 / 后台运行；应用内已提供电池优化入口与提示。
