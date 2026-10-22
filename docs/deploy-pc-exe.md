# 通知转发 PC 端（Windows exe 版）使用说明

v2.1 起配置存入 SQLite 数据库（`relay.db`）：**首次运行自动建库，所有配置都能在
Web 管理界面里改**，不需要手动编辑任何文件。

## 包内容

```text
pc-relay.exe          # 服务主程序（内含 Web 管理界面，双击即启动）
_internal/            # 运行时依赖（PyInstaller 产物，与 exe 同级放置，勿拆分）
manage.py             # 开机自启管理（也可在 Web 界面开关自启）
使用说明.md           # 本文件
```

## 快速开始（三步）

1. **启动**：双击 `pc-relay.exe`。首次运行会自动在 exe 同目录创建 `relay.db`
   （默认只监听本机 `127.0.0.1:8787`，并自动生成随机 webhook token）。
2. **打开管理界面**：浏览器访问 `http://127.0.0.1:8787/admin/ui/`
   ——首次进入自动进入**配置向导**（免登录）：
   - 第 1 步：选**传输模式**并填对应参数——**直连手机**：填手机端地址与 Token（手机 App「复制配置信息」可得）；**经中继服务器**：填中继地址、Push Token、通道 ID（来自中继服务器 Web UI「通道」页），可点击「测试连接」验证；
   - 第 2 步：设置管理界面登录密码（≥6 位）；
   - 第 3 步：完成保存——可选重新生成 webhook token，若改过监听地址/端口会自动重启服务。
3. **（可选）允许局域网访问**：默认只监听本机。若要在其他设备访问 Web 界面或接收
   局域网 webhook，请在「配置」页把监听地址改为 `0.0.0.0` 并点「立即重启」。

## Web 管理界面

地址 `http://<本机IP>:8787/admin/ui/`（默认 `http://127.0.0.1:8787/admin/ui/`）。

- **状态**：运行时长、版本、**传输模式**（直连手机 / 经中继服务器）、下游连通性。
  - 直连模式显示「手机端连通性」（`GET {手机地址}/ping`）；
  - 中继模式显示「中继通道」（`GET {中继}/channels/{通道}/ping`，正常时提示“中继正常，待推送 N 条”）。
- **最近消息**：转发成功/失败记录（默认保留 200 条，可在「设置」页改保留条数）。
- **测试发送**：走完整链路发一条测试通知到手环。
- **接入指引**：一键复制的 Webhook 接入模板（curl/JavaScript/Python/查询参数），
  接入地址输入框+候选下拉；登录后模板自动填入真实 Token，复制即可用；任意能发 HTTP 请求的程序都能借此推通知到手环。
- **设置**：表单化编辑全部配置项（服务地址、webhook token、手机地址/Token、管理密码、
  最近消息保留条数、开机自启等），改动点「保存并应用」即生效。
  - token 类字段打码显示，留空保存表示"不修改"；旁有 显示/隐藏 切换明文、🎲 一键生成随机值；
  - 改监听地址/端口保存后出现「⟳ 立即重启」按钮，点击即自动重启并恢复页面；
  - 其他改动**保存即生效**（热更新），无需重启。

## 中继模式（PC 与手机不在同一局域网时）

若手机在移动网络/异地（PC 无法直接访问手机 `8788`），改用中继：

1. 先部署中继服务器（仓库 `relay-server/`，`docker build -t mihuan-relay:latest .` 后 `docker run`），
   访问其 Web UI 新建一个通道，拿到 **通道 ID / Push Token / Poll Token**；详见 [relay-server/README.md](../relay-server/README.md)。
2. 本机「设置」页「传输模式」选「经中继服务器」→ 填**中继地址**（如 `https://relay.example.com`）、**Push Token**、**通道 ID** →「保存并应用」。
3. 手机 App「传输模式」选「中继服务器」→ 填同一地址、通道 ID 与 **Poll Token**。
4. 「状态」页出现「传输模式：经中继服务器」且“中继通道：✓ 中继正常”即接通；「测试发送」验通路。

- 两种模式可随时互切（保存即生效，无需重启）；切回直连后仍用原来的手机地址/Token。
- 中继只负责排队投递（每通道缓存最多 1000 条待投递），手机短暂离线时消息不丢。

## 命令行自启管理（可选）

```bat
python manage.py install-autostart     :: 注册（Run 键 → vbs 静默启动 exe）
python manage.py status                :: 查看状态
python manage.py uninstall-autostart   :: 注销
```

- 任意能运行 Python 的环境均可（Windows Python / WSL）；注册的是 exe 绝对路径，移动目录后请重新注册。
- 与 Web 界面的自启开关等效，操作同一注册表键。

## 第三方应用接入

```bash
curl -X POST http://PC_IP:8787/webhook \
  -H "Authorization: Bearer <webhook_token>" -H "Content-Type: application/json" \
  -d '{"title":"Grafana","body":"CPU > 90%","priority":"high"}'
```

webhook token 在 Web 界面「配置」页查看/重新生成。也支持纯文本
（`Content-Type: text/plain`，正文即消息）与查询参数（`?title=xx&body=yy`）。

> 💡 Web 界面「接入指引」页有可直接复制的完整模板（含多网卡地址切换），
> 优先推荐从那里复制，替换其中的 `<WEBHOOK_TOKEN>` 即可。

## 常见问题

- **端口被占**：配置页改「监听端口」保存后点「立即重启」。
- **改了配置没生效**：token/地址/密码类改动保存即生效；监听地址/端口需点「立即重启」。
- **忘记管理密码**：关闭 exe，删除 exe 同目录 `relay.db`（或用 SQLite 工具清空
  `kv` 表中 `admin.password` 键），再启动——会回到免密向导重新设置。
- **手机不可达**：状态页显示红色时，检查手机 App 是否运行、IP 是否变化（建议路由器绑定静态 IP）。
- **中继未配置完整**：中继模式下状态页提示此文案，说明中继地址/Push Token/通道 ID 未填全，补全保存即恢复。
- **中继模式仍提示 502**：表示中继服务器不可达或 push token 错（中继返 401 不重试）；先在「状态」页确认“中继通道”为绿色，再到中继 Web UI 核对通道 ID 与 token。
- **升级/更换目录**：整个目录（exe + `_internal/` + `relay.db`）一起移动即可，配置随目录走。
- **设置页的「开机自启」**：一个区块（标题 + 状态标签 + 开启/关闭按钮），就在「设置」页表单下方；
  开启后注册 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 的 `MihuanNotifyRelay` 项（登录后静默后台启动，
  日志写 `deploy/autostart.log`）。非 Windows 系统或 `manage.py` 不在 exe 同目录时，该区块会显示具体原因而不会消失；
  对移动过目录的旧包，请重新点一次「开启」以刷新注册表里的 exe 路径。

## 安全提示

- 默认监听 `127.0.0.1`，仅本机可访问；改 `0.0.0.0` 后局域网内任何人都能访问 Web 界面，
  请务必设置足够强的管理密码。
- `relay.db` 内含 webhook token 与手机端 token（管理密码为哈希存储），注意不要外传该目录。
