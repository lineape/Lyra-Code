package com.yukisoffd.lyracode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import kotlin.math.roundToInt

internal data class PreviewImagePayload(val bitmap: Bitmap? = null, val animatedSvg: DecodedDataUrl? = null)

internal fun loadPreviewImage(bytes: ByteArray, mimeType: String? = null): PreviewImagePayload =
    if (isAnimatedSvg(bytes, mimeType)) PreviewImagePayload(animatedSvg = DecodedDataUrl(SVG_MIME_TYPE, bytes))
    else PreviewImagePayload(bitmap = decodePreviewImage(bytes, mimeType))

/** SVGs are parsed as graphics, with no script execution or external resource resolver. */
internal fun decodePreviewImage(bytes: ByteArray, mimeType: String? = null): Bitmap? = runCatching {
    if (!mimeType.equals(SVG_MIME_TYPE, true) && !looksLikeSvg(bytes)) {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    require(bytes.size <= MAX_SVG_BYTES)
    val source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    require(!Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE).containsMatchIn(source))
    val svg = SVG.getFromString(source)
    val viewBox = svg.documentViewBox
    val ratio = svg.documentAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 2f
    val documentWidth = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
    val documentHeight = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
    val width = documentWidth
        ?: documentHeight?.let { it * ratio }
        ?: viewBox?.width()?.takeIf { it.isFinite() && it > 0f }
        ?: 300f
    val height = documentHeight ?: (width / ratio)
    // Keep a sharp preview while bounding bitmap allocations even for huge model-authored sizes.
    val scale = (2048f / maxOf(width, height)).coerceAtMost(4f)
    val bitmapWidth = (width * scale).roundToInt().coerceIn(1, 2048)
    val bitmapHeight = (height * scale).roundToInt().coerceIn(1, 2048)
    if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    try {
        svg.renderToCanvas(Canvas(bitmap))
        bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}.getOrNull()
