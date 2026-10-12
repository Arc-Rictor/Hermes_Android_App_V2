package com.arcrictor.hermes.gateway

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Base64

/**
 * Session control over RPC + the two REST fallbacks. See PROTOCOL.md §5 and §1.
 *
 * Id discipline: [resumeStored] and [deleteStored] take STORED ids; everything else takes
 * RUNTIME ids. Variable names say which.
 */
class SessionsApi(
    private val gateway: GatewayClient,
    private val tokens: TokenManager,
    /** Reconnects a dropped socket before each call. */
    private val ensureConnected: suspend () -> Unit = {},
    private val apiFor: (baseUrl: String) -> AuthApi = ::AuthApi,
) {
    private suspend fun rpc(method: String, params: JsonObject, timeoutMs: Long = 120_000): JsonObject {
        ensureConnected()
        return gateway.request(method, params, timeoutMs)
    }

    private fun snapshot(res: JsonObject) =
        GatewayJson.decodeFromJsonElement(LiveSessionSnapshot.serializer(), res)

    suspend fun list(limit: Int = 200): List<SessionRow> =
        GatewayJson.decodeFromJsonElement(
            ListResponse.serializer(SessionRow.serializer()),
            rpc("session.list", buildJsonObject { put("limit", JsonPrimitive(limit)) }),
        ).sessions

    suspend fun create(title: String? = null): LiveSessionSnapshot =
        snapshot(rpc("session.create", buildJsonObject { if (title != null) put("title", JsonPrimitive(title)) }))

    /**
     * Open (or re-open) a session. Images arrive as `[image]` placeholders on mobile.
     * [lazy] skips building the agent (used when we only need a runtime id, e.g. rename).
     */
    suspend fun resumeStored(storedId: String, lazy: Boolean = false): LiveSessionSnapshot =
        snapshot(
            rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", JsonPrimitive(storedId))
                    put("inline_images", JsonPrimitive(false))
                    if (lazy) put("lazy", JsonPrimitive(true))
                },
                timeoutMs = 60_000,
            ),
        )

    /**
     * Rename by stored id. Uses the known runtime id or a lazy resume. Never closes the
     * runtime afterwards: it may be shared with another client (e.g. the desktop app) and
     * `session.close` tears it down for everyone. Idle runtimes are reaped server-side.
     */
    suspend fun rename(storedId: String, runtimeId: String?, title: String): String {
        val rid = runtimeId ?: resumeStored(storedId, lazy = true).session_id
        val res = rpc(
            "session.title",
            buildJsonObject {
                put("session_id", JsonPrimitive(rid))
                put("title", JsonPrimitive(title))
            },
        )
        return res["title"]?.jsonPrimitive?.content ?: title
    }

    /**
     * Send text. Busy sessions queue/steer/redirect server-side; the result status
     * labels the bubble ("queued"/"steering"/"redirected"). No attachments param —
     * attach calls (Phase 4) precede this in send order.
     */
    suspend fun submit(runtimeId: String, text: String): String {
        val res = rpc(
            "prompt.submit",
            buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("text", JsonPrimitive(text))
            },
            timeoutMs = 60_000,
        )
        return res["status"]?.jsonPrimitive?.content ?: "streaming"
    }

    /** Stop the running turn. */
    suspend fun interrupt(runtimeId: String): String {
        val res = rpc(
            "session.interrupt",
            buildJsonObject { put("session_id", JsonPrimitive(runtimeId)) },
            timeoutMs = 30_000,
        )
        return res["status"]?.jsonPrimitive?.content ?: ""
    }

    /** Rebind this socket to a live session (reconnect step 2). Carries open_requests. */
    suspend fun activate(runtimeId: String): LiveSessionSnapshot =
        snapshot(
            rpc(
                "session.activate",
                buildJsonObject { put("session_id", JsonPrimitive(runtimeId)) },
                timeoutMs = 60_000,
            ),
        )

    /** Missed events since the seq watermark (reconnect step 3). */
    suspend fun eventsSince(runtimeId: String, lastSeen: Long): EventsSince =
        GatewayJson.decodeFromJsonElement(
            EventsSince.serializer(),
            rpc(
                "session.events.since",
                buildJsonObject {
                    put("session_id", JsonPrimitive(runtimeId))
                    put("last_seen", JsonPrimitive(lastSeen))
                },
                timeoutMs = 60_000,
            ),
        )

    /**
     * Attach calls for the send flow (PROTOCOL.md §6). Images queue server-side
     * for the next submit and return their server path; PDFs return a page
     * summary; files return nothing queueable — the caller appends [FileAttach.refText].
     */
    suspend fun attachImage(runtimeId: String, contentBase64: String, filename: String): String {
        val res = rpc(
            "image.attach_bytes",
            buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("content_base64", JsonPrimitive(contentBase64))
                put("filename", JsonPrimitive(filename))
            },
            timeoutMs = 120_000,
        )
        return res["path"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun attachPdf(runtimeId: String, contentBase64: String, filename: String): String {
        val res = rpc(
            "pdf.attach",
            buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("content_base64", JsonPrimitive(contentBase64))
                put("filename", JsonPrimitive(filename))
            },
            timeoutMs = 120_000,
        )
        // "Result lists rendered pages" — shape defensive; live-covered.
        val pages = (res["pages"] as? kotlinx.serialization.json.JsonArray)?.size
            ?: res["count"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: (res["text"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { return it }
        return if (pages != null) "$filename: $pages pages attached" else "$filename attached"
    }

    suspend fun attachFile(runtimeId: String, contentBase64: String, name: String): FileAttach {
        val res = rpc(
            "file.attach",
            buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("data_url", JsonPrimitive("data:application/octet-stream;base64,$contentBase64"))
                put("name", JsonPrimitive(name))
            },
            timeoutMs = 120_000,
        )
        return FileAttach(
            path = res["path"]?.jsonPrimitive?.content ?: "",
            refText = res["ref_text"]?.jsonPrimitive?.content ?: "@file:$name",
        )
    }

    /**
     * Delete by stored id (an explicit, destructive user action). Closes the live runtime
     * first when we know it — delete refuses (4023) while live.
     */
    suspend fun deleteStored(storedId: String, runtimeId: String? = null) {        if (runtimeId != null) {
            try {
                rpc("session.close", buildJsonObject { put("session_id", JsonPrimitive(runtimeId)) })
            } catch (_: RpcException) {
                // Already gone — delete proceeds.
            }
        }
        rpc("session.delete", buildJsonObject { put("session_id", JsonPrimitive(storedId)) })
    }

    /** REST fallback for oversized transcripts (resume 4130). Paged, oldest-first. */
    suspend fun messagesPage(storedId: String, limit: Int = 200, offset: Int = 0): List<HistoryMessage> {
        val set = tokens.ensureValid()
        val bytes = apiFor(set.baseUrl).get(
            "/api/sessions/${URLEncoder.encode(storedId, "UTF-8")}/messages",
            mapOf(
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "order" to "oldest",
                "inline_images" to "false",
            ),
            set.accessToken,
        )
        val root = GatewayJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return GatewayJson.decodeFromJsonElement(
            ListSerializer(HistoryMessage.serializer()),
            root["messages"] ?: return emptyList(),
        )
    }

    /** `GET /api/media?path=` → raw image bytes. */
    suspend fun fetchMedia(serverPath: String): ByteArray {
        val set = tokens.ensureValid()
        val bytes = apiFor(set.baseUrl).get("/api/media", mapOf("path" to serverPath), set.accessToken)
        val dataUrl = GatewayJson.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            .jsonObject["data_url"]?.jsonPrimitive?.content
            ?: throw RpcException(-1, "media has no data_url")
        return try {
            // MIME decoder skips line breaks and stray characters.
            Base64.getMimeDecoder().decode(dataUrl.substringAfter(";base64,", ""))
                .also { if (it.isEmpty()) throw IllegalArgumentException("empty") }
        } catch (_: IllegalArgumentException) {
            throw RpcException(-1, "media payload not base64")
        }
    }

    @kotlinx.serialization.Serializable
    private data class ListResponse<T>(val sessions: List<T>)
}
