package com.yukisoffd.lyracode.interaction.overlay

import com.yukisoffd.lyracode.interaction.session.DeviceChatState
import com.yukisoffd.lyracode.interaction.session.ManualControlState
import com.yukisoffd.lyracode.interaction.session.ManualControlStatus

internal fun ManualControlState.canUseCompactChat(): Boolean = chat.running && approval == null &&
    !(selection?.automatic == false && status == ManualControlStatus.TARGET_SELECTED)

/** Only preview this turn's prose; old replies and raw tool results do not describe current work. */
internal fun DeviceChatState.compactPreview(): String {
    val currentTurn = messages.asReversed().takeWhile { it.role != "user" }
    val assistant = currentTurn.firstOrNull { it.role == "assistant" && (it.text.isNotBlank() || it.thinking.isNotBlank()) }
    val text = assistant?.let { it.text.ifBlank { it.thinking } }.orEmpty()
    // Bound only this one-line preview, retaining the beginning and complete transcript separately.
    val prefix = text.take(160).replace(Regex("\\s+"), " ").trim()
    return prefix + if (text.length > 160) "…" else ""
}
