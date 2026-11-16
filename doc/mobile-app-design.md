---
date: 2026-11-14
description: 项目管理台手机端（Android + Compose，多桌面端连接）页面设计、协议对接与实现记录
---

# 项目管理台 · 手机端页面设计（多连接）

> 状态：**已确认并实现**。页面原型见 `design/mockup.html`（含深浅两套主题），实现与验证记录见文末「Complements」；2026-11-14 追加 PushGo Pull 原型（见第 11 节）。

## 1. 需求与范围

参考 `项目管理器` 最新提交 `f42b2e6 feat: 新增手机端通信（ntfy + 端到端加密）`，手机端作为独立客户端接入：

- 扫描桌面端二维码拿到 PushGo 网关 / 频道 / 密码（`v2` 连接信息）；
- 接收两类待办：**待审核**（`review`）与**待回答**（`question`）；
- 在手机上完成：通过 / 打回 / 拒绝交付，或回答提问；
- 答案加密回传桌面端，落库后桌面端状态随之变化；
- 双向每分钟 `keepalive`，两端都能判断在线。

**本版新增（按你的答复）**：手机端要同时连接**多台桌面端**。每台桌面端是独立连接，有自己的 `server / topic / key`、独立订阅与在线判定；待办聚合展示并标注来源，答案按来源连接回传，连接之间互不影响。

本轮不做：桌面端改动、历史记录、需求创建、命令执行、diff 查看。

## 2. 设计文件

| 文件 | 说明 |
|---|---|
| `design/mockup.html` | 高保真原型，390 × 844，7 屏 + 2 张规则说明卡，右上角可切换深浅主题 |
| `design/screenshots/overview-dark.png` | 深色主题总览 |
| `design/screenshots/overview-light.png` | 浅色主题总览 |

视觉沿用桌面端 iOS 风格 token（`web/src/styles/tokens.css` / `theme-dark.css`）：系统字体、8pt 栅格、卡片 16dp 圆角、语义色（蓝 `#0A84FF`、绿 `#30D158`、橙 `#FF9F0A`、红 `#FF453A`）。手机端使用 Material 3 承载。

## 3. 页面清单

| # | 页面 | 作用 |
|---|---|---|
| 01 | 待办列表 | 多连接聚合；按来源筛选；待审核 / 待回答分组 |
| 02 | 连接管理 | 多台桌面端状态、添加连接、通知与关于 |
| 03 | 添加连接 | 扫码 + 本地命名；支持手动粘贴连接信息 |
| 04 | 审核交付 | 查看来源、标题、交付 agent、完成说明摘要；通过 / 打回 / 拒绝 |
| 05 | 打回意见 | 底部弹层，意见必填，快捷原因 |
| 06 | 回答提问 | 候选答案 + 自定义输入，倒计时与过期态 |
| 07 | 连接详情 | 单台桌面端的状态、会话、重命名、重新扫码、移除 |

原型另附两张说明卡：多连接规则、状态与边界。

## 4. 页面细节

### 01 待办列表

- 顶部：标题「待办」+ 总条数；下方连接状态行，最多展示每台连接的在线 / 具体失败原因（密码错误、网络不通等）。
- 筛选 chips：全部 + 每台连接名（带条数），可按连接过滤。
- 分组：待审核在上、待回答在下；组内待审核按 `completedAt` 倒序，待回答按 `expiresAt` 升序。
- 卡片字段（只使用协议实际下发的数据）：
  - 类型标签、`连接名 · 项目名 #seq`、相对时间；
  - 需求标题；
  - 摘要：审核取 `note`，提问取 `text`，最多两行；
  - 底部：交付 / 提问的 agent 名；提问额外显示剩余时间。
- 空状态：「暂时没有待处理的事项」+ 说明只会在等待审核 / 等待回答时出现。
- 手动刷新：点右上角刷新，对所有连接重新订阅 `since=10m` 补收。
- 底部导航：待办（带待办数角标）/ 连接。
- 断线：连接状态行标具体原因（见 6.1），自动重连（2s 起指数退避到 30s）；该连接的条目仍可查看，但操作按钮禁用。

### 02 连接管理

