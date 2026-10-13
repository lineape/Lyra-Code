package com.yukisoffd.lyracode

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.util.Base64

internal const val SVG_MIME_TYPE = "image/svg+xml"
internal const val MAX_SVG_BYTES = 2 * 1024 * 1024

private val svgStart = Regex("""^\s*(?:<\?xml\b[^>]*>\s*)?(?:<!--[\s\S]*?-->\s*)*<svg(?=[\s/>])""", RegexOption.IGNORE_CASE)
private val svgTokens = Regex(
    """<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?]]>|<(/?)svg(?=[\s/>])(?:[^<>"']|"[^"]*"|'[^']*')*>|<img\b(?:[^<>"']|"[^"]*"|'[^']*')*>|data:image/svg\+xml(?:;[^,\s<>]*)?,[^\s<>]+""",
    RegexOption.IGNORE_CASE,
)
private val imageSource = Regex("""(?:^|\s)src\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
private val xmlPrefix = Regex("""<\?xml\b[^>]*>\s*$""", RegexOption.IGNORE_CASE)

internal fun looksLikeSvg(source: String): Boolean = svgStart.containsMatchIn(source.removePrefix("\uFEFF"))

internal fun looksLikeSvg(bytes: ByteArray): Boolean =
    looksLikeSvg(bytes.copyOfRange(0, minOf(bytes.size, 4096)).toString(Charsets.UTF_8))

internal fun svgDataUrl(source: String): String =
    "data:$SVG_MIME_TYPE;base64," + Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))

/** Turn complete, unfenced SVGs into image nodes before HTML gets split into text tokens. */
internal fun normalizeMarkdownSvg(content: String): String {
    if (!content.contains("<svg", true) && !content.contains("data:image/svg+xml", true)) return content
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val protectedRanges = mutableListOf<IntRange>()
    fun collect(node: ASTNode) {
        if (node.type in setOf(
                MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
                MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.IMAGE,
                MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.AUTOLINK,
            )) {
            protectedRanges += node.startOffset until node.endOffset
        } else node.children.forEach(::collect)
    }
    collect(tree)

    val result = StringBuilder(content.length)
    var cursor = 0
    var svgOffset = -1
    var depth = 0
    for (token in svgTokens.findAll(content)) {
        if (protectedRanges.any { token.range.first in it }) continue
        val value = token.value
        if (value.startsWith("<!--") || value.startsWith("<![CDATA[")) continue
        if (value.startsWith("<svg", true) || value.startsWith("</svg", true)) {
            if (value.startsWith("</")) {
                if (depth == 0) continue
                depth--
            } else {
                if (depth == 0) {
                    svgOffset = token.range.first
                    xmlPrefix.find(content.substring(cursor, svgOffset))?.let { svgOffset = cursor + it.range.first }
                }
                if (!value.endsWith("/>")) depth++
            }
            if (depth == 0 && svgOffset >= cursor) {
                val end = token.range.last + 1
                val svg = content.substring(svgOffset, end)
                if (svg.toByteArray(Charsets.UTF_8).size <= MAX_SVG_BYTES) {
                    result.append(content, cursor, svgOffset)
                    result.append("![SVG](").append(svgDataUrl(svg)).append(')')
                    cursor = end
                }
                svgOffset = -1
            }
        } else if (depth == 0) {
            val url = if (value.startsWith("<img", true)) {
                imageSource.find(value)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                    ?.replace("&amp;", "&")
            } else value.trimEnd(')', ']', '.', '。', '，', ',')
            if (url?.startsWith("data:$SVG_MIME_TYPE", true) != true) continue
            val end = if (value.startsWith("<img", true)) token.range.last + 1 else token.range.first + url.length
            result.append(content, cursor, token.range.first)
            // Base64 avoids Markdown interpreting parentheses or quotes in the URL payload.
            val decoded = decodeDataUrl(url)
            val destination = if (decoded != null) {
                "data:$SVG_MIME_TYPE;base64," + Base64.getEncoder().encodeToString(decoded.bytes)
            } else url
            result.append("![SVG](").append(destination).append(')')
            cursor = end
        }
    }
    if (cursor == 0) return content
    return result.append(content, cursor, content.length).toString()
}
