package com.yukisoffd.lyracode

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions
import com.yukisoffd.lyracode.interaction.overlay.CompactChatBar
import com.yukisoffd.lyracode.interaction.overlay.FloatingPagePreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.LocalDate

class FloatingPageSettingsInstrumentedTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val saved = mutableMapOf<String, Map<String, *>>()
    private lateinit var originalOptions: CompactOverlayOptions
    private val setup = object : TestWatcher() {
        override fun starting(description: Description) {
            for (name in listOf("lyra_settings", "lyra_update_state", "first_use_consent", "android_compatibility", "floating_page_settings"))
                saved[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            originalOptions = FloatingPagePreferences.observe(context).value
            FloatingPagePreferences.update(context, CompactOverlayOptions())
            FirstUseConsentStore(context).accept()
            context.getSharedPreferences("android_compatibility", Context.MODE_PRIVATE).edit().putBoolean("android_17_local_network_rationale_seen", true).commit()
            context.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE).edit().putString("last_daily_check_date", LocalDate.now().toString()).commit()
            AppSettings(context).apply {
                languageMode = AppSettings.LANGUAGE_ZH_CN
                fontScaleMode = AppSettings.FONT_SCALE_NORMAL
                predictiveBackEnabled = false
            }
        }

        override fun finished(description: Description) {
            FloatingPagePreferences.update(context, originalOptions)
            for ((name, entries) in saved) {
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for ((key, value) in entries) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                editor.commit()
            }
        }
    }
    @get:Rule val rule: RuleChain = RuleChain.outerRule(setup).around(compose)
    private fun text(id: Int) = compose.activity.getString(id)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun openSettings() {
        val rationale = text(R.string.local_network_permission_title)
        if (compose.onAllNodesWithText(rationale).fetchSemanticsNodes().isNotEmpty()) {
            val dialog = isDialog() and hasAnyDescendant(hasText(rationale))
            compose.onNode(hasText(text(R.string.action_later)) and hasAnyAncestor(dialog)).performClick()
        }
        compose.onNodeWithContentDescription(text(R.string.cd_menu)).performClick()
        compose.onNodeWithText(text(R.string.title_settings)).performClick()
        compose.onNodeWithText(text(R.string.menu_device_interaction)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.device_interaction_open_manual_control)).performScrollTo().performClick()
        val stop = compose.onNodeWithText(text(R.string.manual_control_stop)).fetchSemanticsNode().boundsInRoot
        val entry = compose.onNodeWithText(text(R.string.floating_page_settings)).performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue(entry.top >= stop.bottom)
        compose.onNodeWithText(text(R.string.floating_page_settings)).performClick()
    }

    @Test fun slidersUpdatePinnedPreviewPersistAndResetWithoutStartingATask() {
        openSettings()
        val preview = compose.onNodeWithTag("floating-preview")
        val initialPosition = preview.fetchSemanticsNode().boundsInRoot.top
        fun adjust(tag: String, value: Float) {
            compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
            preview.assertIsDisplayed()
            assertEquals(initialPosition, preview.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        adjust("floating-width", 280f)
        adjust("floating-height", 120f)
        adjust("floating-transparency", 65f)
        adjust("floating-blur", 12f)
        compose.runOnIdle {
            assertEquals(CompactOverlayOptions(280, 120, 65, 12), FloatingPagePreferences.observe(context).value)
            val prefs = context.getSharedPreferences("floating_page_settings", Context.MODE_PRIVATE)
            assertEquals(280, prefs.getInt("width_dp", 0))
            assertEquals(120, prefs.getInt("height_dp", 0))
            assertEquals(65, prefs.getInt("transparency", 0))
            assertEquals(12, prefs.getInt("blur_dp", 0))
            val bar = descendants(compose.activity.window.decorView).filterIsInstance<CompactChatBar>().single()
            assertEquals((280 * bar.resources.displayMetrics.density).toInt(), bar.width)
            assertEquals((120 * bar.resources.displayMetrics.density).toInt(), bar.height)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.externalCacheDir, "floating-page-settings-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithContentDescription(text(R.string.cd_back)).performClick()
        compose.onNodeWithText(text(R.string.manual_control_start)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.floating_page_settings)).performClick()
        compose.onNodeWithText(text(R.string.floating_page_reset)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(CompactOverlayOptions(), FloatingPagePreferences.observe(context).value) }
    }
}
