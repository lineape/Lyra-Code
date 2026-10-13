package com.yukisoffd.lyracode

import org.junit.Assert.*
import org.junit.Test

class AnimatedSvgDocumentTest {
    @Test fun `detects SMIL and CSS animation in decoded SVG`() {
        for (animation in listOf("<animate attributeName='x'/>", "<animateTransform/>", "<animateMotion/>", "<animateColor/>", "<set/>",
            "<style>@keyframes spin { to { transform: rotate(360deg); } }</style>", "<rect style='animation: spin 1s infinite'/>",
            "<style>rect { animation-name: spin; }</style>")) {
            val svg = "<svg xmlns='http://www.w3.org/2000/svg'>$animation</svg>"
            assertTrue(animation, isAnimatedSvg(svg.toByteArray()))
            assertTrue(isAnimatedSvg(decodeDataUrl(svgDataUrl(svg))!!.bytes, SVG_MIME_TYPE))
        }
        assertFalse(isAnimatedSvg("<svg><!-- <animate/> @keyframes spin {} --><rect/></svg>".toByteArray()))
        assertFalse(isAnimatedSvg("<svg><setLighting/><rect/></svg>".toByteArray()))
        assertFalse(isAnimatedSvg("PNG animation: spin".toByteArray(), "image/png"))
    }

    @Test fun `adds missing SVG namespace to the root including empty namespace and XML prefix`() {
        for (source in listOf("<svg viewBox='0 0 160 90'><animate/></svg>", "<svg xmlns='' viewBox='0 0 160 90'><animate/></svg>",
            "<?xml version='1.0'?>\n<!-- <svg> example -->\n<svg viewBox='0 0 160 90'><animate/></svg>")) {
            val document = AnimatedSvgDocument.create(source.toByteArray())
            assertTrue(document.imageSource.contains("xmlns=\"http://www.w3.org/2000/svg\""))
            assertEquals(160f / 90f, document.aspectRatio, 0.001f)
            assertEquals(document.imageSource, AnimatedSvgDocument.create(document.imageSource.toByteArray()).imageSource)
        }
    }

    @Test fun `keeps valid namespaces source and CSS intact`() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 100'><style>@keyframes color { to { fill: blue; } } rect { animation: color 1s infinite; }</style><rect width='200' height='100'/></svg>"
        val document = AnimatedSvgDocument.create(svg.toByteArray())
        assertEquals(svg, document.imageSource)
        assertEquals(2f, document.aspectRatio, 0f)
        assertFalse(animatedSvgHtml().contains(svg))
        assertTrue(animatedSvgHtml().contains("<img src=\"image.svg\""))
        assertTrue(animatedSvgHtml().contains("script-src 'none'"))
    }

    @Test fun `uses intrinsic units viewBox fallback and bounded extreme proportions`() {
        assertEquals(2f, AnimatedSvgDocument.create("<svg width='2in' height='72pt'/>".toByteArray()).aspectRatio, 0.001f)
        assertEquals(4f, AnimatedSvgDocument.create("<svg width='100%' height='100%' viewBox='0,0,400,100'/>".toByteArray()).aspectRatio, 0.001f)
        assertEquals(2f, AnimatedSvgDocument.create("<svg/>".toByteArray()).aspectRatio, 0f)
        assertEquals(100f, AnimatedSvgDocument.create("<svg width='1000000' height='1'/>".toByteArray()).aspectRatio, 0f)
    }

    @Test fun `rejects malformed XML non SVG roots doctypes and oversized documents`() {
        for (source in listOf("<svg><rect></svg>", "<html><svg/></html>", "<svg xmlns='https://example.com'/>",
            "<!DOCTYPE svg [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><svg>&e;</svg>", "<svg>" + " ".repeat(MAX_SVG_BYTES) + "</svg>")) {
            assertTrue(source.take(100), runCatching { AnimatedSvgDocument.create(source.toByteArray()) }.isFailure)
        }
    }
}
