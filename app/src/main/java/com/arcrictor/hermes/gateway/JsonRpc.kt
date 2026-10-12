package com.arcrictor.hermes.gateway

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * JSON-RPC framing for /api/ws. See PROTOCOL.md §4.
 *
 * Client request ids are `"a<n>"` (never `srq-`, which is server-owned).
 * Frames are told apart by the presence of `method`, not by id shape.
 */
sealed interface GatewayFrame {
    /** `{"jsonrpc","id","result"}` or `{"jsonrpc","id","error"}`. */
    data class Response(val id: String, val result: JsonElement?, val error: RpcError?) : GatewayFrame
    /** `{"jsonrpc","method":"event","params":{type, session_id, payload?, seq?}}`. */
    data class Event(
        val type: String,
        val sessionId: String,
        val payload: JsonObject,
        val seq: Long?,
    ) : GatewayFrame
    /** `{"jsonrpc","id":"srq-…","method":<name>,"params":{session_id,…}}`. */
    data class ServerRequest(val id: String, val method: String, val params: JsonObject) : GatewayFrame
    /** Anything that is not a frame at all (log + count, never crash). */
    data class Unparseable(val raw: String, val reason: String) : GatewayFrame
}

data class RpcError(val code: Long, val message: String)

object JsonRpc {
    fun classify(raw: String): GatewayFrame {
        val el = try {
            GatewayJson.parseToJsonElement(raw)
        } catch (_: Exception) {
            return GatewayFrame.Unparseable(raw, "not JSON")
        }
        val obj = el as? JsonObject ?: return GatewayFrame.Unparseable(raw, "not an object")
        fun str(el: JsonElement?): String? =
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        val method = str(obj["method"])
        val id = (obj["id"] as? JsonPrimitive)?.content
        if (method == null) {
            // Response to our request.
            if (id == null) return GatewayFrame.Unparseable(raw, "no id, no method")
            val err = obj["error"] as? JsonObject
            return GatewayFrame.Response(
                id = id,
                result = obj["result"],
                error = err?.let {
                    // codes are JSON numbers; .content reads numbers and strings alike.
                    RpcError(
                        code = (it["code"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1,
                        message = str(it["message"]) ?: "",
                    )
                },
            )
        }
        if (method == "event") {
            val params = obj["params"] as? JsonObject
                ?: return GatewayFrame.Unparseable(raw, "event without params")
            val type = str(params["type"])
                ?: return GatewayFrame.Unparseable(raw, "event without type")
            return GatewayFrame.Event(
                type = type,
                sessionId = str(params["session_id"]) ?: "",
                payload = (params["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
                seq = (params["seq"] as? JsonPrimitive)?.content?.toLongOrNull(),
            )
        }
        // Server→client request.
        if (id == null) return GatewayFrame.Unparseable(raw, "server request without id")
        return GatewayFrame.ServerRequest(
            id = id,
            method = method,
            params = (obj["params"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
    }

    fun request(id: String, method: String, params: JsonObject): String =
        """{"jsonrpc":"2.0","id":${q(id)},"method":${q(method)},"params":${GatewayJson.encodeToString(JsonObject.serializer(), params)}}"""

    fun answer(id: String, result: JsonObject): String =
        """{"jsonrpc":"2.0","id":${q(id)},"result":${GatewayJson.encodeToString(JsonObject.serializer(), result)}}"""

    fun errorAnswer(id: String, code: Long, message: String): String =
        """{"jsonrpc":"2.0","id":${q(id)},"error":{"code":$code,"message":${q(message)}}}"""

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }
}

/**
 * Log redaction (SPEC.md R1.5): passwords, tokens, tickets and cookie values never reach
 * logs or exported captures. Long secrets keep 6 leading chars + "…", short ones become "***".
 */
object Redact {
    private val jsonSecretField =
        """"(access_token|refresh_token|code_verifier|password|ticket|refreshToken)"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
    private val querySecret = """([?&](?:ticket|token)=)([^&\s"]+)""".toRegex()
    private val cookieValue = """(?i)(Cookie:\s*[^\s=;]+?=)([^\s;]+)""".toRegex()
    private val bearerValue = """(?i)(Authorization:\s*Bearer\s+)(\S+)""".toRegex()

    private fun mask(secret: String): String =
        if (secret.length > 10) secret.take(6) + "…" else "***"

    fun text(s: String): String {
        var out = s
        out = jsonSecretField.replace(out) { m -> """"${m.groupValues[1]}":"${mask(m.groupValues[2])}"""" }
        // `"code"` is a real field name too, but only secret-shaped values are long;
        // short codes stay readable.
        out = Regex(""""code"\s*:\s*"([^"]{20,})"""").replace(out) { m ->
            """"code":"${mask(m.groupValues[1])}""""
        }
        out = querySecret.replace(out) { m -> m.groupValues[1] + mask(m.groupValues[2]) }
        out = cookieValue.replace(out) { m -> m.groupValues[1] + mask(m.groupValues[2]) }
        out = bearerValue.replace(out) { m -> m.groupValues[1] + mask(m.groupValues[2]) }
        return out
    }
}
