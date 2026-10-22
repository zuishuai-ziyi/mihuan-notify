# relay-server 中继服务器

PC 与手机不在同一局域网时使用的中转服务：**PC 推送 → 中继入队 → 手机长轮询拉取 → 系统通知 → 小米手环**。
中继只做「按通道排队 + 长轮询投递 + 送达确认」，**不做内容解析以外的任何加工**，也不需要 PC/手机可达彼此。

```text
第三方应用 ──> PC ──POST /channels/{cid}/messages (X-Push-Token)──> 中继服务器 ──长轮询──> 手机 App ──> 手环
                                                                     ▲
                                                            浏览器 ── Web UI（/admin/ui/）
```

- 一个中继实例可服务多个「通道」（channel）：每个通道一对 token（push / poll），PC 与手机各持其一，互不泄露。
- 消息信封格式与直连模式**完全一致**（见 [../docs/protocol.md](../docs/protocol.md)「中继模式」节），手机端与 PC 端无需感知差异。
- 打包产物为 Docker 镜像 `mihuan-relay:latest`，自带 Web 管理界面（登录、通道管理、最近消息、测试发送、配置）。

## 快速开始

```bash
# 1) 构建镜像
cd relay-server
docker build -t mihuan-relay:latest .

# 2) 运行（数据落在命名卷 relay-data）
docker run -d --name mihuan-relay \
  -p 8080:8080 \
  -v relay-data:/app/data \
  -e RELAY_ADMIN_PASSWORD='改成你的密码' \
  mihuan-relay:latest
```

或使用 compose：

```bash
docker compose up -d          # 见 docker-compose.yml
```

然后访问 `http://<服务器IP>:8080/admin/ui/`，用 `RELAY_ADMIN_PASSWORD` 登录
（未设置该变量时进入**首启免密向导**，请立即在「设置」页设管理密码）。

健康检查：`curl http://<服务器IP>:8080/health` → `{"ok":true,"service":"relay-server","version":"1.0","needs_setup":…}`

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `RELAY_HOST` | `0.0.0.0` | 监听地址（首次建库时写入配置，之后由 env 强制覆盖） |
| `RELAY_PORT` | `8080` | 监听端口（同上） |
| `RELAY_DB` | 容器内 `/app/data/relay.db`（源码运行则为源码旁 `relay.db`） | SQLite 库路径 |
| `RELAY_ADMIN_PASSWORD` | 空 | 首次建库时设为管理密码哈希的明文来源；空 = 首启免密向导 |
| `RELAY_FORCE_DOCKER` | - | 置 1 时状态页按 Docker 部署展示（自动检测 `/app/.dockerenv`） |

## Web 管理界面

`/admin/ui/`（Vue3 + Vite 构建产物，由中继自身托管），四页签：

- **状态**：运行时长、版本、监听地址、通道数/在线通道、待推送条数、消息总量
- **通道**：新建/重命名/删除通道；查看并复制 push token、poll token、推送地址；一键「测试」入队一条消息；token 泄露时可单独重置
- **消息**：全部通道最近消息（时间/通道/标题/优先级/来源/状态），点行展开正文，可按通道筛选、一键清空
- **设置**：表单化编辑监听地址/端口、最近消息保留条数、长轮询最大等待秒数、管理密码（保存即热生效，仅地址/端口需重启）

## HTTP 接口

对外（PC / 手机调用，token 鉴权，可用 `X-Push-Token` / `X-Poll-Token` 头或 `Authorization: Bearer`）：

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | 无 | 健康检查与 `needs_setup` |
| POST | `/channels/{cid}/messages` | push | 入队（单信封或 `{"messages":[…]}` 批量 ≤50），返回 `{ok,accepted,duplicate,rejected}`；同 `id` 幂等去重 |
| GET | `/channels/{cid}/messages?limit=&wait=` | poll | 长轮询拉取待投递消息（`wait` 默认 0=不等待，上限 `relay.max_wait` 默认 30s，Android 端用 25s；`limit` ≤100） |
| POST | `/channels/{cid}/ack` | poll | 确认消费 `{"ids":[…]}`（≤200），返回 `{ok,acked}` |
| GET | `/channels/{cid}/ping` | push 或 poll | 探活：返回通道名与待推送条数（PC 端状态页用它判断中继连通性） |

