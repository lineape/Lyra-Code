package com.yukisoffd.lyracode.interaction

import com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions
import org.junit.Assert.*
import org.junit.Test

class CompactOverlayOptionsTest {
    @Test fun invalidImportedValuesClampToUsableDimensionsAndEffects() {
        assertEquals(CompactOverlayOptions(240, 160, 0, 40), CompactOverlayOptions(-1, 9000, -20, 500).normalized())
        assertEquals(CompactOverlayOptions(600, 64, 100, 0), CompactOverlayOptions(9000, -1, 300, -20).normalized())
    }

    @Test fun transparencyChangesOnlyBackgroundAndSupportsBothExtremes() {
        assertEquals(255, CompactOverlayOptions(transparency = 0).backgroundAlpha)
        assertEquals(0, CompactOverlayOptions(transparency = 100).backgroundAlpha)
        assertEquals(128, CompactOverlayOptions(transparency = 50).backgroundAlpha)
    }
}
