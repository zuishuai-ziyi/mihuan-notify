# 跨设备通知转发系统（PC WebHook → 手机 → 小米手环）

[![CI](https://github.com/zuishuai-ziyi/mihuan-notify/actions/workflows/ci.yml/badge.svg)](https://github.com/zuishuai-ziyi/mihuan-notify/actions/workflows/ci.yml)

跨设备通知系统：**PC 端服务**接收第三方应用的 WebHook 调用，转发到**手机端 Android App**，App 发出系统通知，由**小米运动健康**监听并转发到**小米手环**。

支持两种投递模式，PC 端设置页可随时切换（手机端有对应模式开关）：

- **直连模式**（默认）：PC 直接 `POST http://<手机IP>:8788/notify`——要求 PC 与手机同一局域网。
- **中继模式**：PC 推送到**中继服务器**（`relay-server/`，Docker 镜像 + Web UI），手机 App 长轮询中继拉取消息——PC 与手机**无需互通**，各自只要能访问中继即可（适合异地/移动网络）。

> 关于「直接调用小米运动健康私有接口」的可行性调研结论：不可行（无公开 API，官方 SDK 仅对生态伙伴开放），故采用「App 发系统通知 + 小米运动健康监听转发」回退方案。完整调研见 [docs/research-mi-fitness-private-api.md](docs/research-mi-fitness-private-api.md)。

## 架构（直连模式）

```text
第三方应用            PC (Python/FastAPI)              手机 (Android App)        小米运动健康
    │                       │                              │                        │
    │ POST /webhook         │                              │                        │
    │ Bearer webhook_token  │                              │                        │
    ├──────────────────────>│ POST /notify                 │                        │
    │                       │ X-Auth-Token phone_token     │                        │
    │                       ├─────────────────────────────>│ 系统通知 ──────────────>│ 转发到手环
    │  200 {"ok":true,...}  │        200 {"ok":true}       │                        │
    │<──────────────────────┤<─────────────────────────────┤                        │
```

- 两段链路 token 相互独立：第三方应用只持有 `webhook_token`，`phone_token` 不出 PC。
- 中继模式下信封格式**完全不变**，仅投递路径换成「PC → 中继 → 手机长轮询拉取」，见下方「架构（中继模式）」。
- 协议细节（信封格式、错误码、字段约束）见 [docs/protocol.md](docs/protocol.md)。

### 架构（中继模式）

```text
第三方应用                PC (FastAPI)                  中继服务器 (Docker)         手机 App          小米运动健康
    │                       │                               │                        │                  │
    │ POST /webhook         │ POST /channels/{cid}/messages│                        │                  │
    │ Bearer webhook_token  │ X-Push-Token                  │                        │                  │
    ├──────────────────────>│──────────────────────────────>│ 入队                   │                  │
    │  200 {"ok":true,…}     │  200 {accepted:1}             │                        │                  │
    │<──────────────────────┤<──────────────────────────────┤                        │                  │
    │                       │                               │ GET …/messages?wait=25 │                  │
    │                       │                               │<───────────────────────┤ 长轮询拉取        │
    │                       │                               │──── {messages:[…]} ───>│ 系统通知 ───────>│
    │                       │                               │ POST …/ack {ids}       │                  │
    │                       │                               │<───────────────────────┤                  │
```

- 中继按「通道（channel）」隔离：每个通道一对 token（`push_token` 给 PC、`poll_token` 给手机），中继 Web UI 可随时重置。
- 消息带唯一 `id`，中继侧幂等入库；手机 ack 前不重复投递（App 另存最近 100 条 id 自防重）。
- 中继只做排队与投递，能看到标题/正文——公网部署请套 HTTPS 反代，详见 [relay-server/README.md](relay-server/README.md)。

## 目录结构

```text
├── docs/
│   ├── protocol.md                        # 通信协议 v1 规范（含中继模式）
│   ├── deploy-pc-exe.md                   # PC exe 版使用说明（随包附带）
│   └── research-mi-fitness-private-api.md # 小米运动健康私有接口调研
├── dist/
│   └── mihuan-notify-pc-<版本>-win64.zip  # PC 端发布包（exe 免安装，scripts/package.sh 产出）
├── pc-relay.spec                          # PyInstaller 打包配置
├── scripts/
│   └── package.sh                         # 打 PC 端发布包（webui 构建 + PyInstaller + zip）
├── relay-server/                          # 中继服务器（Docker 镜像 + Web UI，可选部署）
│   ├── main.py                            # /health、通道消息/长轮询/ack/ping 接口
│   ├── admin.py                           # 管理 API（登录/通道/消息/配置）
│   ├── store.py                           # SQLite 存储（配置 kv + 通道 + 消息队列）
│   ├── webui/                             # Vue3+Vite 管理界面（挂 /admin/ui）
│   ├── Dockerfile + docker-compose.yml    # 镜像：mihuan-relay:latest
│   ├── README.md                          # 部署/接口/安全说明
│   └── tests/smoke.sh                     # 自包含冒烟（30 用例）
├── pc-relay/                              # PC 端（Python 3.11+ / FastAPI + Vue Web UI）
│   ├── main.py                            # /webhook /health，转发与重试
│   ├── admin.py                           # Web 管理 API（登录/配置/状态/测试/最近消息）
│   ├── webui/                             # Vue3+Vite 管理界面（构建产物挂 /admin/ui）
│   ├── manage.py                          # 开机自启管理（exe/venv 双模式自动探测）
│   ├── install.sh                         # venv 源码部署安装脚本（含 ensurepip 缺失降级）
│   ├── store.py                           # SQLite 存储层（relay.db：配置 kv + 消息表，v2.1）
│   ├── requirements.txt
│   ├── deploy/                            # autostart.vbs / autostart.log（自动生成）
│   └── tests/
│       ├── e2e.sh                         # 本地 mock 自包含 E2E（33 用例：转发语义 + 应用化功能 + Web UI 渲染检查，独立临时 DB）
│       ├── e2e_relay.sh                   # 中继模式 E2E（25 用例：relay-server + PC + 模拟手机长轮询，独立临时 DB）
│       ├── mock_phone_relay.py            # 手机端「中继模式」长轮询模拟器（等价于 RelayPoller）
│       ├── e2e_full.sh                    # 真机全链路 E2E（3 条消息 + 鉴权负例）
│       ├── lan_test.sh                    # 真机连通性 4 项（ping/401/notify/去重）
│       ├── push.sh                        # 手动单发一条消息
│       └── mock_phone.py                  # 手机端模拟器（直连模式）
└── android-app/                           # 手机端（Kotlin / NanoHTTPD）
    ├── keystore/ + keystore.properties    # release 签名（gitignore，勿提交）
    ├── dist/
    │   ├── app-release.apk                # 签名版（推荐）
    │   └── app-debug.apk                  # 调试版
    └── app/src/main/java/com/mihuan/notifyrelay/
        ├── NotifyServer.kt                # 内嵌 HTTP 服务（/notify /ping，直连模式）
        ├── RelayPoller.kt                 # 中继模式长轮询拉取 + ack
        ├── NotificationHelper.kt          # 通知渠道与发布
        ├── RelayService.kt                # 前台服务（保活；按模式启动 HTTP 服务或中继轮询）
        ├── BootReceiver.kt                # 开机自启（受「应用行为→开机自启」开关控制，且停机前服务需在运行）
        ├── AppAppearance.kt               # 桌面名称/图标方案（activity-alias 切换：默认/记事本/计算器/天气）
        ├── ConfigStore.kt                 # 模式/token/port/中继配置/自启位/应用行为/外观方案/常驻通知内容持久化
        └── MainActivity.kt                # Jetpack Compose + Material3 界面（状态卡/模式切换/启停/测试/复制配置/应用行为设置/应用外观与通知/日志，Theme.kt 为配套主题）
```

## 部署步骤

### 一、手机端

1. 安装 `android-app/dist/app-release.apk`（要求 Android 8.0+，Android 13+ 需允许通知权限）
   - **从 v1.0（debug 签名）升级到 v1.1（release 签名）须先卸载旧版**——签名不同无法覆盖安装；卸载后 Token 重置，需重新「复制配置信息」并在 Web 界面更新手机端配置
2. 打开「通知转发」App → 允许通知权限 → 选**传输模式**（直连 / 中继）→ 点「启动服务」
3. **直连模式**：界面显示监听地址（`http://<手机IP>:8788`）与 Token，点「复制配置信息」得到 `[phone]` 片段，粘到 PC 端设置页「手机端」段
4. **中继模式**：填中继服务器地址（如 `https://relay.example.com`）、通道 ID、Poll Token（均来自中继 Web UI「通道」页）→ 点「保存并应用（重启服务）」；App 转为长轮询中继拉取（不再监听端口）。同时 PC 端设置页「传输模式」选「经中继服务器」并填同一通道 ID 与 Push Token
5. **小米运动健康**：我的 → 设置 → 通知使用权 → 授权「通知转发」；设备（手环）→ 消息通知 → 开启「通知转发」（或全量转发）
6. 建议：系统设置中给 App 关闭电池优化/允许后台运行，避免被杀
7. 开机自启：需满足两项——App「应用行为 → 开机自启」开关开（默认开）且停机前服务在运行；两者任一不满足（如用户手动「停止服务」）则重启后不自启

### 二、PC 端

#### 方式 A：exe 免安装（推荐）

1. 解压 `dist/mihuan-notify-pc-<版本>-win64.zip`（含 `pc-relay.exe` + `_internal/`，两者须同目录）
2. 双击 `pc-relay.exe`——首启自动生成配置并进入**配置向导**（免登录）：选传输模式（直连填手机地址/Token；中继填中继地址/Push Token/通道 ID）、设管理密码即可
3. Web 管理界面 `http://127.0.0.1:8787/admin/ui/`：状态/最近消息/测试发送/**接入指引（Webhook 模板一键复制）**/**表单化配置编辑（保存并应用）**/设置页内开机自启开关
4. 需局域网访问时在「设置」页把监听地址改 `0.0.0.0` 并点「立即重启」；详细说明见包内《使用说明.md》

#### 方式 B：源码运行（开发调试）

```bash
cd pc-relay
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt   # 国内可加 -i https://pypi.tuna.tsinghua.edu.cn/simple
.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8787
```

v2.1 起无配置文件：首启自动在同目录创建 SQLite 库 `relay.db`（kv 配置 + 消息表，已被
gitignore，勿提交；种子默认 127.0.0.1:8787 + 随机 webhook token，**全部字段在 Web 管理
界面修改**，源码部署同样支持首启免密向导）。`host`/`port` 由环境变量 `PC_RELAY_PORT`
覆写，库路径用 `PC_RELAY_DB` 指定其他路径（测试脚本均走此变量，与真实数据隔离）。
配置以 kv 形式存储：`server.host`、`server.port`、`webhook.token`、`phone.url`、
`phone.token`、`phone.timeout_seconds`、`phone.retries`、`admin.password`（PBKDF2-SHA256
哈希）、`admin.secret`（会话签名密钥，持久化）、`storage.recent_limit`（最近消息保留条数）。

### 三、中继服务器（Docker，仅中继模式需要）

```bash
cd relay-server
docker build -t mihuan-relay:latest .
docker run -d --name mihuan-relay -p 8080:8080 \
  -v relay-data:/app/data \
  -e RELAY_ADMIN_PASSWORD='改成强密码' \
  mihuan-relay:latest
```

1. 访问 `http://<服务器IP>:8080/admin/ui/` 登录（未设 `RELAY_ADMIN_PASSWORD` 时为首启免密向导，请立即补设密码）
2. 「通道」页新建一个通道（如「我的手机」）→ 分别复制 **Push Token**（给 PC）与 **Poll Token**（给手机），记下通道 ID
3. PC 端设置页「传输模式」选「经中继服务器」→ 填中继地址 / Push Token / 通道 ID → 保存并应用；手机端同理填 Poll Token
4. 状态页出现「传输模式：经中继服务器」与「中继通道：✓ 中继正常，待推送 N 条」即接通；「测试发送」可一键验通路

- 公网部署请用 Caddy/Nginx 加 HTTPS 反代并只暴露反代端口（中继自身不终止 TLS）。
- 完整说明（环境变量、接口、安全、容量）见 [relay-server/README.md](relay-server/README.md)。

### 四、PC 端开机自启（Windows）

```bash
cd pc-relay
python manage.py install-autostart             # exe/venv 自动探测（exe 优先）
python manage.py status                        # 查看状态
python manage.py uninstall-autostart           # 注销
```

- 原理：注册表 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` → wscript 静默执行 `deploy/autostart.vbs`。
  - exe 模式：vbs 直接启动 `pc-relay.exe`（读 exe 同目录 `relay.db`）
  - venv 模式：vbs → `wsl.exe` 在项目目录启动 `.venv/bin/python main.py`（日志 `deploy/autostart.log`）
- Windows / WSL 任意 Python 均可运行（调 reg.exe 需 interop）；注册的是绝对路径，移动目录后需重新注册。

### 五、打包

- PC 发布包：`bash scripts/package.sh` → `dist/mihuan-notify-pc-<版本>-win64.zip`。链路：Vite 构建 `pc-relay/webui` → PyInstaller onedir（spec: `pc-relay.spec`，datas 打包 webui/dist，excludes 排除 PIL 等）→ zip 组装（exe + `_internal/` + manage.py + 使用说明）。产物约 28MB；打完包可解包运行 `pc-relay.exe` 验证（首启在 exe 旁建 `relay.db`，或 `PC_RELAY_DB` 指定）。
- 中继镜像：`cd relay-server && docker build -t mihuan-relay:latest .`（或 `docker compose up -d`）；带 Web UI，数据在命名卷 `relay-data`。
- APK：`bash scripts/build_apk.sh [debug|release|all]`（默认 debug；构建在 ext4 构建区完成，产物自动拷至 `android-app/dist/app-<variant>.apk`）。release 签名由 `android-app/keystore.properties` 提供，缺失时自动回退 debug 签名。

## 第三方应用接入

三种方式任选（`title` 必填）：

```bash
# 1. JSON（推荐，支持 source/priority）
curl -X POST http://PC_IP:8787/webhook \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Grafana","body":"CPU > 90%","source":"grafana","priority":"high"}'

# 2. 纯文本（正文即文本，标题固定 Webhook）
curl -X POST http://PC_IP:8787/webhook \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: text/plain" \
  --data-binary "构建完成"

# 3. 查询参数（最简）
curl -X POST "http://PC_IP:8787/webhook?title=备份&body=任务完成" -H "Authorization: Bearer $TOKEN"
```

- `priority`: `normal`（默认）/ `high`（响铃+横幅，对应手机端高优先级通知渠道）
- 响应：200 `{"ok":true,"id":"…","delivered":true}`；401 token 错；400 格式错；413 >64KB；502 手机不可达（已重试 3 次）

## Web 管理界面

浏览器打开 `http://<PC_IP>:8787/admin/ui/`，用 `[admin] password` 登录（HttpOnly 会话 cookie，12h 过期）：

- **状态**：运行时长、版本、监听地址、**传输模式**（直连手机/经中继服务器）、下游连通性（直连 `GET {phone.url}/ping`，中继 `GET {relay}/channels/{cid}/ping` → “中继正常，待推送 N 条”）、消息计数；5s 自动刷新
- **最近消息**：转发成功/失败记录（SQLite 持久化，默认 200 条可在设置页改），点行展开完整正文，可一键清空
- **测试发送**：走完整链路（含重试）发一条 `source=webui-test` 的通知到手环
- **接入指引**：Webhook 接入信息与指引——接入地址为输入框+候选下拉（可手改）；curl/查询参数/JavaScript/Python 四种模板一键复制，
  登录后自动填入真实 Token（复制即可用；未登录显示 `<WEBHOOK_TOKEN>` 占位符），
  附 JSON 字段说明与响应码说明（地址数据接口 `/admin/api/webhook-info` 免鉴权；token 明文经 `GET /admin/api/webhook-token` 需登录下发）
- **设置**：**表单化编辑全部配置**（schema 驱动；token 打码显示、留空=不修改、显示/隐藏切换明文、🎲 一键生成随机值；含最近消息保留条数与开机自启开关）。**中继模式**：传输模式（直连/经中继服务器）+ 中继地址 + Push Token + 通道 ID（三项齐全才视为配置完成，可随时切回直连）。除监听地址/端口外，**保存并应用**即热生效；地址/端口变更后出现「⟳ 立即重启」按钮一键重启
- **首启向导**：无 `relay.db` 时服务自动建库（仅监听 127.0.0.1 + 随机 webhook token），首次访问免密进入向导，三步完成手机连接（或中继通道）→管理密码→保存

## 测试

| 脚本 | 覆盖 |
|---|---|
| `pc-relay/tests/e2e.sh` | 本地 mock 手机：鉴权/三模式/413/重试/502 + 应用化功能（首启建库/免密向导/表单热更新/密码哈希落库/打码保留/recent_limit/重启链路/自启查询）+ **Web UI 渲染检查**，33 用例（独立临时 DB，不动真实 relay.db） |
| `pc-relay/webui/tests/render_check.mjs` | Web UI 无浏览器渲染检查 8 项（用 Vue 自定义渲染器让 `onMounted` 真正执行）：设置页**开机自启区块**必须渲染出开关、secret 字段的眼睛图标在输入框内、Login 页 `pw-wrap` 结构等。专抓“定义了但没被调用”的静默 bug（SSR 不跑 `onMounted`，抓不到）。`e2e.sh` 会自动调用；需 node（`NODE_BIN=/path/to/node` 可指定，缺省则跳过） |
| `pc-relay/tests/e2e_relay.sh` | **中继模式全链路**：relay-server + PC(中继) + 模拟手机长轮询，25 用例（免密配置中继/入队/长轮询拉取+ack/已送达/状态与连通性文案/错 token→502/切回直连回归/两侧 WebUI） |
| `relay-server/tests/smoke.sh` | 中继服务器自包含冒烟 30 用例（鉴权/建通道/推送幂等与批量/长轮询/ack/token 轮换/删除/错误码/配置/测试发送）；用 `RELAY_PYTHON` 指定解释器 |
| `pc-relay/tests/e2e_full.sh` | 真机全链路：三种模式各 1 条真实消息 + 鉴权负例，手环需人工确认 |
| `pc-relay/tests/lan_test.sh` | 真机连通性 4 项：ping/401 负例/notify/去重 |
| `pc-relay/tests/push.sh 标题 正文 priority` | 手动单发 |

Web UI / admin API 冒烟：起服务后浏览器过一遍四 tab，或 `curl` 验证 `/admin/login` → `/admin/api/status` → `/admin/ui/` 200。

```bash
bash pc-relay/tests/e2e_full.sh
```

## 已知限制与注意事项

- 手机与 PC 必须同一局域网；**建议在路由器为手机绑定静态 IP**（DHCP 租约变化会导致 PC 端连不上）。
- 手机 IP 变化后：App「复制配置信息」重新生成片段，在 Web 界面「配置」页更新手机地址/Token，保存即生效。
- 重新安装 App 后如通知失效，检查两项：系统通知权限、小米运动健康的授权与消息通知开关。
- 手机端 `token` 重新生成后，在 Web 界面「配置」页同步更新手机 Token（保存即生效）。
- App 版本升级若签名不同（如 debug→release）必须卸载重装，Token 会重置。
- App 主界面默认**不出现在最近任务列表**（设置页「应用行为 → 在最近任务中隐藏」可关，v1.2 起默认隐藏，v2.3 起可配置）：后台服务不受影响，重新打开请点桌面图标。
- App「应用行为」可自定义：开机自启开关、在最近任务中隐藏开关、日志保留条数（40/100/200/500）与清空日志。
- 消息长度：标题 ≤64 字符、正文 ≤4096 字符，超出由 PC 端自动截断。
- Web 管理界面建议仅在内网使用：`[admin] password` 为唯一认证（会话 cookie HMAC 签名）；如需公网暴露请自行加反代+额外认证。
- 手环端只接收文本（标题+正文）；带图片/富文本的通知不在支持范围。
- **中继模式**：PC 与手机**无需同网**，但手机必须能访问中继（移动网络/公网）；App 每轮长轮询最长 25s，空转时约 1.5s 后重发，长期在线会有轻微耗电/流量。
- 中继能看到消息**标题与正文**（仅 TLS 保护链路），敏感内容请自行评估或自建中继并限权；公网部署务必加 HTTPS 反代 + 强管理密码。
- 中继模式下 PC 状态页检测的是**中继通道**而非手机本身（“中继正常”仅表示入队通道可用）。
- 通道 `push_token`/`poll_token` 泄露时，在中继 Web UI「通道」页单独重置，并同步更新 PC/手机配置（保存即生效）。
- 前台服务自身通知已做静默 + localOnly 处理，设计上不会出现在手环。

## 文档与许可

| 文档 | 内容 |
|---|---|
| [docs/protocol.md](docs/protocol.md) | 通信协议 v1 规范（信封格式、错误码、中继模式） |
| [docs/deploy-pc-exe.md](docs/deploy-pc-exe.md) | PC exe 版使用说明 |
| [docs/research-mi-fitness-private-api.md](docs/research-mi-fitness-private-api.md) | 小米运动健康私有接口调研 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 开发环境搭建、测试、提交与 PR 规范 |
| [CHANGELOG.md](CHANGELOG.md) | 各组件版本演进记录 |
| [relay-server/README.md](relay-server/README.md) | 中继服务器部署/接口/安全说明 |
| [android-app/README.md](android-app/README.md) | 手机端说明 |

各组件版本号：PC `2.2.3` · Android `2.2` · 中继服务器 `1.0.1`，版本历史见 [CHANGELOG.md](CHANGELOG.md)。

本项目以 [MIT License](LICENSE) 开源。欢迎通过 Issue 反馈问题、按 [CONTRIBUTING.md](CONTRIBUTING.md) 提交 PR。

## CI/CD 与发布

- **CI**（[.github/workflows/ci.yml](.github/workflows/ci.yml)）：推送到 `main` 或提 PR 时自动运行——PC 端 e2e（33 + 25 用例，含 WebUI 渲染检查）、relay-server 冒烟（30 用例）、Android debug/release 双包构建、中继镜像构建检查。
- **发布**（[.github/workflows/release.yml](.github/workflows/release.yml)）：推 `v*` tag 自动构建并创建 [GitHub Release](https://github.com/zuishuai-ziyi/mihuan-notify/releases)，产物：
  - `mihuan-notify-pc-<版本>-win64.zip`——PC 端免安装包（Windows runner PyInstaller 打包）
  - `mihuan-notify-android-<版本>-{release,debug}.apk`——手机端（配置签名 secrets 则 release 签名，否则回退 debug 签名）
  - Docker 镜像 `ghcr.io/zuishuai-ziyi/mihuan-relay:{版本,latest}`（中继服务器）

发布操作：更新各组件版本号 → 提交 → `git tag v<版本> && git push origin v<版本>`，其余全自动（详见 [CONTRIBUTING.md](CONTRIBUTING.md) 发布流程）。
