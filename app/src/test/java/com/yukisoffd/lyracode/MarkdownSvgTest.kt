package com.yukisoffd.lyracode

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownSvgTest {
    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"160\" height=\"90\"><rect width=\"160\" height=\"90\" fill=\"#2563eb\"/></svg>"
    private fun images(markdown: String): List<ASTNode> {
        fun collect(node: ASTNode): List<ASTNode> =
            if (node.type == MarkdownElementTypes.IMAGE) listOf(node) else node.children.flatMap(::collect)
        return collect(MarkdownParser(GFMFlavourDescriptor(useSafeLinks = true)).buildMarkdownTreeFromString(markdown))
    }

    @Test fun `raw multiline SVG becomes a real Markdown image between surrounding prose`() {
        val source = svg.replace("><", ">\n<")
        val normalized = normalizeMarkdownSvg("Before **image**\n$source\nAfter image")
        assertEquals("Before **image**\n![SVG](${svgDataUrl(source)})\nAfter image", normalized)
        assertEquals(1, images(normalized).size)
        assertEquals(source, decodeDataUrl(svgDataUrl(source))!!.bytes.toString(Charsets.UTF_8))
    }

    @Test fun `supports adjacent nested and self closing SVG with quoted greater than`() {
        val nested = "<svg viewBox='0 0 10 10' aria-label='a > b'><svg><rect/></svg><!-- </svg> --><![CDATA[</svg>]]></svg>"
        val normalized = normalizeMarkdownSvg("$nested\n<svg/>\n$svg")
        assertEquals(3, images(normalized).size)
        assertTrue(normalized.startsWith("![SVG](${svgDataUrl(nested)})"))
    }

    @Test fun `includes xml declaration in image payload`() {
        val source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$svg"
        val normalized = normalizeMarkdownSvg(source)
        assertEquals("![SVG](${svgDataUrl(source)})", normalized)
        assertEquals(1, images(normalized).size)
    }

    @Test fun `leaves fenced indented inline code and Markdown links intact`() {
        for (source in listOf("```xml\n$svg\n```", "~~~svg\n$svg\n~~~", "```svg\n$svg", "`$svg`", "    $svg", "![sample](${svgDataUrl(svg)})", "[source](${svgDataUrl(svg)})", "<!-- $svg -->")) {
            assertEquals(source, source, normalizeMarkdownSvg(source))
        }
    }

    @Test fun `leaves incomplete streamed SVG untouched until closed`() {
        val source = "Before\n<svg viewBox='0 0 10 10'><path d='M0 0 L10 10'/>"
        assertEquals(source, normalizeMarkdownSvg(source))
        assertEquals(1, images(normalizeMarkdownSvg("$source</svg>\nAfter")).size)
    }

    @Test fun `supports SVG data urls in html images and bare text`() {
        val dataUrl = "data:image/svg+xml;charset=utf-8,%3Csvg%20viewBox='0%200%2010%2010'%3E%3Cpath%20d='M0%200L10%2010'/%3E%3C/svg%3E"
        assertEquals(1, images(normalizeMarkdownSvg("<img src=\"$dataUrl\" alt=\"example\">\nAfter")).size)
        assertEquals(1, images(normalizeMarkdownSvg("Before $dataUrl After")).size)
        assertTrue(normalizeMarkdownSvg("$dataUrl。").endsWith(")。"))
    }
}
