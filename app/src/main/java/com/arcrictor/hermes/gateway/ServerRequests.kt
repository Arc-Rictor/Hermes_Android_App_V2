package com.arcrictor.hermes.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Server→client request cards (PROTOCOL.md §8): approvals, clarifications and
 * masked secret inputs. Pure state + answer building; transport lives in
 * [TurnSession] (live `srq-` answers) and [SessionsApi] (`approval.respond`).
 */
sealed interface RequestCard {
    /** Live `srq-` id, or `pending:<request_id>` for snapshot-only approvals. */
    val id: String
    val requestId: String
    val method: String
    val expired: Boolean
    val expiredReason: String?
}

data class ApprovalCard(
    override val id: String,
    override val requestId: String,
    val command: String,
    val description: String,
    /** Exactly the offered choices; never invented (missing → once + deny). */
    val choices: List<String>,
    val toolName: String?,
    override val expired: Boolean = false,
    override val expiredReason: String? = null,
) : RequestCard {
    override val method: String = "approval"
}

data class ClarifyQuestion(
    val qid: String,
    val question: String,
    /** Null = free text. */
    val choices: List<String>?,
    val multiSelect: Boolean,
)

data class ClarifyCard(
    override val id: String,
    override val requestId: String,
    val questions: List<ClarifyQuestion>,
    override val expired: Boolean = false,
    override val expiredReason: String? = null,
) : RequestCard {
    override val method: String = "clarify"
}

enum class SecretKind { SUDO, SECRET, VAULT_UNLOCK, VAULT_CODE }

data class SecretCard(
    override val id: String,
    override val requestId: String,
    override val method: String,
    val kind: SecretKind,
    val title: String,
    val prompt: String,
    override val expired: Boolean = false,
    override val expiredReason: String? = null,
) : RequestCard

/** Open cards for one session. All methods are plain (non-suspend) and JVM-tested. */
class ServerRequestStore {    private val _cards = MutableStateFlow<List<RequestCard>>(emptyList())
    val cards: StateFlow<List<RequestCard>> = _cards

    /** Live frame or `open_requests` entry. Same ids as live frames: dedupe by id. */
    fun addLive(srqId: String, method: String, params: JsonObject): RequestCard? {
        _cards.value.firstOrNull { it.id == srqId }?.let { return it }
        val card = parseCard(srqId, method, params) ?: return null
        _cards.update { it + card }
        return card
    }

    fun rehydrate(entries: List<OpenRequestEntry>): List<RequestCard> =
        entries.mapNotNull { addLive(it.id, it.method, it.params) }

    /** Snapshot-only approval (no live frame): answered via `approval.respond`. */
    fun addPendingApproval(params: JsonObject): RequestCard? {
        val requestId = params.string("request_id") ?: return null
        val id = "pending:$requestId"
        _cards.value.firstOrNull { it.id == id }?.let { return it }
        val card = parseApproval(id, requestId, params)
        _cards.update { it + card }
        return card
    }

    /** `request.cancel` names the `srq-` id. */
    fun expireById(srqId: String, reason: String?) {
        _cards.update { list ->
            list.map { if (it.id == srqId && !it.expired) it.withExpired(reason) else it }
        }
    }

    /** `approval.cancelled` names `request_ids[]`. */
    fun expireByRequestIds(requestIds: List<String>, reason: String?) {
        _cards.update { list ->
            list.map { if (it.requestId in requestIds && !it.expired) it.withExpired(reason) else it }
        }
    }

    fun markAnswered(id: String) {
        _cards.update { list -> list.filterNot { it.id == id } }
    }

    fun activeCount(): Int = _cards.value.count { !it.expired }

    companion object {
        fun parseCard(srqId: String, method: String, params: JsonObject): RequestCard? {
            val requestId = params.string("request_id") ?: srqId
            return when (method) {
                "approval" -> parseApproval(srqId, requestId, params)
                "clarify" -> parseClarify(srqId, requestId, params)
                "sudo" -> SecretCard(
                    srqId, requestId, method, SecretKind.SUDO,
                    "Elevated access",
                    params.string("command")?.takeIf { it.isNotBlank() } ?: "A command needs elevated access.",
                )
                "secret" -> SecretCard(
                    srqId, requestId, method, SecretKind.SECRET,
                    params.string("env_var") ?: "Secret",
                    params.string("prompt") ?: "The agent needs a secret value.",
                )
                "vault.unlock_prompt" -> SecretCard(
                    srqId, requestId, method, SecretKind.VAULT_UNLOCK,
                    "Unlock ${params.string("display_name") ?: "vault"}",
                    "Enter the vault password.",
                )
                "vault.code" -> SecretCard(
                    srqId, requestId, method, SecretKind.VAULT_CODE,
                    "Vault code",
                    listOfNotNull(params.string("site"), params.string("hint")).joinToString(" · ")
                        .ifBlank { "Enter the vault code." },
                )
                else -> null
            }
        }

        fun parseApproval(srqId: String, requestId: String, params: JsonObject): ApprovalCard {
            val listed = (params["choices"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .orEmpty()
            return ApprovalCard(
                id = srqId,
                requestId = requestId,
                command = params.string("command") ?: "",
                description = params.string("description") ?: "",
                choices = listed.ifEmpty { listOf("once", "deny") },
                toolName = params.string("tool_name"),
            )
        }

        fun parseClarify(srqId: String, requestId: String, params: JsonObject): ClarifyCard? {
            val questions = (params["questions"] as? JsonArray).orEmpty().mapNotNull { q ->
                val o = q as? JsonObject ?: return@mapNotNull null
                val qid = o.string("qid") ?: return@mapNotNull null
                ClarifyQuestion(
                    qid = qid,
                    question = o.string("question") ?: "",
                    choices = (o["choices"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                    multiSelect = (o["multi_select"] as? JsonPrimitive)?.content == "true",
                )
            }
            if (questions.isEmpty()) return null
            return ClarifyCard(srqId, requestId, questions)
        }

        /** One submit answers all questions; blank answers go as null. */
        fun clarifyResult(answers: Map<String, String?>): JsonObject =
            buildJsonObject {
                put("answers", buildJsonObject {
                    answers.forEach { (qid, value) ->
                        put(qid, value?.let { JsonPrimitive(it) } ?: JsonNull)
                    }
                })
            }

        fun approvalResult(choice: String): JsonObject =
            buildJsonObject { put("choice", JsonPrimitive(choice)) }

        fun secretResult(value: String): JsonObject =
            buildJsonObject { put("value", JsonPrimitive(value)) }

        private fun RequestCard.withExpired(reason: String?): RequestCard = when (this) {
            is ApprovalCard -> copy(expired = true, expiredReason = reason)
            is ClarifyCard -> copy(expired = true, expiredReason = reason)
            is SecretCard -> copy(expired = true, expiredReason = reason)
        }
    }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
