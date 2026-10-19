package com.yukisoffd.lyracode.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTokenCostsTest {
    private fun message(role: String, raw: String? = null) = ChatMessage(
        1, 1, role, "body", "think", "", "model", null, raw, createdAt = 0,
    )

    @Test fun preservesRoleSpecificPromptAndToolOutputCosts() {
        val count = { text: String -> text.length.toLong() }
        assertEquals(MessageTokenCosts(12, 0), message("user").calculateTokenCosts(count))
        assertEquals(MessageTokenCosts(12, 0), message("tool").calculateTokenCosts(count))
        assertEquals(MessageTokenCosts(17, 0), message("runtime_context").calculateTokenCosts(count))
        val raw = """{"tool_calls":[{"function":{"name":"read","arguments":"{}"}},{"name":"write","input":{"x":1}}]}"""
        val toolText = "read\n{}\nwrite\n{\"x\":1}\n"
        assertEquals(toolText, toolCallsOutputText(raw))
        assertEquals(MessageTokenCosts(17L + toolText.length, 9L + toolText.length), message("ASSISTANT", raw).calculateTokenCosts(count))
        assertEquals(MessageTokenCosts(17, 9), message("assistant", "broken JSON").calculateTokenCosts(count))
    }
}
