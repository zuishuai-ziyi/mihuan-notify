package com.mihuan.notifyrelay

import android.content.Context
import java.util.UUID

/** App 本地配置：传输模式、token、监听端口、中继参数（SharedPreferences 持久化）。 */
object ConfigStore {
    private const val PREFS = "relay_config"
    private const val KEY_TOKEN = "token"
    private const val KEY_PORT = "port"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_BOOT_START = "boot_start"
    private const val KEY_HIDE_RECENTS = "hide_recents"
    private const val KEY_LOG_LIMIT = "log_limit"
    private const val KEY_MODE = "mode"
    private const val KEY_RELAY_URL = "relay_url"
    private const val KEY_RELAY_CHANNEL = "relay_channel_id"
    private const val KEY_RELAY_POLL_TOKEN = "relay_poll_token"
    const val DEFAULT_PORT = 8788
    const val MODE_DIRECT = "direct"
    const val MODE_RELAY = "relay"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 传输模式：direct=局域网 HTTP 直连（PC→手机），relay=经中继服务器拉取。 */
    fun mode(ctx: Context): String =
        prefs(ctx).getString(KEY_MODE, null) ?: MODE_DIRECT

    fun setMode(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_MODE, value).apply()
    }

    /** 中继服务器地址（形如 http://1.2.3.4:8080，不带尾部 /）。 */
    fun relayUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_RELAY_URL, "")!!.trimEnd('/')

    fun setRelayUrl(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_RELAY_URL, value.trim().trimEnd('/')).apply()
    }

    /** 中继通道 ID（6 位短 id）。 */
    fun relayChannelId(ctx: Context): String =
        prefs(ctx).getString(KEY_RELAY_CHANNEL, "")!!

    fun setRelayChannelId(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_RELAY_CHANNEL, value.trim()).apply()
    }

    /** 中继通道 Poll Token（手机从中继拉取消息用）。 */
    fun relayPollToken(ctx: Context): String =
        prefs(ctx).getString(KEY_RELAY_POLL_TOKEN, "")!!

    fun setRelayPollToken(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_RELAY_POLL_TOKEN, value.trim()).apply()
    }

    /** token 首次调用时生成（UUID v4），之后持久复用；与 PC 端 [phone].token 一致。 */
    fun token(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, null) ?: UUID.randomUUID().toString().also {
            prefs(ctx).edit().putString(KEY_TOKEN, it).apply()
        }

    fun regenerateToken(ctx: Context): String =
        UUID.randomUUID().toString().also {
            prefs(ctx).edit().putString(KEY_TOKEN, it).apply()
        }

    fun port(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT)

    /** 修改监听端口（1024-65535 合法区间由调用方校验）。 */
    fun setPort(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_PORT, value).apply()
    }

    /**
     * 开机自启状态位：服务因「启动」而运行时置 true，用户手动停止时置 false。
     * BootReceiver 重启后按此决定是否自动拉起（START_STICKY 系统重建不改变此位）。
     */
    fun autoStart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_START, false)

    fun setAutoStart(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_START, value).apply()
    }

    /**
     * 开机自启总开关（用户设置项，默认开）：关掉后无论服务是否在运行，重启手机都不拉起。
     * BootReceiver 实际拉起需同时满足：本开关开 && autoStart（停机前服务在运行）。
     */
    fun bootStart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BOOT_START, true)

    fun setBootStart(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BOOT_START, value).apply()
    }

    /** 在最近任务中隐藏主界面（默认开，与旧版 excludeFromRecents 行为一致）；运行时经 AppTask.setExcludeFromRecents 生效。 */
    fun hideRecents(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_RECENTS, true)

    fun setHideRecents(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_HIDE_RECENTS, value).apply()
    }

    /** 运行日志保留条数（主界面显示上限），可选值见 LOG_LIMITS，默认 40（与旧版一致）。 */
    val LOG_LIMITS = intArrayOf(40, 100, 200, 500)

    fun logLimit(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_LOG_LIMIT, 40)
        return if (LOG_LIMITS.contains(v)) v else 40
    }

    fun setLogLimit(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_LOG_LIMIT, value).apply()
    }
}
