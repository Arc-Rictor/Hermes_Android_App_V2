package com.arcrictor.hermes.gateway

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session control over RPC + the two REST fallbacks. See PROTOCOL.md §5 and §1.
 *
 * Id discipline: [resume] and [delete] take STORED ids; everything else takes RUNTIME ids.
 * Variable names say which.
 */
class SessionsApi(
    private val gateway: GatewayClient,
    private val tokens: TokenManager,
    private val apiFor: (baseUrl: String) -> AuthApi = ::AuthApi,
) {
    suspend fun list(limit: Int = 200): List<SessionRow> {
        val res = gateway.request(
            "session.list",
            buildJsonObject { put("limit", JsonPrimitive(limit)) },
        )
        return GatewayJson.decodeFromJsonElement(
            ListResponse.serializer(SessionRow.serializer()),
            res,
        ).sessions
    }

    suspend fun create(title: String? = null): LiveSessionSnapshot {
        val params = buildJsonObject {
            if (title != null) put("title", JsonPrimitive(title))
        }
        return GatewayJson.decodeFromJsonElement(
            LiveSessionSnapshot.serializer(),
            gateway.request("session.create", params),
        )
    }

    /** Open (or re-open) a session. Images arrive as `[image]` placeholders on mobile. */
    suspend fun resumeStored(storedId: String): LiveSessionSnapshot =
        GatewayJson.decodeFromJsonElement(
            LiveSessionSnapshot.serializer(),
            gateway.request(
                "session.resume",
                buildJsonObject {
                    put("session_id", JsonPrimitive(storedId))
                    put("inline_images", JsonPrimitive(false))
                },
                timeoutMs = 60_000,
            ),
        )

    suspend fun setTitle(runtimeId: String, title: String): String {
        val res = gateway.request(
            "session.title",
            buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("title", JsonPrimitive(title))
            },
        )
        return res["title"]?.jsonPrimitive?.content ?: title
    }

    suspend fun closeRuntime(runtimeId: String) {
        gateway.request(
            "session.close",
            buildJsonObject { put("session_id", JsonPrimitive(runtimeId)) },
        )
    }

    /**
     * Delete by stored id. Closes the live session first when [runtimeId] is known —
     * delete refuses (4023) while live.
     */
    suspend fun deleteStored(storedId: String, runtimeId: String? = null) {
        if (runtimeId != null) {
            try {
                closeRuntime(runtimeId)
            } catch (_: Exception) {
                // Already dead — delete proceeds.
            }
        }
        gateway.request(
            "session.delete",
            buildJsonObject { put("session_id", JsonPrimitive(storedId)) },
        )
    }

    /** REST fallback for oversized transcripts (resume 4130). Paged, oldest-first. */
    suspend fun messagesPage(
        storedId: String,
        limit: Int = 200,
        offset: Int = 0,
    ): List<HistoryMessage> {
        val set = tokens.ensureValid()
        val bytes = apiFor(set.baseUrl).get(
            "/api/sessions/$storedId/messages",
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

    /** `GET /api/media?path=` → raw image bytes. Results are cacheable by path. */
    suspend fun fetchMedia(serverPath: String): ByteArray {
        val set = tokens.ensureValid()
        val bytes = apiFor(set.baseUrl).get(
            "/api/media",
            mapOf("path" to serverPath),
            set.accessToken,
        )
        val dataUrl = GatewayJson.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            .jsonObject["data_url"]?.jsonPrimitive?.content
            ?: throw RpcException(-1, "media has no data_url")
        val clean = dataUrl.substringAfter(";base64,", "")
            .filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (clean.isEmpty()) throw RpcException(-1, "media payload empty")
        return try {
            java.util.Base64.getDecoder().decode(clean)
        } catch (_: IllegalArgumentException) {
            throw RpcException(-1, "media payload not base64")
        }
    }

    @kotlinx.serialization.Serializable
    private data class ListResponse<T>(val sessions: List<T>)
}
