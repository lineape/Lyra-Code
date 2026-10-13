package com.yukisoffd.lyracode

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual Compose/chat path, not a WebView placed directly in a native FrameLayout. */
class ReportedSvgRenderingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Before fun initializeStrings() { AppStrings.initialize(context) }

    private fun views(root: View): List<WebView> = buildList {
        if (root is WebView) add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
    }

    private fun players(): List<WebView> {
        var result = emptyList<WebView>()
        compose.runOnIdle {
            result = WindowInspector.getGlobalWindowViews().flatMap(::views).filter { it.url?.startsWith(SVG_PREVIEW_ORIGIN) == true }
        }
        return result
    }

    private fun awaitPlayers(count: Int) {
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnIdle {
                val active = WindowInspector.getGlobalWindowViews().flatMap(::views).filter { it.url?.startsWith(SVG_PREVIEW_ORIGIN) == true }
                ready = active.size == count && active.all { it.progress == 100 }
            }
            ready
        }
    }

    private tailrec fun activity(context: Context): Activity = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> error("No preview activity")
    }

    private fun window(view: View): Window {
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor is DialogWindowProvider) return ancestor.window
            ancestor = ancestor.parent as? View
        }
        return activity(view.context).window
    }

    private fun frame(view: WebView): Bitmap {
        val finished = CountDownLatch(1)
        val result = AtomicInteger(-1)
        var bitmap: Bitmap? = null
        compose.runOnIdle {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window(view),
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height), bitmap!!,
                { status -> result.set(status); finished.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Screen capture timed out", finished.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result.get())
        return bitmap!!
    }

    private fun assertFourPanelsRenderAndAnimate(view: WebView, prefix: String) {
        val frames = (0..3).map { index ->
            Thread.sleep(350)
            frame(view).also { bitmap -> File(context.cacheDir, "$prefix-$index.png").outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        try {
            val first = frames.first()
            val colorful = (0 until first.height).sumOf { y ->
                (0 until first.width).count { x ->
                    val pixel = first.getPixel(x, y)
                    maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 60
                }
            }
            assertTrue("$prefix is a blank SVG preview", colorful > 500)
            val scale = minOf(first.width / 600f, first.height / 360f)
            val offsetX = (first.width - 600 * scale) / 2
            val offsetY = (first.height - 360 * scale) / 2
            for ((index, rect) in listOf(Rect(16, 70, 292, 186), Rect(308, 70, 584, 186), Rect(16, 225, 292, 338), Rect(308, 225, 584, 338)).withIndex()) {
                val left = (offsetX + rect.left * scale).toInt().coerceAtLeast(0)
                val right = (offsetX + rect.right * scale).toInt().coerceAtMost(first.width)
                val top = (offsetY + rect.top * scale).toInt().coerceAtLeast(0)
                val bottom = (offsetY + rect.bottom * scale).toInt().coerceAtMost(first.height)
                val changes = frames.drop(1).maxOf { bitmap ->
                    (top until bottom).sumOf { y -> (left until right).count { x -> first.getPixel(x, y) != bitmap.getPixel(x, y) } }
                }
                assertTrue("$prefix panel ${index + 1} did not animate", changes > 20)
            }
        } finally { frames.forEach { it.recycle() } }
    }

    @Test fun reportedSvgRendersInChatAndFullscreenWithOneSaveButton() {
        val svg = instrumentation.context.assets.open("svg/reported-css-smil.svg").bufferedReader().use { it.readText() }
        val markdown = "Reported SVG\n\n![SVG](${svgDataUrl(svg)})\n\nAfter SVG"
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    SelectionContainer { StreamingAssistantContent(markdown, isStreaming = false, mode = "none") }
                }
            }
        }
        awaitPlayers(1)
        compose.onAllNodesWithText(context.getString(R.string.file_editor_save)).assertCountEquals(1)
        compose.onAllNodesWithText(context.getString(R.string.action_save_file)).assertCountEquals(0)
        val inline = players().single()
        assertFourPanelsRenderAndAnimate(inline, "svg-reported-inline")
        compose.onNodeWithContentDescription(context.getString(R.string.title_image_preview)).performTouchInput { click() }
        awaitPlayers(2)
        assertFourPanelsRenderAndAnimate(players().first { it !== inline }, "svg-reported-fullscreen")
        compose.onNodeWithContentDescription(context.getString(R.string.media_preview_close)).performClick()
        awaitPlayers(1)
        assertSame(inline, players().single())
    }
}
