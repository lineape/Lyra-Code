package com.yukisoffd.lyracode

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

internal const val SVG_PREVIEW_ORIGIN = "https://lyra-svg.invalid/"
private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
private val svgAnimation = Regex(
    """<(?:[\w.-]+:)?(?:animate(?:Color|Motion|Transform)?|set)\b|@(?:-[\w]+-)?keyframes\b|\banimation(?:-[\w-]+)?\s*:""",
    RegexOption.IGNORE_CASE,
)

internal fun isAnimatedSvg(bytes: ByteArray, mimeType: String? = null): Boolean {
    if (!mimeType.equals(SVG_MIME_TYPE, true) && !looksLikeSvg(bytes)) return false
    if (bytes.size > MAX_SVG_BYTES) return false
    val source = bytes.toString(Charsets.UTF_8).replace(Regex("""<!--[\s\S]*?-->"""), "")
    return svgAnimation.containsMatchIn(source)
}

/** The original bytes are saved unchanged; only the browser's image receives a missing namespace. */
internal data class AnimatedSvgDocument(val imageSource: String, val aspectRatio: Float) {
    companion object {
        fun create(bytes: ByteArray): AnimatedSvgDocument {
            require(bytes.size <= MAX_SVG_BYTES)
            val source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            require(!Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source))
            var rootSeen = false
            var rootNamespace = ""
            var width: Float? = null
            var height: Float? = null
            var viewBoxRatio: Float? = null
            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    if (rootSeen) return
                    require(localName == "svg" && (uri.isNullOrEmpty() || uri == SVG_NAMESPACE))
                    rootSeen = true
                    rootNamespace = uri.orEmpty()
                    width = svgLengthInPixels(attributes.getValue("width"))
                    height = svgLengthInPixels(attributes.getValue("height"))
                    val viewBox = attributes.getValue("viewBox")?.trim()?.split(Regex("""[\s,]+"""))
                        ?.map { it.toFloatOrNull() }
                    if (viewBox?.size == 4) {
                        val w = viewBox[2]
                        val h = viewBox[3]
                        if (w != null && h != null && w.isFinite() && h.isFinite() && w > 0 && h > 0) {
                            viewBoxRatio = (w / h).takeIf { it.isFinite() && it > 0 }
                        }
                    }
                }
            }
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(source)))
            require(rootSeen)
            val ratio = if (width != null && height != null) width!! / height!! else viewBoxRatio ?: 2f
            require(ratio.isFinite() && ratio > 0)
            val imageSource = if (rootNamespace.isEmpty()) addSvgNamespace(source) else source
            return AnimatedSvgDocument(imageSource, ratio.coerceIn(0.01f, 100f))
        }
    }
}

private fun svgLengthInPixels(value: String?): Float? {
    val match = Regex("""^\s*([+]?(?:\d*\.)?\d+(?:[eE][+-]?\d+)?)\s*(px|in|cm|mm|pt|pc)?\s*$""")
        .matchEntire(value.orEmpty()) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    val pixels = number * when (match.groupValues[2]) {
        "in" -> 96f
        "cm" -> 96f / 2.54f
        "mm" -> 96f / 25.4f
        "pt" -> 96f / 72f
        "pc" -> 16f
        else -> 1f
    }
    return pixels.takeIf { it.isFinite() && it > 0 }
}

private fun addSvgNamespace(source: String): String {
    val prefix = Regex("""^\s*(?:<\?xml\b[^>]*>\s*)?(?:<!--[\s\S]*?-->\s*)*<svg(?=[\s/>])""").find(source)
        ?: error("Missing SVG root")
    val rootStart = prefix.range.last - 3
    val root = Regex("""<svg(?:[^<>"']+|"[^"]*"|'[^']*')*>""").find(source, rootStart) ?: error("Missing SVG root")
    val namespace = Regex("""\sxmlns\s*=\s*(?:"[^"]*"|'[^']*')""").find(root.value)
    return if (namespace == null) {
        source.substring(0, prefix.range.last + 1) + " xmlns=\"$SVG_NAMESPACE\"" + source.substring(prefix.range.last + 1)
    } else {
        source.replaceRange(rootStart + namespace.range.first, rootStart + namespace.range.last + 1, " xmlns=\"$SVG_NAMESPACE\"")
    }
}

/** Model-authored SVG is an image resource, never HTML or an executable document. */
internal fun animatedSvgHtml(): String = """
    <!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'none';">
    <style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}img{display:block;width:100%;height:100%;object-fit:contain}</style>
    </head><body><img src="image.svg" alt=""></body></html>
""".trimIndent()
