# NotifyRelay 手机端 App

局域网接收 PC 转发的消息，发布 Android 系统通知，由小米运动健康转发到小米手环。

## 构建（PC 上）

```bash
# 需要 JDK 17+ 与 Android SDK；本机 SDK 在 C:\Program Files (x86)\Android\android-sdk
# （local.properties 的 sdk.dir 已配置，勿提交）
gradle assembleDebug          # 或 gradle assembleRelease（keystore.properties 提供签名）
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## v2.2 新增

- **传输模式开关**：主界面新增「传输模式」单选（**直连**（局域网，默认）/ **中继服务器**），切换后自动重启服务。
- **中继配置**：中继模式下填「中继服务器地址 / 通道 ID / Poll Token」→「保存并应用（重启服务）」；App 转为长轮询拉取中继消息（不再监听端口）。模式单选改动需点该按钮才生效（未保存时状态栏会提示「改动未生效」）。
- 「复制配置信息」按模式生成：直连输出 `[phone]` TOML 片段；中继输出「中继地址 / 通道 ID / Poll Token」三行，方便对填到 PC 端设置页。
- 构建/目标 SDK 降为 34（buildTools 34.0.0），避免依赖 SDK 35/36 下载。

## v2.0 新增

- **应用内改监听端口**：主界面「监听端口」输入 + 应用按钮（1024-65535，运行中自动重启服务）
- **通知权限提示条**：未授权时界面顶部常驻橙色提示
- targetSdk/compileSdk 34，versionCode 4 / versionName 2.0

## 手机端配置

### 直连模式（默认）

1. 安装 APK，打开 App，授予通知权限，点「启动服务」（前台服务常驻）。
2. 记下 App 显示的 `http://<手机IP>:8788` 与 Token。
3. PC 端设置页「手机端（直连模式）」填手机地址与同一 Token（旧版为 `config.toml` 的 `[phone].url` / `[phone].token`）。

### 中继模式

1. 先部署中继服务器（见仓库 `relay-server/`，Docker 镜像 `mihuan-relay`），在它的 Web UI「通道」页新建通道。
2. App「传输模式」选「中继服务器」→ 填中继地址（如 `https://relay.example.com`）、通道 ID、Poll Token（来自中继 Web UI）→「保存并应用（重启服务）」。
3. PC 端设置页「传输模式」选「经中继服务器」，填同一中继地址、通道 ID 与 **Push Token**。
4. 验证：用 App 内「测试通知」或 PC 的「测试发送」，手环应收到消息；App 日志会显示「中继模式：<地址> 通道=<id>」与每轮拉取结果。

### 小米运动健康（两种模式通用）

1. 小米运动健康 → 我的 → 设置（第三方授权/通知使用权）→ 授权本 App「通知使用权」，并在 运动健康→消息通知 中打开「通知转发」。
2. 用 App 内「测试通知」验证手环收到消息。
