package com.yukisoffd.lyracode.interaction.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yukisoffd.lyracode.KimiCardBox
import com.yukisoffd.lyracode.KimiMuted
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions
import com.yukisoffd.lyracode.interaction.overlay.CompactChatBar
import com.yukisoffd.lyracode.interaction.overlay.FloatingPagePreferences
import kotlin.math.roundToInt

@Composable
internal fun FloatingPageSettings() {
    val context = LocalContext.current
    val options by remember(context) { FloatingPagePreferences.observe(context) }.collectAsState()
    fun update(value: CompactOverlayOptions) = FloatingPagePreferences.update(context, value)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = (maxHeight * .30f).coerceIn(100.dp, 190.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // This card stays visible while the controls below it scroll.
            KimiCardBox {
                Text(context.getString(R.string.floating_page_preview), style = MaterialTheme.typography.titleSmall)
                CompactOverlayPreview(options, previewHeight)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(context.getString(R.string.floating_page_auto_save), color = KimiMuted, style = MaterialTheme.typography.bodySmall)
                KimiCardBox {
                    OptionSlider(R.string.floating_page_width, "floating-width", options.widthDp, 240f..600f) { update(options.copy(widthDp = it)) }
                    OptionSlider(R.string.floating_page_height, "floating-height", options.heightDp, 64f..160f) { update(options.copy(heightDp = it)) }
                    OptionSlider(R.string.floating_page_transparency, "floating-transparency", options.transparency, 0f..100f, percent = true) { update(options.copy(transparency = it)) }
                    OptionSlider(R.string.floating_page_blur, "floating-blur", options.blurDp, 0f..40f) { update(options.copy(blurDp = it)) }
                }
                OutlinedButton(onClick = { update(CompactOverlayOptions()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(context.getString(R.string.floating_page_reset))
                }
            }
        }
    }
}

@Composable
private fun OptionSlider(labelId: Int, tag: String, value: Int, range: ClosedFloatingPointRange<Float>, percent: Boolean = false, onChange: (Int) -> Unit) {
    val label = LocalContext.current.getString(labelId)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(if (percent) "$value%" else "$value dp", color = KimiMuted, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value.toFloat(), { onChange(it.roundToInt()) }, valueRange = range,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label })
    }
}

/** A scene and its blurred copy share coordinates; only the bar's rounded region reveals blur. */
@Composable
private fun CompactOverlayPreview(options: CompactOverlayOptions, height: Dp) {
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat().coerceAtLeast(1f)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).testTag("floating-preview")) {
        val scale = minOf(maxWidth.value / screenWidth, (height.value - 12f) / 160f, 1f).coerceAtLeast(.1f)
        val widthDp = minOf(options.widthDp.toFloat(), screenWidth - 12f)
        val barWidth = (widthDp * scale).dp
        val barHeight = (options.heightDp * scale).dp
        val widthPx = with(density) { barWidth.toPx() }
        val heightPx = with(density) { barHeight.toPx() }
        val cornerPx = with(density) { (26 * scale).dp.toPx() }
        val barShape = remember(widthPx, heightPx, cornerPx) {
            GenericShape { size, _ ->
                val left = (size.width - widthPx) / 2f
                val top = (size.height - heightPx) / 2f
                addRoundRect(RoundRect(left, top, left + widthPx, top + heightPx, CornerRadius(cornerPx)))
            }
        }
        PreviewScene(Modifier.fillMaxSize())
        if (options.blurDp > 0) {
            PreviewScene(Modifier.fillMaxSize().clip(barShape).blur((options.blurDp * scale).dp))
        }
        Box(Modifier.align(Alignment.Center).size(barWidth, barHeight)) {
            AndroidView(
                factory = { CompactChatBar(it).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } },
                modifier = Modifier.requiredSize(widthDp.dp, options.heightDp.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                update = { bar ->
                    bar.render(options, context.getString(R.string.floating_page_preview_status), context.getString(R.string.floating_page_preview_body))
                },
            )
        }
    }
}

@Composable
private fun PreviewScene(modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier.background(Brush.linearGradient(listOf(Color(0xffd2e4ff), Color(0xff8f9ed4), Color(0xffd3bee3)))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(context.getString(R.string.floating_page_scene), color = Color(0xff273351), style = MaterialTheme.typography.titleMedium)
        repeat(3) { index ->
            Row(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .6f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(if (index % 2 == 0) Color(0xff6282cd) else Color(0xffa67bbb)))
                Box(Modifier.fillMaxWidth(if (index % 2 == 0) .7f else .45f).height(5.dp).background(Color(0xff8e9ab8), RoundedCornerShape(3.dp)))
            }
        }
    }
}
