package com.yukisoffd.lyracode

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class ChatGptStringsTest {
    @Test fun allSupportedLocalesHaveMatchingChatGptMessagesAndFormatArguments() {
        fun strings(locale: String): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/chatgpt_strings.xml")).getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val item = nodes.item(index) as Element
                item.getAttribute("name") to item.textContent
            }
        }
        val defaults = strings("values")
        val format = Regex("%(?:\\d+\\$)?[sd]")
        for (locale in listOf("values-en", "values-zh-rCN", "values-zh-rTW")) {
            val values = strings(locale)
            assertEquals("ChatGPT translation keys: $locale", defaults.keys, values.keys)
            defaults.forEach { (key, value) ->
                assertEquals("ChatGPT format: $locale/$key", format.findAll(value).map { it.value }.toList(), format.findAll(values.getValue(key)).map { it.value }.toList())
            }
            assertTrue(String.format(Locale.ROOT, values.getValue("chatgpt_remaining_percent"), "9.9").endsWith("9.9%"))
        }
        val cjk = Regex("[\\u3400-\\u9fff]")
        assertTrue(defaults.values.none(cjk::containsMatchIn))
    }
}
