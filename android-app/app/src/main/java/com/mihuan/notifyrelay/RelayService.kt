package com.mihuan.notifyrelay

import android.app.Service
import android.content.Intent
import android.os.IBinder
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                log("收到停止指令")
                ConfigStore.setAutoStart(this, false) // 用户手动停用：重启后不再自启
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                sendTestNotification()
                return START_STICKY
            }
            ACTION_REFRESH_NOTIF -> {
                // 常驻通知内容变更：服务运行中则重建前台通知，否则下次启动时自然生效
                if (running) {
                    NotificationHelper.ensureChannels(this)
                    startForeground(NOTIF_ID, NotificationHelper.serviceNotification(this))
                    log("常驻通知内容已更新")
                }
                return START_STICKY
            }
        }

        NotificationHelper.ensureChannels(this)
        startForeground(NOTIF_ID, NotificationHelper.serviceNotification(this))

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
