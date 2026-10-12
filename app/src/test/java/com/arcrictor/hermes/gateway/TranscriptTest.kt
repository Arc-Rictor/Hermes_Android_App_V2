package com.arcrictor.hermes.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private fun msg(
        role: String,
        text: String? = null,
        displayKind: String? = null,
        name: String? = null,
        context: String? = null,
        reasoning: String? = null,
    ) = HistoryMessage(
        role = role, text = text, display_kind = displayKind, name = name, context = context,
        reasoning = reasoning?.let { kotlinx.serialization.json.JsonPrimitive(it) },
    )

    @Test fun `user text with refs splits`() {
        val (body, images, files) = Transcript.splitRefs(
            "Look at these\n@image:/home/jarvis/.hermes/images/a.png\n@file:\"my report.pdf\"",
        )
        assertEquals("Look at these", body)
        assertEquals(listOf(ImageRef("/home/jarvis/.hermes/images/a.png")), images)
        assertEquals(listOf(FileRef("my report.pdf")), files)
    }

    @Test fun `quoting styles parse`() {
        assertEquals("a b.png", Transcript.parseRefValue("`a b.png`"))
        assertEquals("a(b).png", Transcript.parseRefValue("\"a(b).png\""))
        assertEquals("plain.png", Transcript.parseRefValue("plain.png"))
    }

    @Test fun `non-trailing refs stay in text`() {
        val (body, images, _) = Transcript.splitRefs("@image:x.png\nsome text after")
        assertEquals("@image:x.png\nsome text after", body)
        assertTrue(images.isEmpty())
    }

    @Test fun `projection drops hidden, system-prefixed and empty rows`() {
        val out = Transcript.project(
            listOf(
                msg("user", "hello"),
                msg("user", "[System: hidden scaffolding]", displayKind = "hidden"),
                msg("user", "[System: memory recall]"),
                msg("user", "   "),
                msg("assistant", "", reasoning = "thinking about it"),
                msg("assistant", "  "),
                msg("assistant", "only tool calls, no text"),
            ),
        )
        assertEquals(3, out.size)
        assertTrue(out[0] is UiMessage.User)
        assertEquals("thinking about it", (out[1] as UiMessage.Assistant).reasoning)
        assertEquals("", (out[1] as UiMessage.Assistant).text)
        assertEquals("only tool calls, no text", (out[2] as UiMessage.Assistant).text)
    }

    @Test fun `tool-only assistant row without text or reasoning is dropped`() {
        // Per PROTOCOL §7 the server drops these before they reach history; if one ever
        // arrives (empty text, no reasoning), the client drops it too.
        val out = Transcript.project(listOf(msg("assistant", "")))
        assertTrue(out.isEmpty())
    }

    @Test fun `tool rows project with preview`() {
        val out = Transcript.project(listOf(msg("tool", name = "terminal", context = "ls -la → …")))
        val tool = out.single() as UiMessage.Tool
        assertEquals("terminal", tool.name)
        assertTrue(tool.preview.startsWith("ls"))
    }

    @Test fun `unknown roles are skipped`() {
        assertTrue(Transcript.project(listOf(msg("daemon", "x"))).isEmpty())
    }
}