- 标题「连接」+ 右上角添加；状态行显示在线 / 未连接的连接数、需重新扫码的台数与总待办数。
- 连接卡片：本地名称、状态短标签（在线 / 等待心跳 / 密码错误 / 频道失效 / 网络不通 / 桌面端离线）、服务器域名、该连接待办数、连不上时副行显示具体原因、正常时显示上次同步时间；点击进入连接详情。
- 「添加桌面端」：进入扫码页。
- 通知：新审核与提问提醒开关（多连接汇总成一条通知）。
- 关于：加密方式、版本、协议版本。
- 底部导航：待办（角标）/ 连接。

### 03 添加连接

- 全屏扫码：取景框 + 扫描线；提示到桌面端「设置与数据 → 通知与集成 → 手机端通信」。
- 扫码成功 → 底部弹层「命名这台桌面端」：
  - 名称只在本机显示，默认「桌面端 N」，可改；
  - 显示服务器域名与打码后的 topic，便于核对；
  - 取消 / 添加并连接。
- 校验连接信息：JSON `{"v":1,"type":"pm-mobile","server","topic","key"}`，`topic`、`key` 正则与 `server` http/https 校验。
- 重复检测：同一 `server + topic + key` 已存在时提示「这台桌面端已连接」，直接打开已有连接。
- 手动输入：顶部「手动输入」打开粘贴弹层，规则同上。

### 04 审核交付

- 顶部返回 + 标题「待审核」。
- 元信息：`连接名 · 项目名 #seq`，交付 agent（`agentName`，缺省用 `agent`），完成时间。
- 需求标题；「完成说明」= `note`。
- 截断提示：`note` 以 `…` 结尾时显示「说明已按 500 字截断，完整内容请到桌面端查看」。
- 明确提示：完整说明、diff 与提交记录只在桌面端可见（协议不携带这些数据）。
- 底部操作：
  - **通过**：主按钮（绿），提交 `decision=approve`；
  - **打回**：打开 05 弹层，提交 `decision=feedback`，`text` 必填；
  - **拒绝**：二次确认弹层，可填写可选说明，提交 `decision=reject`。
- 结果只回传给该需求所属连接。

### 05 打回意见（底部弹层）

- 标题「打回这条交付」，说明中带连接名：「意见会通过『工作电脑』这条连接加密发给 agent」。
- 快捷原因 chips：功能不完整 / 与需求不符 / 存在 bug / 补充测试，点击填入输入框。
- 输入：多行，必填，上限 2000 字符，右下角计数。
- 按钮：取消 / 打回并退回进行中（输入为空时禁用）。

### 06 回答提问

- 顶部返回 + 「待回答」+ 倒计时胶囊（`expiresAt`）。
- 上下文：`连接名 · 项目名 #seq`，「Qoder 在『需求标题』中提问」。
- 问题正文 `text`；少于 300 字时正常展示，被截断时同样提示到桌面端查看。
- 候选答案：`options[]` 单选列表；`default` 命中的候选标「推荐」并默认选中。
- 自定义回答：多行输入，上限 2000；选中候选会把候选文案填入输入框，手动编辑后取消候选选中态。
- 提交：`decision=answer`，必须带 `questionId` 与非空 `text`；按来源连接回传。
- 过期：超过 `expiresAt` 后输入与提交禁用，顶部显示「已过期」，并提示到桌面端回答。
- 协议只支持单个回答文本，没有多选。

### 07 连接详情

- 顶部返回 + 连接名 + 「更多」菜单。
- 状态卡：桌面端在线 / 离线、最后活动时间、待推送数（服务端 `keepalive.pending`）、上次接收、本次连接时间。
- 状态卡里的失败说明：连不上时追加一块「下一步 + 传输层原文（小字，最多三行）」，类别见 6.1；需要换密码 / 换频道的类别直接在这块里给「重新扫码」按钮，不必滚到下方操作区。连接正常但始终没有心跳时，按「桌面端离线」提示先确认电脑上的项目管理台在运行。
- 会话：网关、频道（中间打码）、会话建立时间、后台接收状态。
- 操作：
  - **重命名**：只改本地名称；
  - **重新扫码**：只覆盖这一台连接的 topic / key；
  - **移除连接**：二次确认，清除该连接的密钥与本地待办缓存，不影响其他连接。

