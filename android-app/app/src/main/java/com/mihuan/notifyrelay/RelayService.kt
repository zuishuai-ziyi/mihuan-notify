package com.mihuan.notifyrelay

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 前台服务：按传输模式保活消息引擎。
 * - direct：内嵌 HTTP 服务器（NanoHTTPD），接收 PC 推送并发布系统通知。
 * - relay ：长轮询中继服务器拉取消息（RelayPoller）并发布系统通知。
 * 前台通知本身静默 + localOnly，避免被小米运动健康转发到手环。
 */
class RelayService : Service() {

    private var server: NotifyServer? = null
    private var poller: RelayPoller? = null

    /**
     * 本实例是否已调用 startForeground（前台常驻通知已挂着）。
     * 与静态标志 running 区分：running 是进程级标志，表示「消息引擎已启动」；
     * hasForeground 表示「当前这个 Service 实例是否真的是前台服务」。
     * 仅主线程读写（onStartCommand / onDestroy 回调）。
     */
    private var hasForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP) {
            // 安全网：应用处于「自定义」方案但桌面快捷方式已丢失时，服务启动是唯一的
            // 自救路径（此时无任何桌面入口可进应用）。延迟 15s 校验，避开开机后
            // 快捷方式状态尚未就绪的窗口（开机自启场景），避免误恢复。
            Handler(Looper.getMainLooper()).postDelayed({
                AppAppearance.recoverIfCustomLost(this)
            }, 15_000L)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                log("收到停止指令")
                ConfigStore.setAutoStart(this, false) // 用户手动停用：重启后不再自启
                // 立即标记为未运行：stopSelf() 到 onDestroy() 之间不应再被当作「在跑」
                //（否则这段时间里到达的刷新请求会重建前台通知，残留一个已 start 的服务）
                running = false
                hasForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                // 测试通知本身不依赖前台服务（走 NotificationManager.notify），通知照发。
                // 但「本实例从未 startForeground」时绝不能返回 START_STICKY：
                // 那会留下一个「已 start 但从未 startForeground」的粘性服务，
                // 系统之后以 intent == null 重启它时会走正常启动路径
                // → startEngine() + ConfigStore.setAutoStart(true)，消息引擎被静默拉起。
                // 处理：不保留服务实例（stopSelf(startId)），返回 START_NOT_STICKY。
                // 判据用 hasForeground（本实例是否已挂前台通知），而不是 running：
                // ACTION_TEST 是轻量分支，本身不启引擎、也不置 running；
                // running 又是进程级静态标志（服务未启动/已停止时为 false），
                // 若照搬 running 判断会把「服务未运行时点测试通知」的功能一起废掉。
                if (!hasForeground) {
                    log("测试通知：服务未运行（未挂前台通知），仅发送通知、不保留服务")
                    sendTestNotification()
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                sendTestNotification()
                return START_STICKY
            }
            ACTION_REFRESH_NOTIF -> {
                // 仅在服务确实在跑（引擎已启动、前台通知已挂着）时重建常驻通知。
                // 若此刻服务未运行（例如刚被系统回收、或刷新请求抢在服务启动前到达），
                // 绝不能返回 START_STICKY：那会留下一个「已 start 但从未 startForeground」
                // 的粘性服务，系统之后以 intent == null 重启它时会走正常启动路径
                // → startEngine() + ConfigStore.setAutoStart(true)，消息引擎被静默拉起。
                // 处理：本次刷新请求作废，stopSelf(startId) 让服务不残留，
                // 返回 START_NOT_STICKY（下次用户主动启动时通知内容自然生效）。
                if (!running) {
                    log("常驻通知刷新：服务未运行，忽略本次刷新")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                NotificationHelper.ensureChannels(this)
                startForeground(NOTIF_ID, NotificationHelper.serviceNotification(this))
                hasForeground = true
                log("常驻通知内容已更新")
                return START_STICKY
            }
        }

        NotificationHelper.ensureChannels(this)
        startForeground(NOTIF_ID, NotificationHelper.serviceNotification(this))
        hasForeground = true

        applyLogLimit(ConfigStore.logLimit(this))
        startEngine()
        running = true
        ConfigStore.setAutoStart(this, true) // 运行中关机/重启后自动拉起
        return START_STICKY
    }

    /** 按当前配置模式启动对应引擎（已在跑则先停旧的，配置变更后重启服务即生效）。 */
    private fun startEngine() {
        stopEngine()
        when (ConfigStore.mode(this)) {
            ConfigStore.MODE_RELAY -> startPoller()
            else -> startServer()
        }
    }

    private fun startServer() {
        val port = ConfigStore.port(this)
        val token = ConfigStore.token(this)
        val s = NotifyServer(
            port = port,
            token = token,
            onMessage = { id, title, body, source, priority ->
                try {
                    NotificationHelper.showMessage(this, id, title, body, source, priority)
                } catch (e: Exception) {
                    log("发布通知失败: ${e.message}")
                }
            },
            onLog = { log(it) },
        )
        try {
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            log("直连模式：监听 0.0.0.0:$port token=$token")
        } catch (e: IOException) {
            log("服务器启动失败(端口 $port 占用?): ${e.message}")
        }
    }

    private fun startPoller() {
        val url = ConfigStore.relayUrl(this)
        val cid = ConfigStore.relayChannelId(this)
        if (url.isEmpty() || cid.isEmpty() || ConfigStore.relayPollToken(this).isEmpty()) {
            log("中继模式未配置完整（地址/通道 ID/Poll Token），暂不拉取；请在 App 设置中补全")
            // 仍保持服务运行，配置补全后重启服务即可生效
            return
        }
        val p = RelayPoller(
            getUrl = {
                "${ConfigStore.relayUrl(this)}/channels/${ConfigStore.relayChannelId(this)}/messages?wait=25&limit=10"
            },
            getPollToken = { ConfigStore.relayPollToken(this) },
            onMessage = { id, title, body, source, priority ->
                try {
                    NotificationHelper.showMessage(this, id, title, body, source, priority)
                } catch (e: Exception) {
                    log("发布通知失败: ${e.message}")
                }
            },
            onLog = { log(it) },
        )
        p.start()
        poller = p
        log("中继模式：$url 通道=$cid")
    }

    private fun stopEngine() {
        server?.let {
            it.stop()
            log("直连服务器已停止")
        }
        server = null
        poller?.shutdown()
        poller = null
    }

    private fun sendTestNotification() {
        val id = UUID.randomUUID().toString()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        NotificationHelper.showMessage(this, id, "测试通知", "App 本地测试消息 $time", "test", "high")
        log("已发送测试通知")
    }

    override fun onDestroy() {
        stopEngine()
        running = false
        hasForeground = false
        log("服务已停止")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mihuan.notifyrelay.action.START"
        const val ACTION_STOP = "com.mihuan.notifyrelay.action.STOP"
        const val ACTION_TEST = "com.mihuan.notifyrelay.action.TEST"
        const val ACTION_REFRESH_NOTIF = "com.mihuan.notifyrelay.action.REFRESH_NOTIF"
        private const val NOTIF_ID = 1

        @Volatile
        var running: Boolean = false
            private set

        /** 运行日志（主界面轮询读取；写入来自引擎线程，需加锁）。保留条数由设置「日志保留条数」控制。 */
        private val logs = ArrayDeque<String>()

        /** 当前日志保留上限；由服务启动/界面修改时同步（默认 40 与旧版一致）。 */
        @Volatile
        private var logLimit: Int = 40

        /** 界面/服务启动时同步日志保留上限，并立即裁剪现有日志。 */
        @Synchronized
        fun applyLogLimit(max: Int) {
            logLimit = max.coerceIn(10, 1000)
            trimLocked()
        }

        @Synchronized
        fun log(line: String) {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logs.addLast("[$ts] $line")
            trimLocked()
        }

        @Synchronized
        fun clearLogs() {
            logs.clear()
        }

        @Synchronized
        fun logSnapshot(): List<String> = logs.toList()

        private fun trimLocked() {
            while (logs.size > logLimit) logs.removeFirst()
        }
    }
}
