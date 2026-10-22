# 通信协议 v1（PC ↔ 手机）

> task-2 产出 · 架构：默认**直连**（手机端运行 HTTP 服务，PC 主动推送）；v2.2 起新增**中继模式**（§9）：PC 推送到中继服务器，手机 App 长轮询拉取，二者无需互通。
> 直连链路：第三方应用 → `POST PC:8787/webhook` → PC 转发 → `POST 手机:8788/notify` → Android 通知 → 小米运动健康转发 → 手环。
> 中继链路：第三方应用 → `POST PC:8787/webhook` → PC → `POST 中继:8080/channels/{cid}/messages` → 手机长轮询拉取 → 同上前半段之后。
> **信封（§3）在两种模式下完全一致**，端侧仅替换传输层。

## 1. 总览

```text
第三方应用            PC (Python/FastAPI)             手机 (Android App)
    │                       │                              │
    │ POST /webhook         │                              │
    │ Bearer <webhook_token>│                              │
    ├──────────────────────>│  POST /notify                │
    │                       │  X-Auth-Token <phone_token>  │
    │                       ├─────────────────────────────>│
    │                       │                              │ 发系统通知
    │   200 {"ok":true,...} │      200 {"ok":true}         │ → 小米运动健康 → 手环
    │<──────────────────────┤<─────────────────────────────┤
```

- 两段链路使用**不同 token**：第三方应用只知道 `webhook_token`，手机 token 不外泄给第三方。
- 两个服务默认端口：PC `8787`，手机 `8788`（均可配置）。

## 2. PC 端 WebHook 接口（供第三方应用调用）

### 2.1 `POST /webhook`

| 项 | 规则 |
|---|---|
| 鉴权 | 请求头 `Authorization: Bearer <webhook_token>`，不匹配返回 401 |
| Content-Type | `application/json` 或 `text/plain` |
| JSON 字段 | `title`(必填, string)、`body`(必填, string)、`source`(可选, string)、`priority`(可选, `normal`/`high`, 默认 `normal`)；未知字段忽略 |
| 纯文本回退 | `text/plain` 时：`title="Webhook"`，`body=原始文本` |
| 查询参数回退 | `POST /webhook?title=xx&body=yy`（便于 curl/脚本快速接入），body 可为空 |
| 大小限制 | 请求体 ≤ 64KB，超限 413 |
| 超时/重试 | 转发手机超时 5s；失败重试 3 次（间隔 1s、3s）；最终失败返回 502 |

### 2.2 响应

```json
// 200 成功送达手机
{"ok": true, "id": "550e8400-...", "delivered": true}
// 502 手机不可达（重试耗尽）
{"ok": false, "error": "phone_unreachable", "attempts": 3}
// 400 / 401 / 413
{"ok": false, "error": "<reason>"}
```

### 2.3 其他端点

- `GET /health` → `{"ok":true,"service":"pc-relay"}`（无鉴权，探活）。

## 3. PC → 手机 消息格式（信封 v1）

`POST http://<phone-ip>:8788/notify`，请求头 `X-Auth-Token: <phone_token>`，`Content-Type: application/json`。

```json
{
  "v": 1,
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "ts": "2026-09-30T23:59:59+08:00",
  "title": "GitHub",
  "body": "PR #42 was approved",
  "source": "github",
  "priority": "normal"
}
```

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `v` | int | ✓ | 协议版本，当前必须为 `1` |
| `id` | string | ✓ | UUID v4，由 PC 生成；手机按 id 去重（保留最近 100 条） |
| `ts` | string | ✓ | ISO8601 含时区，由 PC 生成 |
| `title` | string | ✓ | ≤64 字符，PC 端截断；显示为通知标题 |
| `body` | string | ✓ | ≤4096 字符，PC 端截断；显示为通知正文 |
| `source` | string | ✗ | 来源标识（如 `github`、`grafana`），可显示在通知上 |
| `priority` | string | ✗ | `normal`(默认)/`high`：`high` 时手机用高优先级通知通道 |

### 3.1 手机端响应

| 状态码 | 场景 | 响应体 |
|---|---|---|
| 200 | 送达（含重复 id：`{"ok":true,"duplicate":true}`） | `{"ok":true}` |
| 400 | 信封非法（缺 `v`/`id`/`title`/`body`、类型错误、`v≠1`） | `{"ok":false,"error":"<reason>"}` |
| 401 | token 缺失或不匹配 | `{"ok":false,"error":"unauthorized"}` |
| 413 | 请求体 > 64KB | `{"ok":false,"error":"too_large"}` |
| 500 | 内部错误 | `{"ok":false,"error":"internal"}` |