## 5. 多连接规则

1. **独立会话**：每台桌面端一条连接，保存 `id / 本地名称 / server / topic / key（加密）/ addedAt`；各自注册设备、各自重连退避。
2. **聚合展示**：待办统一进入列表，卡片始终显示来源连接名；顶部可按连接筛选。
3. **原路回传**：答案消息使用来源连接的 topic + key 加密发送，绝不跨连接。
4. **去重键**：`连接 id + 消息 id`；同一条审核 / 提问在不同连接下互不覆盖。
5. **答案幂等**：每条待办的答案 id 单独保存；未生效重发复用同一 id。
6. **断线隔离**：单条连接断线只影响自己的条目；重连退避 2s → 30s，其他连接照常。
7. **通知聚合**：一条通知汇总「N 条待办 · M 台桌面端」，标题带连接名，点击进入对应条目。
8. **前台服务**：一个前台服务持有 N 条订阅流 + 每分钟一次 keepalive；退出应用仍可接收；开机解锁后自动恢复。
9. **移除清理**：移除连接时取消订阅、清空该连接的缓存与密钥；其他连接不受影响。
10. **数量**：不设硬上限，界面按 2–5 台设计；连接列表可滚动。

## 6. 状态与边界

### 6.1 连接失败的具体原因

传输层的异常统一归类（`link/LinkFailures`），列表用短标签、详情用完整说明，用户不必回桌面端确认：

| 类别 | 触发 | 列表标签 | 下一步 | 引导重新扫码 |
|---|---|---|---|---|
| `NETWORK` | DNS 失败、连不上网关、超时 | 网络不通 | 检查网络，网络恢复自动重连 | 否 |
| `DESKTOP_OFF` | 网关已连上但没有心跳 | 桌面端离线 | 确认电脑上的项目管理台在运行 | 否 |
| `PASSWORD` | 网关 401 / 403，或错误信息指向密码 | 密码错误 | 桌面端换过配对信息，扫码取新密码 | 是 |
| `CHANNEL` | 网关 404，或错误信息指向频道 | 频道失效 | 桌面端换过频道，扫码重新绑定 | 是 |
| `GATEWAY` | 网关 5xx / 限流 / 其他 4xx | 网关报错 | 自动重试，长期不好则重新配对 | 否 |
| `CONFIG` | 本地缺频道或密码 | 配对缺失 | 重新扫码补齐 | 是 |
| `UNKNOWN` | 其余异常 | 连接异常 | 自动重试 | 否 |

判定是纯函数（`LinkFailures.kindOf`），单测覆盖各错误码与常见网络异常；界面只认类别，不自己拼字符串。

| 场景 | 行为 |
|---|---|
| 消息重复 / 重连重放 | 按「连接 id + 消息 id」去重（每连接滑动窗口 200 条） |
| 内容变化 | 同一 id 内容变了会立即重发；手机端按 id 覆盖旧内容 |
| 已提交未生效 | 条目仍在推送 → 60 秒后用同一答案 id 自动重发；条目停止推送 → 标记完成并移出待办 |
| 提交失败 / 离线 | 操作禁用，保留本地待办；恢复连接后允许再次提交（复用同一答案 id） |
| 提问过期 | 本地按 `expiresAt` 判断；过期条目标灰、不可作答 |
| 摘要截断 | 以 `…` 结尾时提示完整内容在桌面端 |
| 解不开的消息 | 不是 `{` 开头或解密失败一律静默丢弃（同一 topic 可能被其他工具复用） |
| 会话轮换 | 桌面端换 topic / key 后旧消息全部解不开；表现为该连接长时间无消息，需到连接详情重新扫码 |
| 重复添加 | 相同 `server + topic + key` 不新增，提示并定位已有连接 |
| 连接名重复 | 允许，但列表中用服务器域名 + topic 尾号区分 |

## 7. 协议对接要点（ntfy 已移除，仅存历史）

> 2026-11-16 起 ntfy 彻底移除：手机端只识别 v2 连接信息；审批与提问改走桌面端 `/remote` 长轮询。PushGo 规格见第 11 节。

