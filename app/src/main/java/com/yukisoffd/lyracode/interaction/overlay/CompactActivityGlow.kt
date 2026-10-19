package com.yukisoffd.lyracode.interaction.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils

/** A small breathing light; animation stops as soon as the compact panel is hidden. */
internal class CompactActivityGlow(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && ValueAnimator.areAnimatorsEnabled()) pulse.start() else pulse.cancel()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height / 2f
        val radius = width * (.35f + .15f * phase)
        if (radius <= 0f) return
        paint.shader = RadialGradient(x, y, radius, ColorUtils.setAlphaComponent(color, 110),
            ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
        paint.color = color
        paint.alpha = (190 + 65 * phase).toInt()
        canvas.drawCircle(x, y, width * .16f, paint)
    }
}
