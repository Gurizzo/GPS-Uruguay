package com.radiohonda.radaruy

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Spinner de carga de UYPS (design/uyps-spinner.svg): el fondo con la brújula queda fijo
 * y el anillo punteado con el pin da una vuelta cada 4 segundos.
 */
class SpinnerView(context: Context) : FrameLayout(context) {

    private val ring = ImageView(context).apply { setImageResource(R.drawable.spinner_ring) }
    private val spin = ObjectAnimator.ofFloat(ring, "rotation", 0f, 360f).apply {
        duration = 4000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
    }

    init {
        addView(ImageView(context).apply { setImageResource(R.drawable.spinner_base) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        if (isShown) { if (!spin.isStarted) spin.start() } else spin.cancel()
    }
}