- **连接信息**：`{"v":1,"type":"pm-mobile","server","topic","key"}`；`topic ^pm-[a-z2-9]{16}$`，`key ^[a-z2-9]{32}$`。
- **派生**：PBKDF2-HMAC-SHA256，`password=key`，`salt="pm-mobile-v1:"+topic`，100000 次，32 字节。
- **信封**：AES-256-GCM，12 字节随机 IV，16 字节 tag，AAD 为 topic 原文；body `{"v":1,"n":"<iv base64>","d":"<密文+tag base64>"}`。
- **明文**：`{v:1, type, from, id, at, payload}`；手机端只处理 `from=server` 的 `review` / `question` / `keepalive`。
- **订阅**：`GET {server}/{topic}/json?since=10m`，逐行解析 NDJSON，只处理 `event === "message"`。
- **发布**：`POST {server}/{topic}`，`Content-Type: text/plain; charset=utf-8`，`X-Ttl: 600`，`X-Priority: 3`；有令牌时 `Authorization: Bearer <token>`；信封超过 4KB 不下发。
- **答案**：`id` 正则 `^answer:[A-Za-z0-9_-]{6,64}$`，格式 `answer:<时间戳 base36><10 位随机>`；同一条重发必须复用。
- **keepalive**：每连接每分钟发 `keepalive:client:<毫秒时间戳>`，payload `{online:true}`；桌面端 150 秒收不到即判离线。
- **去重**：桌面端传输层与领域层各记 500 条；手机端自身也按连接 + id 去重，避免重连回放刷屏。

## 8. 技术方案（已实施）

- **技术栈**：Kotlin + Jetpack Compose + Material 3，单模块；`minSdk 26`、`compileSdk 35`、`targetSdk 34`。
- **构建**：AGP 8.10.1 + Gradle 8.14.4 + JDK 21。
- **关键依赖**：Navigation Compose、ViewModel + StateFlow、kotlinx.serialization、OkHttp（NDJSON 流式订阅）、androidx.security-crypto（连接密钥）、ZXing embedded（扫码，不依赖 GMS）。
- **分层**：
  - `core`：协议常量、PBKDF2 派生、AES-GCM 封包 / 解包、连接信息校验；
  - `data`：连接与待办模型、EncryptedSharedPreferences 存储、仓库与去重；
  - `link`：每连接一条接入点心跳通道（PushGo）+ 常驻长轮询（RemoteWatcher），重连退避；
  - `service`：前台服务（`remoteMessaging`）持有 N 条流、聚合通知、开机恢复；
  - `ui`：待办、连接、添加连接、审核详情、提问详情、连接详情 + 对应 ViewModel。
- **答案重发**：为每条待办保存最后提交的答案 id 与时间；仍在推送且超过 60 秒未生效时按原 id 重发。
- **持久化**：连接密钥、待办、通知去重集合统一加密落盘，使用同步 `commit` 防止后台被杀时丢状态。
- **验证方式**：单元测试覆盖加解密互逆、信封校验、v1 连接信息拒绝；模拟器（AVD `pctest`）安装验证 UI；本机桌面端 + `/remote` 做端到端联调。

## 9. 开发前置条件（已就绪）

| 项 | 位置 / 版本 |
|---|---|
| Android SDK | `D:\Android\Sdk`（platform 35 / 36，build-tools 35 / 36，platform-tools 36.0.2） |
| JDK | `D:\Android\Studio\jbr`，OpenJDK 21.0.9 |
| Gradle | 已缓存 8.14.4（`~/.gradle/wrapper/dists/gradle-8.14.4-bin/.../gradle-8.14.4`）；项目内生成 wrapper |
| 模拟器 | AVD `pctest` |

构建时设置 `JAVA_HOME=D:\Android\Studio\jbr`，`local.properties` 写 `sdk.dir=D:\\Android\\Sdk`。

## 10. 确认与实现记录

- 用户确认：「确认，按此设计开始开发，需要注意，后台保活，通知是重点，不能指望用户天天盯着手机看」。
- 前台服务最终使用 `remoteMessaging` 类型：语义匹配，且允许开机恢复，不受 Android 15 `dataSync` 6 小时上限影响。
- 「后台接收」落地为：前台服务 + 常驻通知 + 断网指数退避 + 网络恢复立即重连 + 开机解锁后自动恢复 + 电池优化引导。

