package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ChatMessage
import org.json.JSONObject
import kotlinx.coroutines.ensureActive

/** Finds the final tool group without dropping the results already stored for it. */
internal object PendingToolCallReplay {
    data class Group(val calls: List<ToolCall>, val answered: Set<String>)

    fun plan(messages: List<ChatMessage>): Group? {
        val history = messages.filterNot { it.isLocalRequestErrorMessage() || it.isEmptyAssistantPlaceholder() }
        val index = history.indexOfLast { it.role != "tool" }
        if (index < 0 || history[index].role != "assistant") return null
        val saved = history[index].rawJson ?: return null
        val raw = try { JSONObject(saved) } catch (error: Exception) {
            throw IllegalStateException("Malformed saved assistant message; Continue stopped before replay", error)
        }
        val array = raw.optJSONArray("tool_calls") ?: return null
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: error("Malformed saved tool call")
            val fn = item.optJSONObject("function") ?: error("Malformed saved tool function")
            val id = item.optString("id")
            val name = fn.optString("name")
            require(id.isNotBlank() && name.isNotBlank() && calls.none { it.id == id }) { "Malformed or duplicate saved tool call id" }
            val arguments = fn.optString("arguments")
            val parsed = try { JSONObject(arguments) } catch (_: Exception) { JSONObject() }
            calls.add(ToolCall(id, name, parsed, arguments))
        }
        if (calls.isEmpty()) return null
        val answered = history.drop(index + 1).mapNotNull { it.toolCallId }.toSet()
        return Group(calls, answered)
    }

    // An answered identical call can own the open batch. Without its persisted call id,
    // choosing the unanswered call could repeat side effects, so require an unambiguous match.
    fun uniqueUnansweredMatch(matches: List<ToolCall>, answered: Set<String>): ToolCall? =
        matches.singleOrNull()?.takeUnless { it.id in answered }

    fun storedOutput(name: String, output: String, limit: Int): String =
        if (name == "run_sub_agents") output else output.take(limit)

    suspend fun run(
        group: Group,
        replayId: String?,
        execute: suspend (ToolCall) -> String,
        interrupted: (ToolCall) -> String,
        persist: (ToolCall, String) -> Unit,
        close: (ToolCall) -> Unit,
        updated: suspend (ToolCall) -> Unit,
    ) {
        for (call in group.calls) {
            if (call.id in group.answered) continue
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val replay = call.id == replayId
            val result = if (replay) execute(call) else interrupted(call)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            persist(call, result)
            if (replay) close(call)
            updated(call)
        }
    }

}