### 3.2 其他端点

- `GET /ping` → `{"ok":true,"service":"phone-relay","v":1}`（无鉴权无敏感信息，供 PC 探活/自动发现验证）。

## 4. 鉴权约定

- `webhook_token` / `phone_token`：均为静态共享密钥字符串，建议 ≥32 位随机字符（如 `openssl rand -hex 32` 生成）。
- 比较**恒定时间**执行（避免时序侧信道）。
- token 存于 `relay.db`（SQLite，v2.1 起）。数据库不入版本库（`.gitignore`），且包含明文 token，勿外传。

## 5. PC 端配置（task-3 实现约定）

v2.1 起**不再读取 `config.toml`**：配置首启自动建库（`relay.db`），全部在 Web 管理界面
（`http://127.0.0.1:8787/admin/ui/` → 「设置」页）修改并保存即生效。

| 配置项（设置页） | 说明 |
|---|---|
| 传输模式 | `direct`＝直连手机局域网（默认）；`relay`＝经中继服务器 |
| 手机端地址 / Token | **直连模式**必填（沿用协议 §2） |
| 中继地址 / Push Token / 通道 ID | **中继模式**必填（来自中继 Web UI，见 §9） |
| webhook token | 第三方接入鉴权（`/webhook`） |
| 监听地址 / 端口 | 默认 `127.0.0.1:8787`；改后需点「立即重启」 |
| 超时 / 重试次数 | `phone.timeout` / `phone.retries`（中继模式同样用于重试判定，见 §9.2） |

旧 `config.toml` 格式仅供参照（已废弃）：

```toml
[server]
host = "0.0.0.0"
port = 8787

[webhook]
token = "<webhook_token>"

[phone]
url = "http://192.168.1.23:8788"   # 手机 IP:port
token = "<phone_token>"
timeout_seconds = 5
retries = 3
```

## 6. 错误码总表

| 码 | PC /webhook | 手机 /notify |
|---|---|---|
| 200 | 送达 | 送达（含重复） |
| 400 | JSON 非法 / 缺 title、body | 信封非法 |
| 401 | webhook_token 错误 | phone_token 错误 |
| 413 | 请求体 > 64KB | 请求体 > 64KB |
| 502 | 手机不可达（重试耗尽） | — |
| 500 | — | 内部错误 |

## 7. curl 示例

```bash
# 第三方应用 → PC
curl -X POST http://PC_IP:8787/webhook \
  -H "Authorization: Bearer $WEBHOOK_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Grafana","body":"CPU > 90% on node-1","source":"grafana","priority":"high"}'

# 极简接入（查询参数）
curl -X POST "http://PC_IP:8787/webhook?title=备份&body=任务完成" -H "Authorization: Bearer $WEBHOOK_TOKEN"

# PC 模拟 → 手机（task-3 验证用）
curl -X POST http://PHONE_IP:8788/notify \
  -H "X-Auth-Token: $PHONE_TOKEN" -H "Content-Type: application/json" \
  -d '{"v":1,"id":"11111111-1111-4111-8111-111111111111","ts":"2026-09-30T23:59:59+08:00","title":"测试","body":"hello band"}'
```

## 8. v2 预留（本期不做）

- 送达回执（手机 → PC 的 ack 上报）、消息撤回、图片/富文本、静默时段。
- 静默时段、免打扰等策略由手机 App 端自行处理，不进协议。

## 9. 中继模式（v2.2 新增）

适用场景：PC 与手机不在同一局域网（异地/移动网络）。中继服务器（`relay-server/`，Docker 镜像 `mihuan-relay`）只负责**排队与投递**，不改变信封格式。

### 9.1 拓扑与角色

```text
第三方应用 ──> PC ──push──> 中继服务器 <──长轮询 poll/ack── 手机 App
                              ▲
                      浏览器 ─┘ Web UI（/admin/ui）
```

- 每个「通道」`channel` 一对 token：`push_token`（PC 持有，仅能入队）、`poll_token`（手机持有，可拉取 + ack）。通道 ID 为 12 位十六进制。
- 中继侧只看得到信封本身；消息带唯一 `id`，同通道内 `id` 幂等去重（重复推送返回 `duplicate`）。

