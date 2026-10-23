# 更新日志（Changelog）

本项目的版本号按**组件**独立演进：`pc-relay`（PC 端）、`android-app`（手机端）、`relay-server`（中继服务器）。
各组件当前版本见对应入口文件（`pc-relay/main.py` 的 `VERSION`、`android-app/app/build.gradle.kts` 的 `versionName`、`relay-server/store.py` 的 `VERSION`）。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## v2.7.0（未发布）

### 修复（Android）
- **自定义图标选图后提示「图片读取失败」**：`BitmapFactory.decodeStream()` 在 `inJustDecodeBounds = true` 时返回 null（只填充尺寸字段），旧代码错误地把它的返回值当成「流已打开」的判据，导致 `saveIconFromUri()` 永远提前返回 false。改为单独判断 `openInputStream` 是否为 null。
- **点「取消」也被当作添加成功**：部分桌面（如 Lawnchair）在用户拒绝固定快捷方式时仍会发送 resultIntent，原来「收到回调即视为已确认」的假设不成立，导致取消后内置图标被隐藏、桌面却没有新图标，应用失去入口。改为回调后用 `ShortcutManager.getPinnedShortcuts()` 查询快捷方式是否真的被固定，未固定则提示「未添加到桌面，已保持原外观」并保持原方案。
- **确认添加后原图标未立即消失**：原逻辑只在下次启动时才收敛 alias。现在确认成功即调用 `AppAppearance.apply()`，与 toast 提示一致。

versionCode 12，versionName 2.7。

## v2.6.0（未发布）

### 修复（Android）
- **v2.5 打开即崩溃**：启动时收敛外观方案会对「自定义」方案的空 alias 构造出不存在的组件（`ComponentName(pkg, pkg)`），`setComponentEnabledSetting` 抛 `IllegalArgumentException`；已跳过空 alias。versionCode 11，versionName 2.6。

## v2.5.0（未发布）

### 变更
- Android：`versionCode` 9 → 10，`versionName` 2.4 → 2.5；PC / 中继服务器版本不变。

### 新增（Android）
- **自定义应用图标**：在「应用外观与通知」卡选择「自定义」方案，从相册上传一张图片并命名，确认后在桌面生成自定义图标/名称的快捷方式，并自动隐藏原应用图标；切回其他方案可恢复内置图标（自定义快捷方式需手动移除）。基于桌面固定快捷方式（`ShortcutManager.requestPinShortcut`）实现，图标图片存于应用内部存储，可随时更换。

### 修复（Android）
- **切换外观方案后桌面同时出现新旧两个图标**：alias 切换逻辑此前只禁用「显式启用」的组件，未动过的 alias 处于 DEFAULT 状态被跳过；现改为无条件禁用其余 alias，并在应用启动时按保存方案重新收敛 alias 状态（老用户升级后打开一次 App 即自动修复）。

### 变更（Android）
- **常驻通知内容支持留空**：标题/副标题留空 = 不显示对应行（v2.4 为回退到内置默认文案）；「恢复默认」按钮相应改为「清空」。

## v2.4.0（未发布）

### 变更
- Android：`versionCode` 8 → 9，`versionName` 2.3 → 2.4；PC / 中继服务器版本不变。

### 变更（Android）
- **界面配色改为清新淡蓝色系**：应用主题与 Compose 配色整体从绿色系迁到淡蓝色系（亮色主色 #3D7EBB / 深色主色 #9FC6F0，容器/背景/描边同源调整），启动图标圆底同步换色；语义状态色（成功绿/错误红/警示黄）保持不变。

### 新增（Android）
- **应用外观自定义**（设置页「应用外观与通知」卡）：提供 4 种桌面名称与图标方案——默认「通知转发」（蓝色信封）、「记事本」（白底文档图标）、「计算器」、「天气」，实现为 activity-alias + `PackageManager.setComponentEnabledSetting` 运行时切换，选择持久化、重启不丢；说明：系统「应用信息」页仍显示默认应用名，部分桌面需稍候刷新。
- **常驻通知内容自定义**：前台服务常驻通知的标题与正文均可自定义（留空使用内置默认，默认正文含动态端口），「应用」后立即重建通知（服务未运行则下次启动生效）；通知仍为静默 + localOnly，不转发到手环。

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
