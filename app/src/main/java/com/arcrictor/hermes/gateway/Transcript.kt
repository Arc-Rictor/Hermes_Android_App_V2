package com.arcrictor.hermes.gateway

import kotlinx.serialization.json.JsonPrimitive

/**
 * Projects stored transcript rows into renderable messages. See PROTOCOL.md §7:
 * hidden rows, `[System:` user rows and empty rows are dropped; assistant `tool_calls`
 * are never sent; tool rows carry only name + context preview.
 */
sealed interface UiMessage {
    /** [label] marks send-while-busy bubbles ("Queued"/"Steering"/"Redirected"). */
    data class User(
        val text: String,
        val images: List<ImageRef>,
        val files: List<FileRef>,
        val label: String? = null,
    ) : UiMessage
    data class Assistant(val text: String, val reasoning: String?, val usage: String? = null) : UiMessage
    data class Tool(val name: String, val preview: String) : UiMessage
}

/** Server path fetchable via `GET /api/media?path=`. */
@JvmInline value class ImageRef(val path: String)

/** `@file:` ref text to show as a chip (no download in MVP). */
@JvmInline value class FileRef(val ref: String)

object Transcript {
    /**
     * Parses one `@image:`/`@file:` ref value with the server quoting rule
     * (prompt_attachments.py): wrapped in the first of backtick/`"`/`'` it does not
     * contain when it holds whitespace or brackets, else bare.
     */
    fun parseRefValue(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2) {
            val q = t[0]
            if ((q == '`' || q == '"' || q == '\'') && t.endsWith(q)) return t.substring(1, t.length - 1)
        }
        return t
    }

    private val refLine = Regex("""^@(image|file):(.+)$""")

    /**
     * Splits trailing `@image:`/`@file:` lines off user text.
     * @return Triple(display text, image paths, file refs).
     */
    fun splitRefs(text: String): Triple<String, List<ImageRef>, List<FileRef>> {
        val lines = text.lines()
        val images = mutableListOf<ImageRef>()
        val files = mutableListOf<FileRef>()
        var end = lines.size
        while (end > 0) {
            val m = refLine.matchEntire(lines[end - 1].trim()) ?: break
            val value = parseRefValue(m.groupValues[2])
            if (m.groupValues[1] == "image") images.add(0, ImageRef(value)) else files.add(0, FileRef(value))
            end--
        }
        return Triple(lines.subList(0, end).joinToString("\n").trimEnd(), images, files)
    }

    private fun reasoningOf(m: HistoryMessage): String? {
        val r = m.reasoning ?: m.reasoning_content ?: return null
        return (r as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    fun project(messages: List<HistoryMessage>): List<UiMessage> = buildList {
        for (m in messages) {
            if (m.display_kind == "hidden") continue
            when (m.role) {
                "user" -> {
                    val text = m.text ?: ""
                    if (text.startsWith("[System:")) continue
                    if (text.isBlank()) continue
                    val (body, images, files) = splitRefs(text)
                    if (body.isBlank() && images.isEmpty() && files.isEmpty()) continue
                    add(UiMessage.User(body, images, files))
                }
                "assistant" -> {
                    val text = m.text ?: ""
                    val reasoning = reasoningOf(m)
                    if (text.isBlank() && reasoning == null) continue
                    add(UiMessage.Assistant(text, reasoning))
                }
                "tool" -> {
                    add(UiMessage.Tool(m.name ?: "tool", m.context ?: ""))
                }
                "system" -> {
                    val text = m.text ?: ""
                    if (text.isBlank()) continue
                    add(UiMessage.Assistant(text, null))
                }
                else -> continue
            }
        }
    }
}
