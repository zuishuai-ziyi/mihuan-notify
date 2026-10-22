package com.mihuan.notifyrelay

import fi.iki.elonen.NanoHTTPD
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 手机端内嵌 HTTP 服务器（协议 v1，见 docs/protocol.md）：
 * - GET  /ping    探活（无鉴权）
 * - POST /notify  接收 PC 转发的消息信封（X-Auth-Token 鉴权）
 */
class NotifyServer(
    port: Int,
    private val token: String,
    private val onMessage: (id: String, title: String, body: String, source: String?, priority: String) -> Unit,
    private val onLog: (String) -> Unit,
) : NanoHTTPD("0.0.0.0", port) {

    /** 消息去重：最近 100 条消息 id（LinkedHashSet 保序，超限淘汰最旧）。 */
    private val seenIds = LinkedHashSet<String>()
    private val seenLock = Any()

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            onLog("500 内部错误: ${e.javaClass.simpleName}: ${e.message}")
            json(Response.Status.INTERNAL_ERROR, "internal")
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        return when {
            session.method == Method.GET && uri == "/ping" -> {
                val body = JSONObject().put("ok", true).put("service", "phone-relay").put("v", 1)
                newFixedLengthResponse(Response.Status.OK, MIME_JSON, body.toString())
            }
            session.method == Method.POST && uri == "/notify" -> handleNotify(session)
            else -> json(Response.Status.NOT_FOUND, "not_found")
        }
    }

    private fun handleNotify(session: IHTTPSession): Response {
        // 鉴权：恒定时间比较，防时序侧信道
        val got = session.headers["x-auth-token"] ?: ""
        if (!MessageDigest.isEqual(got.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) {
            onLog("401 token 不匹配")
            return json(Response.Status.UNAUTHORIZED, "unauthorized")
        }

        // 请求体上限 64KB
        val contentLength = (session.headers["content-length"] ?: "0").toIntOrNull() ?: 0
        if (contentLength > MAX_BODY_BYTES) {
            onLog("413 请求体过大 content-length=$contentLength")
            return json(Response.Status.PAYLOAD_TOO_LARGE, "too_large")
        }

        // 读取请求体：绕过 parseBody（其对非表单类型按 ISO-8859-1 解码，
        // 会把 UTF-8 中文每字节膨胀成一个 Latin-1 乱码字符），
        // 改为按 Content-Length 直接读原始字节，显式 UTF-8 解码
        val buf = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            val n = session.inputStream.read(buf, off, contentLength - off)
            if (n < 0) break
            off += n
        }
        val raw = String(buf, 0, off, Charsets.UTF_8).ifEmpty { "{}" }

        val data = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            onLog("400 JSON 非法")
            return json(Response.Status.BAD_REQUEST, "invalid_json")
        }

        if (data.optInt("v", -1) != 1) {
            return json(Response.Status.BAD_REQUEST, "unsupported_version")
        }
        val id = data.optString("id")
        val title = data.optString("title")
        val body = data.optString("body")
        val priority = if (data.optString("priority") == "high") "high" else "normal"
        val source = data.optString("source").takeIf { it.isNotEmpty() }
        if (id.isEmpty() || title.isEmpty()) {
            onLog("400 缺少 id/title")
            return json(Response.Status.BAD_REQUEST, "id_and_title_required")
        }

        // 去重：重复 id 直接 200 duplicate
        val duplicate = synchronized(seenLock) {
            if (seenIds.contains(id)) {
                true
            } else {
                seenIds.add(id)
                while (seenIds.size > DEDUP_CACHE) {
                    val it = seenIds.iterator()
                    it.next(); it.remove()
                }
                false
            }
        }
        if (duplicate) {
            onLog("200 重复消息 id=$id（忽略）")
            val resp = JSONObject().put("ok", true).put("duplicate", true)
            return newFixedLengthResponse(Response.Status.OK, MIME_JSON, resp.toString())
        }

        onMessage(id, title, body, source, priority)
        onLog("200 收到消息 id=$id title=$title priority=$priority source=$source")
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, "{\"ok\":true}")
    }

    private fun json(status: Response.Status, error: String): Response {
        val body = JSONObject().put("ok", false).put("error", error)
        return newFixedLengthResponse(status, MIME_JSON, body.toString())
    }

    companion object {
        const val MIME_JSON = "application/json"
        const val MAX_BODY_BYTES = 64 * 1024
        const val DEDUP_CACHE = 100
    }
}