### 9.2 PC → 中继（推送，等价于直连的 PC → 手机）

| 项 | 规则 |
|---|---|
| 方法 | `POST {relay.url}/channels/{relay.channel_id}/messages` |
| 鉴权 | 请求头 `X-Push-Token: <push_token>`（也接受 `Authorization: Bearer`） |
| 请求体 | 单个信封对象（§3），或批量 `{"messages":[信封…]}`（≤50） |
| 响应 | `200 {"ok":true,"accepted":N,"duplicate":N,"rejected":N}` |
| 错误 | `401` token 错；`404 channel_not_found`；`400 invalid_envelope`；`413 too_large` |

PC 端重试语义：`200` 且 `accepted≥1` → 成功；`duplicate` → 视为成功（幂等）；`accepted=0` 且非 duplicate → 失败且不重试；`4xx` 不重试；`5xx`/网络错误按 `phone.retries` 重试（间隔 1s、3s）。

### 9.3 中继 → 手机（长轮询拉取）

| 项 | 规则 |
|---|---|
| 方法 | `GET {relay.url}/channels/{channel_id}/messages?wait=<秒>&limit=<条>` |
| 鉴权 | 请求头 `X-Poll-Token: <poll_token>` |
| 参数 | `wait` 默认 0（不等待，立即返回）；>0 则挂起最多 `relay.max_wait`（默认 30）；Android 端固定请求 25。`limit` ≤100 |
| 响应 | `200 {"ok":true,"messages":[信封…]}`（无消息时 `messages: []`） |

服务器在该通道有待投递消息时立即返回，否则挂起直到有新消息或 `wait` 超时（服务端事件唤醒，1s 步进轮询）。返回的消息状态置 `delivered`。

### 9.4 送达确认 ack

| 项 | 规则 |
|---|---|
| 方法 | `POST {relay.url}/channels/{channel_id}/ack` |
| 鉴权 | `X-Poll-Token` |
| 请求体 | `{"ids":["信封 id",…]}`（≤200） |
| 响应 | `200 {"ok":true,"acked":N}` |

手机端流程：拉取 → 逐条发系统通知（内存保最近 100 条 id 防重）→ ack。若 ack 前进程被杀，重启后可能重复通知一次（消息已从待投递队列移除）。

### 9.5 探活

`GET {relay.url}/channels/{channel_id}/ping`：`X-Push-Token` 或 `X-Poll-Token` 均可，返回 `{"ok":true,"channel":"名称","pending":N}`。PC 端状态页「中继通道」行即调此端点（显示“中继正常，待推送 N 条”）。

### 9.6 中继管理 API（Web UI 用，会话 cookie 鉴权）

中继的管理面全部在 `/admin/api/*`（会话 cookie 鉴权；未设密码时 `/admin/login` 免密进入首启向导）：`status`、`channels`（增删改、`/rotate/{push|poll}` 重置 token；新建时一次性返回 push/poll token 明文）、`recent`（含 `state=pending|delivered`）、`test`（测试入队）、`config`（监听地址/端口、`storage.recent_limit`、`relay.max_wait`、管理密码）。

### 9.7 容量与裁剪

- 每通道待投递（pending）上限 1000 条，超出丢最旧的 pending；已送达记录按 `storage.recent_limit`（默认 200）滚动裁剪，仅供 Web 界面查看。
- 长轮询中断后消息仍留在队列，手机下一轮自动拉到（至少一次投递语义）。

### 9.8 与直连的差异

| 维度 | 直连 | 中继 |
|---|---|---|
| 网络前提 | PC 与手机同局域网，PC 能访问手机 IP:8788 | 双方都能访问中继即可（可异构网络） |
| 手机端角色 | HTTP 服务器（监听端口，`/notify`） | HTTP 客户端（长轮询拉取） |
| 鉴权 | `X-Auth-Token: phone_token` | 入队 `X-Push-Token`、拉取/ack `X-Poll-Token` |
| PC 端状态检测 | `GET {phone.url}/ping` | `GET {relay.url}/channels/{cid}/ping` |
| 失败语义 | 502 `phone_unreachable`（重试后） | 502 `phone_unreachable`（入队失败时；下“手机不在线”不再等同于不可达） |
| 消息缓存 | 无（失败即丢，仅 PC 最近消息记录） | 中继队列缓存，可达 1000 条/通道 |

无论哪种模式，第三方应用接入方式（PC 的 `/webhook`）**完全不变**。
