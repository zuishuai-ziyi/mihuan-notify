package com.mihuan.notifyrelay

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 中继模式引擎：长轮询中继服务器拉取消息并发布通知。
 *
 * 流程：长轮询拉取（服务器挂起直至有消息或超时）→ 逐条发系统通知 → ack 确认消费。
 * 单线程顺序循环；stop() 置位后自行退出（连接读超时会提前打断长轮询）。
 */
class RelayPoller(
    private val getUrl: () -> String,        // 完整长轮询 URL（热读配置）
    private val getPollToken: () -> String,  // X-Poll-Token（热读配置）
    private val onMessage: (id: String, title: String, body: String, source: String?, priority: String) -> Unit,
    private val onLog: (String) -> Unit,
) : Thread("relay-poller") {

    @Volatile
    private var stopped = false

    /** 最近已展示消息 id（防 ack 前崩溃重启后重复通知；LinkedHashSet 保序淘汰）。 */
    private val shownIds = LinkedHashSet<String>()
    private val shownLock = Any()

    override fun run() {
        onLog("中继模式启动，开始长轮询")
        var backoff = RETRY_MIN_MS
        while (!stopped) {
            val got = try {
                pollOnce()
            } catch (e: Exception) {
                onLog("拉取失败: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                -1
            }
            if (stopped) break
            if (got >= 0) {
                backoff = RETRY_MIN_MS
                if (got == 0) sleepQuiet(EMPTY_SLEEP_MS) // 无消息，稍微歇一下再发起下一轮
            } else {
                onLog("${backoff / 1000}s 后重试")
                sleepQuiet(backoff)
                backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
        onLog("中继模式已停止")
    }

    /** 一轮长轮询。返回收到的消息条数；-1=网络/服务错误。 */
    private fun pollOnce(): Int {
        val url = getUrl()
        val token = getPollToken()
        if (url.isEmpty() || token.isEmpty()) {
            sleepQuiet(EMPTY_SLEEP_MS)
            return 0
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            // 读超时略大于服务器挂起时长，留出网络余量
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Poll-Token", token)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readLine() } ?: ""
                onLog("HTTP $code $err")
                return -1
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val data = JSONObject(body)
            val messages = data.optJSONArray("messages") ?: JSONArray()
            if (messages.length() == 0) return 0

            var shown = 0
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                val id = m.optString("id")
                val title = m.optString("title")
                if (id.isEmpty() || title.isEmpty()) continue
                if (alreadyShown(id)) continue
                onMessage(
                    id, title, m.optString("body"),
                    m.optString("source").takeIf { it.isNotEmpty() },
                    if (m.optString("priority") == "high") "high" else "normal",
                )
                markShown(id)
                shown++
                onLog("收到消息 id=$id title=$title")
            }
            ack(conn.url.toString(), token, messages)
            return shown
        } finally {
            conn.disconnect()
        }
    }

    /** ack 已收到的消息（best-effort：失败不重试，下次拉取因服务端未 ack 会重发，由去重兜底）。 */
    private fun ack(pollUrl: String, token: String, messages: JSONArray) {
        try {
            val ids = JSONArray()
            for (i in 0 until messages.length()) {
                val id = messages.optJSONObject(i)?.optString("id")
                if (!id.isNullOrEmpty()) ids.put(id)
            }
            if (ids.length() == 0) return
            // 由长轮询 URL 推导 ack URL：.../messages?... → .../ack
            val base = pollUrl.substringBefore('?').trimEnd('/')
            val ackUrl = base.removeSuffix("/messages") + "/ack"
            val conn = URL(ackUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("X-Poll-Token", token)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("ids", ids).toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) onLog("ack HTTP $code（消息可能重发）")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            onLog("ack 失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun alreadyShown(id: String): Boolean = synchronized(shownLock) { id in shownIds }

    private fun markShown(id: String) = synchronized(shownLock) {
        shownIds.add(id)
        while (shownIds.size > SHOWN_CACHE) {
            val it = shownIds.iterator()
            it.next(); it.remove()
        }
    }

    fun shutdown() {
        stopped = true
    }

    private fun sleepQuiet(ms: Long) {
        try {
            sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 40_000   // 服务器最长挂起 30s+余量
        private const val RETRY_MIN_MS = 3_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val EMPTY_SLEEP_MS = 1_500L
        private const val SHOWN_CACHE = 100

        fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
}
