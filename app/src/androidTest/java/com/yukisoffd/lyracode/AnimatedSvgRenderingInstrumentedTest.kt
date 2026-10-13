package com.yukisoffd.lyracode

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AnimatedSvgRenderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withPreview(source: String, action: (MainActivity, WebView) -> Unit) {
        FirstUseConsentStore(instrumentation.targetContext).accept()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ready = CountDownLatch(1)
        val failed = AtomicInteger()
        var view: WebView? = null
        try {
            instrumentation.runOnMainSync {
                view = createAnimatedSvgWebView(activity, AnimatedSvgDocument.create(source.toByteArray()),
                    onReady = { ready.countDown() }, onError = { failed.incrementAndGet(); ready.countDown() })
                activity.setContentView(FrameLayout(activity).apply {
                    setBackgroundColor(Color.WHITE)
                    setPadding(64, 180, 64, 64)
                    addView(view, FrameLayout.LayoutParams(640, 320))
                })
            }
            assertTrue("SVG preview timed out", ready.await(20, TimeUnit.SECONDS))
            assertEquals("SVG browser reported an error", 0, failed.get())
            instrumentation.waitForIdleSync()
            action(activity, view!!)
        } finally {
            instrumentation.runOnMainSync {
                view?.let { (it.parent as? ViewGroup)?.removeView(it); releaseAnimatedSvgWebView(it) }
                activity.finish()
            }
        }
    }

    private fun frame(activity: MainActivity, view: WebView): Bitmap {
        val finished = CountDownLatch(1)
        val result = AtomicInteger(-1)
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            PixelCopy.request(activity.window, Rect(location[0], location[1], location[0] + 640, location[1] + 320), bitmap,
                { status -> result.set(status); finished.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Frame capture timed out", finished.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result.get())
        return bitmap
    }

    @Test fun smilAndCssAnimationsActuallyChangeRenderedFrames() {
        val samples = listOf(
            "animate" to "<rect width='200' height='100' fill='red'><animate attributeName='fill' values='red;blue;red' dur='1.2s' repeatCount='indefinite'/></rect>",
            "animateTransform" to "<rect y='25' width='40' height='50' fill='red'><animateTransform attributeName='transform' type='translate' values='0 0;160 0;0 0' dur='1.2s' repeatCount='indefinite'/></rect>",
            "animateMotion" to "<circle cx='20' cy='50' r='15' fill='red'><animateMotion path='M0 0 L160 0 Z' dur='1.2s' repeatCount='indefinite'/></circle>",
            "set" to "<rect width='200' height='100' fill='red'><set id='a' attributeName='fill' to='blue' begin='0s;b.end' dur='0.3s'/><set id='b' attributeName='fill' to='red' begin='a.end' dur='0.3s'/></rect>",
            "css" to "<style>@keyframes pulse {0%,100% {fill:red} 50% {fill:blue}} rect {animation:pulse 1.2s linear infinite}</style><rect width='200' height='100'/>",
        )
        for ((name, content) in samples) {
            // Missing xmlns is common in AI-authored inline SVG and must still animate as an image.
            val source = "<svg viewBox='0 0 200 100'>$content</svg>"
            assertTrue(isAnimatedSvg(source.toByteArray()))
            assertNotNull(loadPreviewImage(source.toByteArray()).animatedSvg)
            withPreview(source) { activity, view ->
                val frames = (0..3).map {
                    Thread.sleep(220)
                    frame(activity, view)
                }
                try {
                    val first = IntArray(640 * 320)
                    frames.first().getPixels(first, 0, 640, 0, 0, 640, 320)
                    val changingPixels = frames.drop(1).maxOf { bitmap ->
                        val pixels = IntArray(first.size)
                        bitmap.getPixels(pixels, 0, 640, 0, 0, 640, 320)
                        first.indices.count { first[it] != pixels[it] }
                    }
                    assertTrue("$name stayed on a static frame", changingPixels > 1000)
                    for (index in listOf(0, 2)) File(instrumentation.targetContext.cacheDir, "svg-animation-$name-$index.png")
                        .outputStream().use { frames[index].compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { frames.forEach { it.recycle() } }
            }
        }
    }

    @Test fun SVGScriptAndEventHandlersCannotModifyTheAnimatedImage() {
        val source = """
            <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 100' onload="document.getElementById('shape').setAttribute('fill','red')">
              <script>window.addEventListener('load',function(){document.getElementById('shape').setAttribute('fill','red')});</script>
              <rect id='shape' width='200' height='100' fill='blue'><animate attributeName='opacity' values='1;1' dur='1s' repeatCount='indefinite'/></rect>
            </svg>
        """.trimIndent()
        withPreview(source) { activity, view ->
            instrumentation.runOnMainSync {
                assertFalse(view.settings.javaScriptEnabled)
                assertFalse(view.settings.allowFileAccess)
                assertFalse(view.settings.allowContentAccess)
                assertFalse(view.settings.domStorageEnabled)
            }
            Thread.sleep(500)
            val bitmap = frame(activity, view)
            try { assertEquals("SVG executed model-authored code", Color.BLUE, bitmap.getPixel(320, 160)) }
            finally { bitmap.recycle() }
        }
    }
}
