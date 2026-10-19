package com.yukisoffd.lyracode.interaction.session

internal data class DeviceChatMessage(val id: Long, val role: String, val text: String, val thinking: String = "", val toolName: String = "", val createdAt: Long = System.currentTimeMillis())

/** Complete session transcript; screen trees, bitmaps and credentials stay outside the UI state. */
internal data class DeviceChatState(
    val messages: List<DeviceChatMessage> = emptyList(),
    val running: Boolean = false,
    val status: String = "",
    val providerLabel: String = "",
    val modelLabel: String = "",
    val workspaceLabel: String = "",
    val configurationOptions: String = "{}",
    val pureMode: Boolean = false,
    val compactOptions: com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions = com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions(),
)
