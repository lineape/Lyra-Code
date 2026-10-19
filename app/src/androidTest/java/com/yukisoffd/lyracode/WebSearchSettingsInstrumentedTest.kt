package com.yukisoffd.lyracode

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.data.AppSettings
import com.yukisoffd.lyracode.data.DeepSeekWebConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.LocalDate

class WebSearchSettingsInstrumentedTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedPreferences = mutableMapOf<String, Map<String, *>>()
    private lateinit var oldSearch: DeepSeekWebConfig
    private lateinit var oldFetch: DeepSeekWebConfig

    private val setup = object : TestWatcher() {
        override fun starting(description: Description) {
            for (name in listOf("lyra_settings", "lyra_update_state", "first_use_consent", "android_compatibility")) {
                savedPreferences[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            }
            FirstUseConsentStore(context).accept()
            context.getSharedPreferences("android_compatibility", Context.MODE_PRIVATE).edit()
                .putBoolean("android_17_local_network_rationale_seen", true).commit()
            context.getSharedPreferences("lyra_update_state", Context.MODE_PRIVATE).edit()
                .putString("last_daily_check_date", LocalDate.now().toString()).commit()
            AppSettings(context).apply {
                oldSearch = deepSeekSearchConfig
                oldFetch = deepSeekFetchConfig
                deepSeekSearchConfig = DeepSeekWebConfig()
                deepSeekFetchConfig = DeepSeekWebConfig()
                languageMode = AppSettings.LANGUAGE_ZH_CN
                fontScaleMode = AppSettings.FONT_SCALE_NORMAL
                predictiveBackEnabled = false
            }
        }

        override fun finished(description: Description) {
            AppSettings(context).apply {
                deepSeekSearchConfig = oldSearch
                deepSeekFetchConfig = oldFetch
            }
            for ((name, values) in savedPreferences) {
                val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for ((key, value) in values) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.commit()
            }
        }
    }

    @get:Rule val rule: RuleChain = RuleChain.outerRule(setup).around(compose)

    private fun text(id: Int) = compose.activity.getString(id)
    private fun back() = compose.onNodeWithContentDescription(text(R.string.cd_back)).performClick()

    private fun openWebSearch() {
        val rationale = text(R.string.local_network_permission_title)
        if (compose.onAllNodesWithText(rationale).fetchSemanticsNodes().isNotEmpty()) {
            val dialog = isDialog() and hasAnyDescendant(hasText(rationale))
            compose.onNode(hasText(text(R.string.action_later)) and hasAnyAncestor(dialog)).performClick()
        }
        compose.onNodeWithContentDescription(text(R.string.cd_menu)).performClick()
        compose.onNodeWithText(text(R.string.title_settings)).performClick()
        compose.onNodeWithText(text(R.string.menu_web_search)).performScrollTo().performClick()
    }

    @Test fun configAndBlacklistUseIndependentSubpagesAndReturnToWebSearch() {
        openWebSearch()
        compose.onNodeWithText(text(R.string.deepseek_search_title)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.deepseek_enable_search)).assertIsDisplayed()
        compose.onAllNodesWithText("接口文档").assertCountEquals(0)
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("API Key").performTextInput("ui-search-test-key")
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.file_editor_save)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.deepseek_config_saved)).performScrollTo().assertIsDisplayed()
        assertEquals("ui-search-test-key", AppSettings(context).deepSeekSearchConfig.apiKey)
        assertFalse(AppSettings(context).deepSeekSearchConfig.enabled)
        compose.onNode(isToggleable()).performScrollTo().assertIsEnabled().assertIsOff().performClick()
        assertTrue(AppSettings(context).deepSeekSearchConfig.enabled)
        assertFalse(AppSettings(context).deepSeekFetchConfig.enabled)
        back()
        compose.onNodeWithText(text(R.string.deepseek_backend_enabled)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.deepseek_fetch_title)).performClick()
        compose.onNodeWithText(text(R.string.deepseek_enable_fetch)).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        back()
        compose.onNodeWithText(text(R.string.title_domain_blacklist)).performClick()
        compose.onNodeWithText(text(R.string.label_one_domain_per_line)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.deepseek_enable_search)).assertCountEquals(0)
        back()
        compose.onNodeWithText(text(R.string.deepseek_search_title)).assertIsDisplayed().performClick()
        compose.onNode(isToggleable()).assertIsOn()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.deepseek_enable_search)).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn()
        back()
        compose.onNodeWithText(text(R.string.title_domain_blacklist)).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.menu_model_service)).performScrollTo().assertIsDisplayed()
    }

    @Test fun savingInputsAndTogglingAreIndependentForBothBackends() {
        openWebSearch()
        for (search in listOf(true, false)) {
            fun current() = AppSettings(context).let { if (search) it.deepSeekSearchConfig else it.deepSeekFetchConfig }
            compose.onNodeWithText(text(if (search) R.string.deepseek_search_title else R.string.deepseek_fetch_title)).performClick()
            compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
            compose.onNodeWithText("API Key").performTextInput("saved-test-key")
            compose.onNodeWithText(text(R.string.file_editor_save)).performScrollTo().performClick()
            compose.onNode(isToggleable()).performScrollTo().assertIsOff().assertIsEnabled().performClick()
            assertTrue(current().enabled)
            compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("draft-test-key")
            compose.onNodeWithText(text(R.string.label_base_url)).performScrollTo().performTextReplacement("https://proxy.example/v1")
            compose.onNode(isToggleable()).performScrollTo().performClick()
            assertFalse(current().enabled)
            assertEquals("saved-test-key", current().apiKey)
            assertEquals(DeepSeekWebConfig.DEFAULT_BASE_URL, current().baseUrl)
            compose.onNodeWithText(text(R.string.label_base_url)).assertTextContains("https://proxy.example/v1")
            compose.onNodeWithText(text(R.string.file_editor_save)).performScrollTo().performClick()
            assertFalse(current().enabled)
            assertEquals("draft-test-key", current().apiKey)
            assertEquals("https://proxy.example/v1", current().baseUrl)
            compose.onNode(isToggleable()).performScrollTo().performClick()
            assertTrue(current().enabled)
            compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("replacement-test-key")
            compose.onNodeWithText(text(R.string.file_editor_save)).performScrollTo().performClick()
            assertTrue(current().enabled)
            assertEquals("replacement-test-key", current().apiKey)
            compose.onNodeWithText("API Key").performScrollTo().performTextClearance()
            compose.onNodeWithText(text(R.string.file_editor_save)).performScrollTo().performClick()
            assertEquals("", current().apiKey)
            assertFalse(current().enabled)
            compose.onNode(isToggleable()).performScrollTo().assertIsOff().assertIsNotEnabled()
            back()
        }
    }
}
