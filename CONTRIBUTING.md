# 贡献指南（CONTRIBUTING）

感谢关注本项目！提交 Issue / PR 前请先阅读本指南。

## 项目组成

| 目录 | 组件 | 技术栈 |
|---|---|---|
| `pc-relay/` | PC 端服务（WebHook 接收 → 转发手机） | Python 3.11+ / FastAPI + Vue3+Vite（Web UI） |
| `android-app/` | 手机端 App（收通知 → 小米运动健康转发手环） | Kotlin / NanoHTTPD |
| `relay-server/` | 中继服务器（可选，中继模式用） | Python / FastAPI + Docker + Vue3+Vite |
| `docs/` | 协议规范、部署与调研文档 | Markdown |

架构与部署请先读 [README.md](README.md)，通信协议见 [docs/protocol.md](docs/protocol.md)。

## 开发环境搭建

### PC 端（pc-relay）

```bash
# 1. Web UI 需先构建（否则 /admin/ui 无产物可挂载）
cd pc-relay/webui
npm install
npm run build

# 2. Python 服务
cd ..
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt   # 国内可加 -i https://pypi.tuna.tsinghua.edu.cn/simple
PC_RELAY_DB=/tmp/dev-relay.db .venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8787
```

- 用 `PC_RELAY_DB` 指向临时路径做开发，避免污染真实 `relay.db`（该库含配置与 token，已被 gitignore）。
- Web UI 源码改动后重新 `npm run build` 再刷新页面（无热更新挂载）。

### 中继服务器（relay-server）

```bash
cd relay-server
docker compose up -d        # 生产方式，镜像 mihuan-relay:latest
# 或源码调试：
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cd webui && npm install && npm run build && cd ..
.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8790
```

### 手机端（android-app）

- 需要 Android SDK（Android 8.0+ 目标）与 JDK；项目**未提交 Gradle Wrapper**，用本机 Gradle / Android Studio 打开构建。
- release 签名来自根目录 `keystore.properties` + `keystore/`（均被 gitignore，**严禁提交**）；缺失时自动回退 debug 签名，无密钥环境也可出包。
- 构建脚本参考 `scripts/build_apk.sh`。

## 运行测试

改动后请至少跑通离线用例（无需真机/Docker）：

| 命令 | 覆盖 |
|---|---|
| `bash pc-relay/tests/e2e.sh` | PC 端本地 mock 全量 33 用例（独立临时 DB，含 Web UI 渲染检查，需 node，可用 `NODE_BIN` 指定） |
| `bash pc-relay/tests/e2e_relay.sh` | 中继模式全链路 25 用例（自动起 relay-server + PC + 模拟手机） |
| `bash relay-server/tests/smoke.sh` | 中继服务器自包含冒烟 30 用例（`RELAY_PYTHON` 可指定解释器） |

真机用例（可选，需手机与手环在手）：`pc-relay/tests/e2e_full.sh`（全链路 3 条消息）、`lan_test.sh`（连通性 4 项）。

## 提交规范

- 提交信息建议遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：`feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`，可加组件前缀，如 `feat(pc): ...`、`fix(android): ...`。
- **严禁提交**：`keystore*`、`*.keystore`、`keystore.properties`、`*.db`（含 token/密码哈希）、构建产物（`dist/`、`build/`、`node_modules/`）——.gitignore 已覆盖，新增敏感文件请同步补充规则。
- 行尾由 `.gitattributes` 统一（仓库内 LF，`.bat/.vbs/.ps1` 为 CRLF），请勿关闭 `text=auto`。
- 新依赖请说明理由；Web UI 改动请附带 `render_check.mjs` 能覆盖的渲染检查项（如适用）。

## Pull Request 流程

1. Fork 或新建特性分支（`feat/xxx`、`fix/xxx`）。
2. 改动 + 补充/更新测试；涉及协议变更须同步更新 `docs/protocol.md` 与手机端、中继两侧实现。
3. 本地跑通上表离线用例。
4. PR 描述写清：动机、改动点、测试情况；UI 改动附截图。

## 反馈问题

Issue 请附：组件与版本（PC/Android/中继各自版本号）、复现步骤、期望与实际行为、相关日志（**先脱敏 token/密码**）。

## 许可

提交即表示同意以 [MIT License](LICENSE) 许可你的贡献。
