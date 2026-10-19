package com.yukisoffd.lyracode.interaction.model

import kotlin.math.roundToInt

internal data class CompactOverlayOptions(
    val widthDp: Int = 340,
    val heightDp: Int = 80,
    val transparency: Int = 25,
    val blurDp: Int = 24,
) {
    fun normalized() = copy(
        widthDp = widthDp.coerceIn(240, 600),
        heightDp = heightDp.coerceIn(64, 160),
        transparency = transparency.coerceIn(0, 100),
        blurDp = blurDp.coerceIn(0, 40),
    )

    val backgroundAlpha: Int get() = ((100 - transparency.coerceIn(0, 100)) * 2.55f).roundToInt()
}
