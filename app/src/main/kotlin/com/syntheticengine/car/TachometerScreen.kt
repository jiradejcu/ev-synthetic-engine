package com.syntheticengine.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.syntheticengine.EngineController
import com.syntheticengine.R
import com.syntheticengine.ui.TachometerRenderer

/**
 * The car-display screen: a NavigationTemplate whose full-screen surface we draw the
 * tachometer onto, plus an action strip with start/stop and engine selection.
 */
class TachometerScreen(carContext: CarContext, private val controller: EngineController) : Screen(carContext) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val onEngineChanged: () -> Unit = { mainHandler.post { invalidate() } }

    init {
        lifecycle.addObserver(SurfaceRenderer(carContext, controller))
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = controller.addListener(onEngineChanged)
            override fun onDestroy(owner: LifecycleOwner) = controller.removeListener(onEngineChanged)
        })
    }

    override fun onGetTemplate(): Template {
        val running = controller.running
        val startStop = Action.Builder()
            .setIcon(icon(if (running) R.drawable.ic_stop else R.drawable.ic_play))
            .setOnClickListener { controller.toggle() }
            .build()
        val engine = Action.Builder()
            .setTitle(controller.profile.displayName)
            .setOnClickListener {
                controller.cycleProfile()
                CarToast.makeText(carContext, controller.profile.displayName, CarToast.LENGTH_SHORT).show()
            }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(startStop).addAction(engine).build())
            .setBackgroundColor(CarColor.createCustom(TachometerRenderer.BACKGROUND, TachometerRenderer.BACKGROUND))
            .build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}

/**
 * Draws [TachometerRenderer] onto the surface Android Auto provides, every display frame
 * (capped at ~30 fps) while the screen is visible.
 */
private class SurfaceRenderer(
    private val carContext: CarContext,
    private val controller: EngineController,
) : DefaultLifecycleObserver {

    private val renderer = TachometerRenderer()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visibleArea: Rect? = null
    private var visible = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!visible || surface == null) return
            if (frameTimeNanos - lastFrameNanos >= 30_000_000L) {
                lastFrameNanos = frameTimeNanos
                drawFrame()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surface = container.surface
            width = container.width
            height = container.height
            scheduleFrames()
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visibleArea = area
        }

        override fun onStableAreaChanged(area: Rect) = Unit

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            surface = null
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onStart(owner: LifecycleOwner) {
        visible = true
        scheduleFrames()
    }

    override fun onStop(owner: LifecycleOwner) {
        visible = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        surface = null
    }

    private fun scheduleFrames() {
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    private fun drawFrame() {
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = try { s.lockHardwareCanvas() } catch (_: Exception) { return }
        try {
            canvas.drawColor(TachometerRenderer.BACKGROUND)
            // The visible area excludes the action strip and any host UI.
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
            renderer.draw(
                canvas, area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(),
                controller.frame(),
            )
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }
}
