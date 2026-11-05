package com.syntheticengine.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.syntheticengine.EngineController

/** Phone-screen tachometer; redraws every frame while attached and visible. */
class TachometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val renderer = TachometerRenderer()
    private val controller = EngineController.get(context)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(
            canvas,
            paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat(),
            controller.frame(),
        )
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