## 11. PushGo Pull 原型（2026-11-14 追加）

- **连接信息 v2**：`{"v":2,"type":"pm-mobile","provider":"pushgo","gateway","channel","password"}`；`password` 同时作为频道密码和 AES-GCM 密钥，`channel` 作为 AAD。
- **手机端**：`PushGoClient` 注册设备、设置 `fcm` 路由（随机 provider token，进入 Pull 队列）、订阅频道；`PushGoSession` 每 15 秒 `/v2/messages/pull`（翻页）、解密、落待办、按 `delivery_id` ACK；每分钟发 keepalive。
- **桌面端**：`electron/services/mobile-link.js` 改为 PushGo Pull；发送审核/提问时把既有 AES 信封放进 `ciphertext`，`op_id` 使用稳定消息 id；Pull 到手机答案后落库，并通过 keepalive 的 `resolvedIds` 通知手机移除条目。
- **桌面端设置**：`doc` 中「手机端通信」弹窗显示 PushGo 网关、频道、密码、二维码；二维码 v2 供手机扫码/粘贴。
- **验证**：`npm run check`、`npm run build` 通过；真实公共网关完成「审核 → 手机 Pull/ACK → 通过 → 桌面端收到答案 → resolved → 手机端移除」；模拟器 API 35 实机联调通过。
- **限制**：Pull 原型最多 15 秒延迟；设备路由用假 FCM token 进入 Pull 队列，`send_status` 可能显示 `partially_failed`；正式版应切 PushGo 私有 WSS 或自建 Gateway。公共网关没有每日条数额度，但受每分钟请求频率限制。

## Complements

### 1. 多连接与接入 ✅

- 变更文件：`core/Protocol.kt`、`data/Models.kt`、`data/SecureStore.kt`、`data/AppRepository.kt`
- 实现：协议加解密、连接信息校验、多连接独立会话、按「连接 id + 消息 id」去重、答案 id 幂等保存与重发。
- 验证：`ProtocolTest` 覆盖加解密互逆、错误 topic / key、密文篡改、连接信息校验、答案 id 规则、派生密钥稳定性，全部通过。

### 2. ntfy 传输与前台服务 ✅

- 变更文件：`link/NtfyClient.kt`、`link/LinkManager.kt`、`notify/Notifier.kt`、`service/MobileLinkService.kt`、`service/BootReceiver.kt`、`PmApp.kt`
- 实现：每连接一条 NDJSON 流、2s→30s 退避重连、150 秒读超时、每分钟 keepalive、答案未生效自动重发、条目停止推送后清理、聚合通知与点击直达、开机恢复。
- 验证：模拟器 API 35 + 真实 ntfy.sh + 本地测试桩：
  - 两台桌面端同时在线，待办聚合与来源筛选正常；
  - 审核「通过」「打回（带意见）」、提问「回答」均成功回传，且只发往来源连接；
  - 退到后台仍收到新待办通知；通知组展开后点击可直达对应详情；
  - 重启模拟器并在解锁后，BootReceiver 自动拉起 `remoteMessaging` 前台服务，无需打开 App；
  - 服务端停止推送的条目在 180 秒后自动移出待办。

### 3. Compose 页面 ✅

- 变更文件：`ui/*.kt`、`MainActivity.kt`、`res/*`
- 实现：待办、连接、扫码/手动输入、命名、审核、打回弹层、提问、连接详情、设置区；深浅主题跟随系统。
- 验证：模拟器截图见 `design/screenshots/app-*.png`。

### 4. 文档与测试桩 ✅

- 新增 `README.md`、`tools/mobile-peer.mjs`；`doc/mobile-app-design.md` 更新为设计 + 实现记录。
- 测试桩支持指定 `--topic/--key` 复用连接、推送审核 / 提问、打印手机回传答案，便于后续联调。

### 5. PushGo Pull 后台接收（2026-11-15）✅

