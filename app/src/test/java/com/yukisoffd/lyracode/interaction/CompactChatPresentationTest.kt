package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.overlay.canUseCompactChat
import com.yukisoffd.lyracode.interaction.overlay.compactPreview
import com.yukisoffd.lyracode.interaction.session.DeviceApproval
import com.yukisoffd.lyracode.interaction.session.DeviceChatMessage
import com.yukisoffd.lyracode.interaction.session.DeviceChatState
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import org.junit.Assert.*
import org.junit.Test

class CompactChatPresentationTest {
    @Test fun previewUsesCurrentTurnAndRetainsTheBeginning() {
        val old = DeviceChatMessage(1, "assistant", "previous answer")
        val user = DeviceChatMessage(2, "user", "new task")
        assertEquals("", DeviceChatState(messages = listOf(old, user)).compactPreview())
        val prose = "Checking the page\n" + "details ".repeat(80)
        val current = DeviceChatMessage(3, "assistant", prose)
        val chat = DeviceChatState(messages = listOf(old, user, current, DeviceChatMessage(4, "tool", "raw result")))
        assertTrue(chat.compactPreview().startsWith("Checking the page details"))
        assertTrue(chat.compactPreview().endsWith("…"))
        assertFalse(chat.compactPreview().contains('\n'))
        assertEquals(prose, chat.messages[2].text)
    }

    @Test fun previewFallsBackToCurrentReasoningWithoutShowingToolResults() {
        val thinking = DeviceChatMessage(1, "assistant", "", thinking = "Finding the next control")
        val tool = DeviceChatMessage(2, "tool", "raw result", toolName = "device_observe")
        assertEquals("Finding the next control", DeviceChatState(messages = listOf(thinking, tool)).compactPreview())
        assertEquals("", DeviceChatState(messages = listOf(tool)).compactPreview())
    }

    @Test fun compactRequiresRunningAndRestoresForUserQuestions() {
        val idle = ManualControlState()
        assertFalse(idle.canUseCompactChat())
        val running = idle.copy(chat = DeviceChatState(running = true))
        assertTrue(running.canUseCompactChat())
        assertFalse(running.copy(approval = DeviceApproval("id", "Question", "Please answer", null, false, textInput = true)).canUseCompactChat())
    }
}
