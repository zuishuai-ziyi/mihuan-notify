# 调研笔记：小米运动健康私有接口可行性

> task-1 产出 · 结论：**不可行（无可直接调用的私有/公开通知推送接口）** → 采用回退方案：App 发系统通知 + 小米运动健康监听转发到手环。

## 1. 背景与问题

目标系统的最终链路是 PC WebHook → 手机 App → 小米手环。原始设想是"手机端调用小米运动健康（Mi Fitness，包名 `com.xiaomi.wearable`）的接口"把通知推给手环。本调研回答：这样的接口是否存在、能否用、成本多大。

## 2. 主要发现

### 2.1 小米运动健康的手环通知机制 = 系统通知监听（无推送 API）

- Mi Fitness 通过 Android `BIND_NOTIFICATION_LISTENER_SERVICE`（通知使用权）监听手机系统通知，再经蓝牙转发到手环。
- 官方故障排查文档明确要求用户授予 Mi Fitness"通知使用权"，并在"小米运动健康 APP → 消息通知"里按应用逐一开启开关（来源：小米官方 FAQ《小米手环消息通知不提醒怎么办》）。
- **没有任何公开文档提供"第三方 App 直连 Mi Fitness 推通知"的 API。** 第三方应用想上手环，正路就是"自己发一条普通系统通知，让 Mi Fitness 转发"。

### 2.2 现实世界的可行性证明（关键证据）

开源项目 **leNing-sy/water-reminder-cloud**（喝水提醒 App，Java，minSdk 26 / targetSdk 35）正是走这条链路，README 明确结论：

- "手机通知和小米运动健康到小米手环的转发链路可用"；
- "小米运动健康能够识别本应用并提供独立通知开关"；
- "测试通知能够到达手机和小米手环"。

它还指出 Mi Fitness 识别应用依赖**应用图标和应用元数据**（图标 + 应用名/包名）。其实测问题集中在 AlarmManager 定时可靠性（Android 16 省电），与我们的**推送型**场景（收到 LAN 消息立刻发通知）无关——推送型不依赖精准闹钟，可靠性更好。

### 2.3 确实存在的"私有/半私有"接口，但门槛与限制都很高

| 接口/项目 | 现状 | 门槛 | 适用性 |
|---|---|---|---|
| 小米穿戴第三方 APP 能力开放接口文档 v1.4（`com.xiaomi.xms.wearable` SDK：MessageApi/NodeApi/NotifyApi/AuthApi） | 官方存在，含 NotifyApi"发送通知事件" | 面向 Vela 手表生态；"现阶段只对小米生态链企业及合作伙伴正式开放"（小米健康云文档语），需企业资质 | ✗ 个人不可用 |
| A5245/WatchSDK + Wearable-Debug（逆向/半官方） | 实测可与手环 8 Pro 通信（XMS_WEARABLE_SERVICE） | 需要 **LSPosed（root）**给 Mi Fitness 打插件授权"third app list"；还需打包一个**与 Android 应用同包名同签名的小程序** | ✗ 依赖 root，成本/风险过高，仅学习参考 |
| batareya16/MiWearBridge | LSPosed 模块，hook Mi Fitness 实现同签名应用桥接 | 同上，需 root | ✗ |
| Mi Push SDK | 官方推送服务，能把消息变成手机通知 | 需注册开发者账号 + AppID/AppKey，走小米云端 | △ 绕远路：LAN 场景不需要云端推送；发出的通知同样要靠 Mi Fitness 转发 |
| Gadgetbridge（开源蓝牙协议栈） | 完整支持小米手环 8/9/10 等（protobuf 协议） | 手环需**改用 Gadgetbridge 配对**（放弃 Mi Fitness），需提取 auth key | △ 可用备选，但与"经运动健康转发"的原始需求相悖 |

### 2.4 结论

1. **"调用小米运动健康的（私有）接口推通知到手环"不可行**：无公开 API；逆向路径（XMS Wearable SDK / LSPosed）需要 root + 企业资质/同签名小程序，不适合本项目的自用工具定位。
2. **选定落地路径（回退方案，已证实可行）**：Android App 收到 PC 转发消息后调用系统 `NotificationManager` 发通知 → 用户在 Mi Fitness 中授予通知使用权并开启本应用的转发开关 → 手环震动/亮屏显示。
3. 备选（记录备查，不采用）：Gadgetbridge 蓝牙直连（手环须换配套 App）。

## 3. 对后续任务的落地要点

- **task-4（Android App）**：
  - 发通知时使用独立 NotificationChannel；标题/内容由消息字段决定。
  - App 自身的前台服务常驻通知要避免被 Mi Fitness 转发到手环（尝试 `setLocalOnly(true)` / 静音 channel / 独立 channel id，联调时验证 Mi Fitness 是否遵守 `FLAG_LOCAL_ONLY`）。
  - Mi Fitness 的应用识别依赖图标与应用元数据，需设置可辨识的应用名（如 "LAN Notifier"）。
- **task-5（手环对接）**：交付物是"配置指引 + 验证"——手机安装 Mi Fitness 并配对手环；系统设置 → 通知使用权 → 勾选小米穿戴/运动健康；Mi Fitness → 消息通知 → 开启本 App 开关。
- **task-6（联调）**：验收标准是手环端能收到 3 条以上消息（震动/亮屏）。

## 4. 参考来源

- 小米官方 FAQ《小米手环消息通知不提醒怎么办》（cdn.cnbj1.fds.api.mi-img.com/ics-resources/articles/5fe002a329b6075c67c0554d.html）
- 小米穿戴第三方APP能力开放接口文档 v1.4（vela-docs.cnbj1.mi-fds.com）
- leNing-sy/water-reminder-cloud（GitHub，链路可行性实证）
- A5245/WatchSDK、A5245/Wearable-Debug、batareya16/MiWearBridge（GitHub，私有 SDK 逆向路径）
- Gadgetbridge 文档（gadgetbridge.org，小米 protobuf 设备支持列表）
- 小米澎湃OS开发者平台《设备与应用通知权限使用及申请标准》
