package com.yukisoffd.lyracode

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.charts.CHART_ASSET_ORIGIN
import com.yukisoffd.lyracode.charts.ChartDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CircuitWebCompanionInstrumentedTest {
    @Test fun webMarkdownRendersBothCircuitFencesAndPreservesErrorsAndPreviewControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        FirstUseConsentStore(instrumentation.targetContext).accept()
        val source = instrumentation.context.assets.open("charts/ne555-cd4017.json").bufferedReader().use { it.readText() }
        val untrusted = "<script>window.circuitPwned=true</script>"
        val data = JSONObject(source).apply { getJSONArray("components").getJSONObject(0).put("label", untrusted) }
        val largeComponents = org.json.JSONArray()
        repeat(200) { index -> largeComponents.put(JSONObject().put("id", "R$index").put("type", "resistor")
            .put("x", 100 + index % 10 * 60).put("y", 100 + index / 10 * 20).put("label", "\"".repeat(100)).put("value", "\"".repeat(100))) }
        val largeChart = ChartDocument.create("circuit", JSONObject().put("components", largeComponents).toString()).markdown()
        assertTrue("Large escaped envelope must exercise the source boundary", largeChart.length > 100_000)
        val markdown = "```circuit\n$data\n```\n\n" + ChartDocument.create("circuit", source, "中文电路图").markdown() +
            "\n\n" + largeChart +
            "\n\n```circuit\n" + JSONObject(source).apply { getJSONArray("wires").getJSONObject(0).put("to", "IC1.missing") } + "\n```"
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val complete = CountDownLatch(1)
        val result = AtomicReference<String>()
        var view: WebView? = null
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none';script-src 'self';style-src 'self' 'unsafe-inline';">
            <link rel="stylesheet" href="style.css"></head><body><div id="root"></div>
            <dialog id="visual-dialog"><button id="close-visual">Close</button><div id="visual-title"></div><div id="visual-content"></div></dialog>
            <script src="marked.js"></script><script src="purify.js"></script><script src="charts/circuit-symbols.js"></script><script src="charts/circuit.js"></script><script src="rich-content.js"></script>
            </body></html>""".trimIndent()
        val scripts = mapOf("marked.js" to "web-access/marked.js", "purify.js" to "web-access/purify.js",
            "rich-content.js" to "web-access/rich-content.js", "charts/circuit.js" to "charts/circuit.js", "charts/circuit-symbols.js" to "charts/circuit-symbols.js", "style.css" to "web-access/style.css")
        try {
            instrumentation.runOnMainSync {
                view = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    addJavascriptInterface(object {
                        @JavascriptInterface fun complete(value: String) { result.set(value); complete.countDown() }
                    }, "CircuitTest")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val name = request.url.toString().removePrefix(CHART_ASSET_ORIGIN)
                            if (name == "web-circuit.html") return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                            val asset = scripts[name]
                            return if (asset != null) WebResourceResponse(if (name.endsWith("css")) "text/css" else "application/javascript", "UTF-8", activity.assets.open(asset))
                            else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript("""
                                (()=>{try {
                                  window.t=value=>value;window.esc=value=>value;
                                  const rich=createRichContent(),root=document.getElementById('root');
                                  root.innerHTML=rich.markdown(${JSONObject.quote(markdown)});rich.hydrate(root);
                                  const svgs=[...root.querySelectorAll('.rich-chart-host>svg')];
                                  const labels=svgs.map(svg=>svg.textContent);
                                  const error=root.querySelector('.rich-error')?.textContent;
                                  const code=root.querySelector('.rich-chart>code');
                                  root.querySelector('[data-chart-source]').click();const sourceVisible=!code.hidden;
                                  root.querySelector('[data-visual]').click();const preview=document.getElementById('visual-dialog').open;
                                  document.getElementById('visual-dialog').addEventListener('close',()=>{
                                    CircuitTest.complete(JSON.stringify({count:svgs.length,labels,error,sourceVisible,preview,
                                      restored:root.querySelectorAll('.rich-chart-host>svg').length===3,
                                      safe:!window.circuitPwned&&svgs.every(svg=>!svg.querySelector('script,foreignObject,image,a')),
                                      bounds:svgs.every(svg=>svg.viewBox.baseVal.width>0&&svg.viewBox.baseVal.height>0)}));
                                  },{once:true});
                                  document.getElementById('close-visual').click();
                                }catch(error){CircuitTest.complete(JSON.stringify({failure:String(error.stack||error)}));}})()
                            """.trimIndent(), null)
                        }
                    }
                }
                activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
                view!!.loadUrl(CHART_ASSET_ORIGIN + "web-circuit.html")
            }
            assertTrue("Web companion rendering timed out", complete.await(30, TimeUnit.SECONDS))
            val output = JSONObject(result.get())
            assertFalse(output.toString(), output.has("failure"))
            assertEquals(3, output.getInt("count"))
            assertTrue(output.getJSONArray("labels").getString(0).contains(untrusted))
            assertTrue(output.getJSONArray("labels").getString(1).contains("CD4017"))
            assertTrue(output.getString("error").contains("IC1.missing"))
            for (key in listOf("sourceVisible", "preview", "restored", "safe", "bounds")) assertTrue("$key: $output", output.getBoolean(key))
        } finally {
            instrumentation.runOnMainSync { view?.destroy(); activity.finish() }
        }
    }
}
