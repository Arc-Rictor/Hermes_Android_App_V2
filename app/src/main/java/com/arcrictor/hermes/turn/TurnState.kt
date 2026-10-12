package com.arcrictor.hermes.turn

/** One tool timeline entry. `endMs == null` means still running. */
data class ToolEntry(
    val toolId: String,
    val name: String,
    val preview: String,
    val startMs: Long,
    val endMs: Long? = null,
    /** Server-reported seconds; falls back to wall clock. */
    val durationS: Double? = null,
    val summary: String = "",
    val failed: Boolean = false,
    val risky: Boolean = false,
) {
    val open: Boolean get() = endMs == null

    fun displayDuration(nowMs: Long): String {
        durationS?.let { return formatDuration(it) }
        return formatDuration((endMs ?: nowMs).minus(startMs) / 1000.0)
    }

    companion object {
        fun formatDuration(seconds: Double): String = when {
            seconds < 0 -> "0s"
            seconds < 1 -> "${(seconds * 1000).toLong()}ms"
            seconds < 60 -> if (seconds == seconds.toLong().toDouble()) "${seconds.toLong()}s" else "%.1fs".format(seconds)
            else -> "%d:%02d".format((seconds / 60).toLong(), (seconds % 60).toLong())
        }
    }
}

data class TurnUsage(val input: Long, val output: Long) {
    /** Compact caption, e.g. "12.4k in · 3.1k out". */
    fun label(): String = "${k(input)} in · ${k(output)} out"

    companion object {
        private fun k(n: Long): String = when {
            n < 1000 -> "$n"
            else -> "%.1fk".format(n / 1000.0)
        }
    }
}

/** Activity header state. Follows SPEC.md R3.2 exactly; `NeedsInput` wins in the header. */
sealed interface TurnActivity {
    data object Idle : TurnActivity
    data class Working(val status: String) : TurnActivity
    data object Thinking : TurnActivity
    data class UsingTool(val name: String) : TurnActivity
    data object Responding : TurnActivity
    data object NeedsInput : TurnActivity
    data class Error(val message: String) : TurnActivity
    data class Stopped(val detail: String) : TurnActivity
}

/**
 * The whole live turn. UI renders this; it never interprets events.
 * History rows live outside (server snapshot + frozen turns); only the running
 * turn lives here, frozen into transcript rows on `message.complete`.
 */
data class TurnState(
    val activity: TurnActivity = TurnActivity.Idle,
    val reply: String = "",
    val thinking: String = "",
    /** Agent commentary mid-turn (R3.5); rendered as interim notes, never the reply. */
    val notes: List<String> = emptyList(),
    val tools: List<ToolEntry> = emptyList(),
    val usage: TurnUsage? = null,
    /** Open approval/question cards (snapshot count; live cards arrive in Phase 5). */
    val openRequestCount: Int = 0,
    val turnStartMs: Long? = null,
    /** Shown after a resume-fallback recovery that skipped live steps (R6.3). */
    val gapNotice: String? = null,
) {
    val openTools: List<ToolEntry> get() = tools.filter { it.open }

    /** A turn is on screen (streaming, working, or awaiting input). */
    val turnAlive: Boolean get() = running || openRequestCount > 0

    private val running: Boolean get() = activity is TurnActivity.Working ||
        activity is TurnActivity.Thinking ||
        activity is TurnActivity.UsingTool ||
        activity is TurnActivity.Responding
}
