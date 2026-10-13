package com.yukisoffd.lyracode

import android.graphics.Bitmap
import android.graphics.Color
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.io.ByteArrayOutputStream
import java.io.File

class SvgRenderingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='160' height='90'><rect width='160' height='90' rx='12' fill='#2563eb'/><circle cx='52' cy='45' r='22' fill='#facc15'/><rect x='92' y='30' width='48' height='30' rx='6' fill='#ffffff'/></svg>"
    private val encoded = "data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%20width='160'%20height='90'%3E%3Crect%20width='160'%20height='90'%20rx='12'%20fill='%232563eb'/%3E%3Ccircle%20cx='52'%20cy='45'%20r='22'%20fill='%23facc15'/%3E%3Crect%20x='92'%20y='30'%20width='48'%20height='30'%20rx='6'%20fill='%23ffffff'/%3E%3C/svg%3E"

    @Before fun initializeStrings() { AppStrings.initialize(context) }

    @Test fun svgAndDataUrlsRenderExpectedPixelsAndKeepSvgFileType() {
        for (decoded in listOf(decodeMediaPayload(svg)!!, decodeDataUrl(encoded)!!, decodeDataUrl(svgDataUrl(svg))!!)) {
            val bitmap = decodePreviewImage(decoded.bytes, decoded.mimeType)!!
            try {
                val scale = bitmap.width / 160f
                assertEquals(160f / 90f, bitmap.width.toFloat() / bitmap.height, 0.001f)
                assertEquals(Color.rgb(37, 99, 235), bitmap.getPixel((80 * scale).toInt(), (10 * scale).toInt()))
                assertEquals(Color.rgb(250, 204, 21), bitmap.getPixel((52 * scale).toInt(), (45 * scale).toInt()))
                assertEquals(Color.WHITE, bitmap.getPixel((116 * scale).toInt(), (45 * scale).toInt()))
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                assertEquals("svg", mediaExtensionFromSource("", decoded.mimeType))
                assertEquals("image", mediaKindForSource("", decoded.mimeType))
            } finally { bitmap.recycle() }
        }
        assertEquals(SVG_MIME_TYPE, mimeTypeFromSource("https://example.com/image.svg?download=1"))
        assertTrue(isMediaSource("https://example.com/image.svg"))
    }

    @Test fun viewBoxOnlyHugeAndMalformedSvgAreHandledAndPngStillWorks() {
        val viewBoxSvg = "<svg viewBox='0 0 160 90'><rect width='160' height='90' fill='#2563eb'/></svg>"
        val viewBoxBitmap = decodePreviewImage(viewBoxSvg.toByteArray())!!
        assertEquals(160f / 90f, viewBoxBitmap.width.toFloat() / viewBoxBitmap.height, 0.001f)
        assertEquals(Color.rgb(37, 99, 235), viewBoxBitmap.getPixel(viewBoxBitmap.width / 2, viewBoxBitmap.height / 2))
        viewBoxBitmap.recycle()
        val widthOnly = decodePreviewImage(viewBoxSvg.replace("<svg ", "<svg width='80' ").toByteArray())!!
        assertEquals(160f / 90f, widthOnly.width.toFloat() / widthOnly.height, 0.001f)
        widthOnly.recycle()
        val huge = "<svg width='1000000000' height='500000000'><rect width='1000000000' height='500000000' fill='red'/></svg>"
        val bounded = decodePreviewImage(huge.toByteArray())!!
        assertEquals(2048, bounded.width)
        assertEquals(1024, bounded.height)
        bounded.recycle()
        assertNull(decodePreviewImage("<svg><path".toByteArray(), SVG_MIME_TYPE))
        assertNull(decodePreviewImage("<!DOCTYPE svg [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><svg>&ext;</svg>".toByteArray(), SVG_MIME_TYPE))
        val png = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val output = ByteArrayOutputStream()
        png.compress(Bitmap.CompressFormat.PNG, 100, output)
        png.recycle()
        val decodedPng = decodePreviewImage(output.toByteArray(), "image/png")!!
        assertEquals(Color.GREEN, decodedPng.getPixel(1, 1))
        decodedPng.recycle()
    }

    @Test fun savingSvgKeepsOriginalXmlAndMimeType() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val decoded = decodeDataUrl(encoded)!!
        val status = saveDecodedDataUrl(context, decoded)!!
        val filename = status.substringAfterLast('/')
        assertTrue(filename.endsWith(".svg"))
        val resolver = context.contentResolver
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(filename), null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            try {
                assertEquals(SVG_MIME_TYPE, cursor.getString(1))
                assertArrayEquals(decoded.bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { resolver.delete(uri, null, null) }
        }
    }

    @Test fun markdownSvgFormsShowImagePreserveProseAndOpenFullscreen() {
        var markdown by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    RichMarkdownContent(markdown)
                }
            }
        }
        val preview = context.getString(R.string.title_image_preview)
        for ((index, source) in listOf(svg, "```svg\n$svg\n```", "![data-URI SVG 测试]($encoded)", "![Base64 SVG](${svgDataUrl(svg)})", "<img src=\"$encoded\">", encoded).withIndex()) {
            compose.runOnIdle { markdown = "Before image $index\n\n$source\n\nAfter image $index" }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("After image $index").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(preview).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Before image $index").assertIsDisplayed()
            compose.onNodeWithText("After image $index").assertIsDisplayed()
            compose.onNodeWithContentDescription(preview).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.media_preview_close)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(preview).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.media_preview_close)).performClick()
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(context.cacheDir, "svg-markdown-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun animatedSvgFormsOpenAnimatedFullscreenAndKeepThePlayerDuringStreaming() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val animated = "<svg viewBox='0 0 200 100'><rect width='200' height='100' fill='red'><animate attributeName='fill' values='red;blue;red' dur='1.2s' repeatCount='indefinite'/></rect></svg>"
        val url = "data:image/svg+xml," + java.net.URLEncoder.encode(animated, "UTF-8").replace("+", "%20")
        var markdown by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    RichMarkdownContent(markdown)
                }
            }
        }
        fun views(root: View): List<WebView> = buildList {
            if (root is WebView) add(root)
            if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
        }
        fun players(): List<WebView> {
            var result = emptyList<WebView>()
            compose.runOnIdle { result = WindowInspector.getGlobalWindowViews().flatMap(::views).filter { it.url?.startsWith(SVG_PREVIEW_ORIGIN) == true } }
            return result
        }
        fun awaitPlayers(count: Int) {
            compose.waitUntil(15_000) {
                var ready = false
                compose.runOnIdle {
                    val active = WindowInspector.getGlobalWindowViews().flatMap(::views).filter { it.url?.startsWith(SVG_PREVIEW_ORIGIN) == true }
                    ready = active.size == count && active.all { it.progress == 100 }
                }
                ready
            }
        }
        val preview = context.getString(R.string.title_image_preview)
        for ((index, source) in listOf(animated, "```svg\n$animated\n```", "![Animated SVG]($url)", "![Base64 SVG](${svgDataUrl(animated)})", "<img src=\"$url\">", url).withIndex()) {
            compose.runOnIdle { markdown = "Before animation $index\n\n$source\n\nAfter animation $index" }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("After animation $index").fetchSemanticsNodes().isNotEmpty() }
            awaitPlayers(1)
            try {
                compose.onNodeWithText("Before animation $index").assertIsDisplayed()
            } catch (error: AssertionError) {
                compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                    File(context.cacheDir, "svg-animation-ui-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                throw AssertionError("SVG form $index scrolled the preceding text out of view", error)
            }
            compose.onNodeWithText("After animation $index").assertIsDisplayed()
            val initial = players().single()
            compose.runOnIdle { markdown += " continues streaming" }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("After animation $index continues streaming").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("After animation $index continues streaming").assertIsDisplayed()
            assertSame("Streaming restarted the animation player", initial, players().single())
            compose.onNodeWithContentDescription(preview).performTouchInput { click() }
            awaitPlayers(2)
            compose.onNodeWithText(preview).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.media_preview_close)).performClick()
            awaitPlayers(1)
            assertSame(initial, players().single())
        }
    }
}
