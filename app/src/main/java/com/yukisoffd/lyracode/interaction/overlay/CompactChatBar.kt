package com.yukisoffd.lyracode.interaction.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.yukisoffd.lyracode.R
import com.yukisoffd.lyracode.interaction.model.CompactOverlayOptions

/** Shared native content and tint for the real overlay and the live settings preview. */
internal class CompactChatBar(
    context: Context,
    onDrag: View.OnTouchListener = View.OnTouchListener { _, _ -> false },
    onRestore: () -> Unit = {},
    onStop: () -> Unit = {},
) : LinearLayout(context) {
    private val palette = OverlayPalette(context)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val status = label(12, palette.accent).apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(3))
    }
    private val preview = label(13, palette.text)
    private var options: CompactOverlayOptions? = null

    init {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(control(R.drawable.ic_floating_restore, R.string.floating_restore, onRestore), LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            setOnTouchListener(onDrag)
            addView(CompactActivityGlow(context, palette.accent), LayoutParams(dp(18), dp(40)).apply { marginEnd = dp(8) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(status)
                addView(preview)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(control(R.drawable.ic_floating_stop, R.string.floating_stop_output, onStop), LayoutParams(dp(44), dp(44)))
        setOnTouchListener(onDrag)
    }

    fun render(value: CompactOverlayOptions, statusText: String, previewText: String) {
        if (options != value) {
            options = value
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                ColorUtils.setAlphaComponent(palette.surface, value.backgroundAlpha),
                ColorUtils.setAlphaComponent(palette.container, value.backgroundAlpha),
            )).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), ColorUtils.setAlphaComponent(palette.accent, 55))
            }
        }
        status.text = statusText
        preview.text = previewText
    }

    private fun label(size: Int, color: Int) = TextView(context).apply {
        textSize = size.toFloat(); setTextColor(color)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun control(icon: Int, description: Int, action: () -> Unit) = Button(context).apply {
        contentDescription = context.getString(description)
        minWidth = 0; minimumWidth = 0; minimumHeight = dp(44)
        setCompoundDrawables(context.getDrawable(icon)!!.mutate().apply {
            setTint(palette.text); setBounds(0, 0, dp(24), dp(24))
        }, null, null, null)
        background = RippleDrawable(ColorStateList.valueOf(0x22777777), null, null)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { action() }
    }
}