- 现象：桌面端已提交消息，手机端没有通知，打开 App 才看到待办。
- 根因 1：Pull 轮询靠 15 秒定时器推进，息屏后系统挂起 CPU、定时器停摆（模拟器实测：息屏 150 秒内应用 0 个网络包），消息只能等点亮屏幕 / 打开 App 才被拉走。
- 根因 2：前台服务在 `Application.onCreate` 里启动，进程在后台重建时被系统拒绝（`ForegroundServiceStartNotAllowedException`），服务起不来。
- 实现：`service/MobileLinkService.kt` 在服务存活期间持有 `PARTIAL_WAKE_LOCK`（`pm-mobile:link`），销毁时释放；`MainActivity.onStart` 在前台拉起服务，`PmApp.onCreate` 不再尝试后台启动；`AndroidManifest.xml` 增加 `WAKE_LOCK` 权限。
- 验证：`assembleDebug`、`testDebugUnitTest` 通过；模拟器 API 35 冷启动日志 `Background started FGS: Allowed ... uidState: TOP`，息屏后 120 秒仍有 30 个收包（修复前 150 秒 0 个），force-stop 后唤醒锁正常释放；真实桌面端投递通知未在本轮复测。

### 6. 后台仍不通知的续修（2026-11-15）✅

- 现象：App 退到后台仍然收不到通知，只有打开 App 才看到待办（上一轮的息屏唤醒锁没解决）。
- 定位：收到即通知的链路本身没问题（打开 App 后服务启动、立刻 Pull 并落待办，说明积压消息都在队列里）；差别在于**后台时进程/服务还在不在、能不能联网**。上一轮把服务启动挪到 Activity 前台后留下一个缺口：系统在后台重启服务时 `startForeground` 可能被拒，原实现直接 `stopSelf()`，后台接收从此停摆直到用户再打开 App。
- 实现：`MobileLinkService` 先拉起 `LinkManager` 再尝试进入前台，失败只记日志、不终止服务，本轮仍会 Pull 并发通知，下次 `onStartCommand` 重试进入前台；`ConnectionsScreen` 在已有连接且未加入电池优化白名单时弹一次引导（`batteryPromptShown` 记录，只弹一次），可直接跳到系统授权页。
- 验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest` 通过；真机后台行为未在本轮复测（当前没有连接设备）。
- 剩余限制：Doze 期间未加白的应用网络会被挂起，国产 ROM 还可能直接清理后台进程，这两项只能由用户在系统里放行（电池优化 / 自启动管理）。

### 7. 通知权限「去开启」落到系统设置（2026-11-16）✅

- 现象：系统里关掉通知后，「通知权限未开启」卡片的「去开启」在 Android 12 及以下点了没反应；Android 13+ 二次拒绝后也只是静默返回。
- 定位：原实现只在 `SDK >= 33` 且未授权时调一次 `RequestPermission` launcher。12 及以下根本没有这个运行时权限，整个分支被跳过；13+ 二次拒绝（或勾了不再询问）后系统不再弹框，`launch` 静默返回 false。
- 实现：新增 `notify/NotificationAccess.kt`——`decide()` 按「SDK、运行时授权、应用通知是否开启、是否请求过、系统是否仍允许询问」给出三种动作（`RUNTIME_DIALOG` / `SYSTEM_SETTINGS` / `NONE`）；`openAppNotificationSettings()` 依次尝试应用通知设置页 → 应用详情页 → 系统设置首页；`openChannelNotificationSettings()` 供「提醒被系统静音」复用。`ConnectionsScreen` 的「去开启」按判定结果弹框或跳设置，被拒回调里弹「通知仍是关闭的」提示（可一键去设置）；`MainActivity` 启动时的自动请求记为「已请求过」，保证二次拒绝后第一次点「去开启」就能进设置页。
- 验证：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`（新增 `NotificationAccessTest` 6 组决策断言）、`:app:assembleDebug` 通过；无连接设备，真机点击路径未在本轮复测。

### 已知限制

- 被用户「强行停止」后系统不会自动恢复服务（Android 限制）。
- 部分国产 ROM 需额外允许自启动 / 后台运行；应用内已提供电池优化入口与提示。
- 首次冷启动时 EncryptedSharedPreferences / Keystore 初始化在低端模拟器上较慢；真机通常明显更快。
- 自建 ntfy 若开启鉴权，当前二维码不携带令牌，手机端只按协议使用 server / topic / key。