Web UI 与自动化用（`/admin/api/*`，会话 cookie 鉴权；`/admin/login` 免密仅限未设密码时）：

`POST /admin/login`、`POST /admin/logout`、`GET /admin/api/status`、`GET|POST /admin/api/channels`、
`PATCH|DELETE /admin/api/channels/{cid}`、`POST /admin/api/channels/{cid}/rotate/{push|poll}`、
`GET /admin/api/recent?channel=&limit=`、`POST /admin/api/recent/clear`、`POST /admin/api/test`、
`GET|POST /admin/api/config`。

## 推送接入示例

```bash
# PC 端（或任意应用）推送到通道
curl -X POST http://RELAY_IP:8080/channels/$CID/messages \
  -H "X-Push-Token: $PUSH_TOKEN" -H 'Content-Type: application/json' \
  -d '{"v":1,"id":"unique-id","title":"Grafana","body":"CPU > 90%","priority":"high"}'
```

手机端拉取与确认（App 内 `RelayPoller.kt` 自动完成）：

```bash
curl -H "X-Poll-Token: $POLL_TOKEN" "http://RELAY_IP:8080/channels/$CID/messages?wait=25&limit=10"
curl -X POST -H "X-Poll-Token: $POLL_TOKEN" -H 'Content-Type: application/json' \
  -d '{"ids":["唯一id"]}' http://RELAY_IP:8080/channels/$CID/ack
```

## 数据与容量

- 单文件 SQLite（`/app/data/relay.db`，WAL 模式）：`kv` 配置表 + `channels` 表 + `messages` 表。
- 每个通道待投递消息上限 1000 条，超出丢弃最旧的**待投递**消息（已送达记录不受影响）。
- 已送达记录按 `storage.recent_limit`（默认 200）滚动裁剪，仅用于 Web 界面查看。
- 备份 = 备份该 db 文件；升级镜像不影响数据（卷独立）。

## 安全建议

- **公网部署务必用 HTTPS 反代**（Caddy/Nginx），并把 `RELAY_ADMIN_PASSWORD` 设强密码；中继能看到消息标题与正文，请自行评估内容敏感度。
- 中继自身不做 TLS 终止；建议只暴露反代端口，`8080` 不直接对公网开放。
- push token / poll token 分离：push token 泄露只可能导致伪造通知入队，poll token 泄露可能导致消息被他人取走；泄露后在 Web UI 单独重置并同步更新端侧配置。
- 管理会话 cookie 为 HMAC 签名、12 小时过期；登录失败有 0.5s 延迟。

## 开发与测试

```bash
# 本地源码运行（需 fastapi/uvicorn，可用 pc-relay/.venv）
RELAY_DB=/tmp/relay.db RELAY_PORT=8080 python -m uvicorn main:app --host 0.0.0.0 --port 8080

# 前端（改了 webui/src 后需重建）
cd webui && npm install && npm run build     # 产物 dist/ 由 main.py 挂到 /admin/ui

# 自包含冒烟（30 用例：鉴权/建通道/推送幂等/批量/长轮询/ack/轮换/删除/错误码/测试发送/配置）
bash tests/smoke.sh                          # 用 RELAY_PYTHON 指定解释器（默认 python3）

# 与 PC 端、模拟手机的长轮询联调（在仓库根）
bash pc-relay/tests/e2e_relay.sh             # 25 用例：中继入队→长轮询拉取+ack→状态展示→502→切回直连
```

## 目录

```text
relay-server/
├── main.py              # FastAPI 入口：/health、通道消息接口、Web UI 挂载
├── admin.py             # 管理 API + 会话 cookie 鉴权
├── store.py             # SQLite 存储层（配置 kv / 通道 / 消息队列）
├── requirements.txt     # fastapi、uvicorn
├── Dockerfile           # python:3.12-slim + HEALTHCHECK + VOLUME /app/data
├── docker-compose.yml
├── webui/               # Vue3 + Vite 管理界面（src/ 源码，dist/ 构建产物）
└── tests/smoke.sh       # 自包含冒烟测试
```
