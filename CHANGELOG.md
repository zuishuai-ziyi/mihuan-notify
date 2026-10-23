# 更新日志（Changelog）

本项目的版本号按**组件**独立演进：`pc-relay`（PC 端）、`android-app`（手机端）、`relay-server`（中继服务器）。
各组件当前版本见对应入口文件（`pc-relay/main.py` 的 `VERSION`、`android-app/app/build.gradle.kts` 的 `versionName`、`relay-server/store.py` 的 `VERSION`）。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## v2.3.0（未发布）

### 变更
- Android：`versionCode` 7 → 8，`versionName` 2.2 → 2.3；PC / 中继服务器版本不变。

### 新增（Android）
- **界面现代化（Jetpack Compose）**：主界面从 XML View 体系全面迁移到 **Jetpack Compose + Material 3** 声明式 UI（BOM 2024.09.03，Compose Compiler 1.5.14，配套 Kotlin 1.9.24）——状态卡（运行状态彩点 + 模式胶囊 + 可选中连接信息 + 启停/测试/复制/Token 操作）、传输模式卡（**M3 SegmentedButton** 分段选择，中继三要素/监听端口仅在对应模式显示）、应用行为卡（开关 + 分段选择）、运行日志卡；保留与启动图标同源的绿色品牌配色，**深色模式跟随系统**；原 `activity_main.xml` 及配套 drawable 已移除。
- **应用行为自定义**（设置持久化，即时生效）：
  - **开机自启**开关（默认开）：总开关；实际拉起还需停机前服务在运行（手动「停止服务」后重启不自启，与旧版语义一致）。
  - **在最近任务中隐藏**开关（默认开，与 v1.2 以来行为一致）：Manifest 硬编码 `excludeFromRecents` 改为运行时 `ActivityManager.AppTask.setExcludeFromRecents`，切开关立即生效。
  - **日志保留条数**：40 / 100 / 200 / 500 四档分段选择（默认 40，与旧版一致），新增「清空」日志按钮。

## v2.2.4（2026-10-23）

### 变更
- Android：`versionCode` 6 → 7，Release APK 由 CI 使用正式签名证书构建（GitHub Actions secrets：`ANDROID_KEYSTORE_BASE64` + `ANDROID_KEYSTORE_PROPERTIES`），此前公开发布的 Release APK 均为 debug 签名；正式签名包与旧 debug 包不兼容升级，需卸载重装（Token 会重置）。
- PC / 中继服务器版本不变（pc-relay 2.2.3 / relay-server 1.0.1），Release 产物以 tag `v2.2.4` 命名。

## 未发布（Unreleased）

### 新增
- 项目以开源形式公开发布：补齐 LICENSE（MIT）、CONTRIBUTING、CHANGELOG、.gitignore/.gitattributes 等仓库规范文件。
- CI/CD：GitHub Actions——`ci.yml`（push/PR 自动跑 PC e2e 33+25 用例、relay 冒烟 30 用例、Android 双包构建、Docker 构建检查）；`release.yml`（推 `v*` tag 自动构建 PC zip / Android APK / GHCR 镜像并创建 GitHub Release）。

## 历史版本（公开仓库建立前）

以下里程碑整理自项目内文档，按大致演进顺序排列；细节以各版本发布包内说明为准。

### 直连模式时代（PC v1.x / Android v1.x）
- 基础链路：第三方应用 → PC `/webhook` → 手机 `/notify` 系统通知 → 小米运动健康转发到手环。
- Android v1.0：debug 签名；升级到 v1.1（release 签名）须卸载重装，Token 重置。
- Android v1.2：App 主界面不出现最近任务列表（`excludeFromRecents`），后台服务不受影响。
- PC v2.0：配置文件为 `config.toml`。

### PC v2.1「应用化」
- 移除配置文件：首启自动创建 SQLite 库 `relay.db`（kv 配置 + 消息表），全部字段经 Web 管理界面修改。
- 首启免密配置向导；管理员密码 PBKDF2-SHA256 哈希落库。
- Web 管理界面：状态 / 最近消息 / 测试发送 / 接入指引（Webhook 模板复制）/ 表单化配置编辑 / 开机自启开关。
- 开机自启管理（`manage.py`，exe / venv 双模式自动探测）。

### 中继模式（relay-server v1.0 → v1.0.1，PC / Android 同步支持）
- 新增中继服务器：Docker 镜像 + Web UI，按「通道」隔离（`push_token` / `poll_token`，可轮换）。
- 手机端长轮询拉取 + ack 幂等投递；PC 与手机无需互通，适合异地/移动网络。
- 消息唯一 id 幂等入库，ack 前不重复投递。

### 当前版本
| 组件 | 版本 |
|---|---|
| pc-relay | 2.2.3 |
| android-app | 2.2（versionCode 6） |
| relay-server | 1.0.1 |

详细功能与部署说明见 [README.md](README.md)，协议规范见 [docs/protocol.md](docs/protocol.md)。
