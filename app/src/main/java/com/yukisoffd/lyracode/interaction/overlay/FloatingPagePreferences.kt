package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The main process owns preferences; changes reach the overlay through its existing state IPC. */
internal object FloatingPagePreferences {
    private val state = MutableStateFlow(CompactOverlayOptions())
    private var initialized = false
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("floating_page_settings", Context.MODE_PRIVATE)

    @Synchronized fun observe(context: Context): StateFlow<CompactOverlayOptions> {
        if (!initialized) {
            val prefs = preferences(context)
            state.value = CompactOverlayOptions(
                prefs.getInt("width_dp", 340), prefs.getInt("height_dp", 80),
                prefs.getInt("transparency", 25), prefs.getInt("blur_dp", 24),
            ).normalized()
            initialized = true
        }
        return state.asStateFlow()
    }

    @Synchronized fun update(context: Context, options: CompactOverlayOptions) {
        observe(context)
        val value = options.normalized()
        preferences(context).edit()
            .putInt("width_dp", value.widthDp).putInt("height_dp", value.heightDp)
            .putInt("transparency", value.transparency).putInt("blur_dp", value.blurDp).apply()
        state.value = value
    }
}
