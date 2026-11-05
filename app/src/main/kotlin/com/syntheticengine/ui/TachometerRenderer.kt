package com.syntheticengine.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import com.syntheticengine.engine.AccelSource
import com.syntheticengine.engine.EngineProfile
import com.syntheticengine.engine.EngineState
import com.syntheticengine.engine.Motion
import com.syntheticengine.engine.SpeedSource
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything the gauge needs for one frame. */
data class GaugeFrame(
    val state: EngineState,
    val motion: Motion,
    val profile: EngineProfile,
    val running: Boolean,
)

/**
 * Draws the tachometer with plain [Canvas] calls so the exact same code renders on the
 * phone ([TachometerView]) and on the Android Auto car display surface.
 *
 * Sizes are all relative to the gauge radius, so it scales to any screen.
 * On wide areas (typical car displays) a side panel with speed, gear and load is drawn
 * to the right of the dial.
 */
class TachometerRenderer {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FACE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeCap = Paint.Cap.ROUND }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val oval = RectF()
    private var shownRpm = 0.0

    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, frame: GaugeFrame) {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        val wide = w > h * 1.55f
        val gaugeSize = if (wide) min(h, w * 0.58f) else min(w, h)
        val pad = gaugeSize * 0.04f
        val r = gaugeSize / 2f - pad
        val cx = if (wide) left + pad + r + (w - gaugeSize) * 0.08f else left + w / 2f
        val cy = top + h / 2f

        // Engine-off: needle falls to zero; otherwise follow (already smooth) state.
        val targetRpm = if (frame.running) frame.state.rpm else 0.0
        shownRpm += (targetRpm - shownRpm) * 0.35

        drawDial(canvas, cx, cy, r, frame)
        if (wide) {
            val panelLeft = cx + r + pad * 2
            drawSidePanel(canvas, panelLeft, top + pad, right - pad, bottom - pad, frame)
        }
    }

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, r: Float, frame: GaugeFrame) {
        val p = frame.profile
        val maxRpm = ceil(p.limiterRpm / 1000.0) * 1000.0
        fun angle(rpm: Double) = START_DEG + SWEEP_DEG * (rpm / maxRpm).coerceIn(0.0, 1.04)

        // Face + bezel. The bezel glows during a shift-light flash.
        canvas.drawCircle(cx, cy, r, face)
        val flash = frame.running && shownRpm > p.redlineRpm * 0.97 &&
            (SystemClock.uptimeMillis() / 70) % 2 == 0L
        ring.color = if (flash) ACCENT else BEZEL
        ring.strokeWidth = r * 0.035f
        canvas.drawCircle(cx, cy, r - ring.strokeWidth / 2, ring)

        // Red zone.
        val zoneR = r * 0.86f
        arc.strokeWidth = r * 0.07f
        arc.color = REDZONE
        oval.set(cx - zoneR, cy - zoneR, cx + zoneR, cy + zoneR)
        val redStart = angle(p.redlineRpm)
        canvas.drawArc(oval, redStart.toFloat(), (angle(maxRpm) - redStart).toFloat(), false, arc)

        // Ticks + numerals.
        val outer = r * 0.90f
        text.color = Color.WHITE
        text.textSize = r * 0.15f
        var rpm = 0.0
        while (rpm <= maxRpm + 1) {
            val major = rpm % 1000.0 == 0.0
            val half = !major && rpm % 500.0 == 0.0
            val len = when { major -> r * 0.13f; half -> r * 0.08f; else -> r * 0.045f }
            tick.strokeWidth = when { major -> r * 0.022f; half -> r * 0.014f; else -> r * 0.008f }
            tick.color = if (rpm >= p.redlineRpm) REDZONE_TICK else TICK
            val a = Math.toRadians(angle(rpm))
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * outer, cy + sa * outer, cx + ca * (outer - len), cy + sa * (outer - len), tick)
            if (major) {
                val nr = r * 0.64f
                canvas.drawText(
                    (rpm / 1000).roundToInt().toString(),
                    cx + ca * nr, cy + sa * nr + text.textSize * 0.36f, text,
                )
            }
            rpm += 250.0
        }

        // Legend.
        text.textSize = r * 0.065f
        text.color = DIM
        canvas.drawText("×1000 r/min", cx, cy - r * 0.30f, text)

        // Load (throttle) arc just inside the ticks.
        val loadR = r * 0.47f
        arc.strokeWidth = r * 0.035f
        oval.set(cx - loadR, cy - loadR, cx + loadR, cy + loadR)
        arc.color = TRACK
        canvas.drawArc(oval, START_DEG.toFloat(), SWEEP_DEG.toFloat(), false, arc)
        if (frame.running) {
            arc.color = if (frame.state.overrun) COAST else LOAD
            canvas.drawArc(oval, START_DEG.toFloat(), (SWEEP_DEG * frame.state.throttle).toFloat(), false, arc)
        }

        // Gear + digital rpm.
        text.color = Color.WHITE
        text.textSize = r * 0.30f
        val gearText = when {
            !frame.running -> "OFF"
            frame.state.gear == 0 -> "D"
            else -> "D${frame.state.gear}"
        }
        canvas.drawText(gearText, cx, cy + r * 0.11f, text)
        text.textSize = r * 0.10f
        text.color = if (frame.state.limiter && frame.running) ACCENT else DIM
        canvas.drawText("${(shownRpm / 10).roundToInt() * 10} rpm", cx, cy + r * 0.30f, text)

        // Needle.
        val na = Math.toRadians(angle(shownRpm))
        val nca = cos(na).toFloat(); val nsa = sin(na).toFloat()
        needle.strokeWidth = r * 0.028f
        canvas.drawLine(cx - nca * r * 0.12f, cy - nsa * r * 0.12f, cx + nca * r * 0.86f, cy + nsa * r * 0.86f, needle)
        hub.color = BEZEL
        canvas.drawCircle(cx, cy, r * 0.075f, hub)
        hub.color = ACCENT
        canvas.drawCircle(cx, cy, r * 0.035f, hub)

        // Speed at the bottom of the dial.
        text.color = Color.WHITE
        text.textSize = r * 0.16f
        canvas.drawText(frame.motion.speedMps.times(3.6).roundToInt().toString(), cx, cy + r * 0.70f, text)
        text.textSize = r * 0.06f
        text.color = DIM
        canvas.drawText("km/h", cx, cy + r * 0.80f, text)
    }

    private fun drawSidePanel(canvas: Canvas, l: Float, t: Float, rgt: Float, b: Float, frame: GaugeFrame) {
        val w = rgt - l
        val h = b - t
        if (w < 40) return
        val unit = min(h / 9f, w / 5f)
        text.textAlign = Paint.Align.LEFT
        var y = t + unit * 1.0f

        text.color = DIM; text.textSize = unit * 0.45f
        canvas.drawText(frame.profile.displayName.uppercase(), l, y, text)

        y += unit * 2.2f
        text.color = Color.WHITE; text.textSize = unit * 2.0f
        val kmh = (frame.motion.speedMps * 3.6).roundToInt().toString()
        canvas.drawText(kmh, l, y, text)
        val kmhW = text.measureText(kmh)
        text.color = DIM; text.textSize = unit * 0.5f
        canvas.drawText(" km/h", l + kmhW, y, text)

        // Load bar.
        y += unit * 1.0f
        text.textSize = unit * 0.42f
        canvas.drawText("LOAD", l, y, text)
        y += unit * 0.3f
        val barH = unit * 0.35f
        val barW = w * 0.9f
        tick.strokeWidth = barH
        tick.strokeCap = Paint.Cap.BUTT
        tick.color = TRACK
        canvas.drawLine(l, y + barH / 2, l + barW, y + barH / 2, tick)
        if (frame.running) {
            tick.color = if (frame.state.overrun) COAST else LOAD
            canvas.drawLine(l, y + barH / 2, l + barW * frame.state.throttle.toFloat(), y + barH / 2, tick)
        }
        tick.strokeCap = Paint.Cap.ROUND

        // Longitudinal g.
        y += unit * 1.4f
        text.color = Color.WHITE; text.textSize = unit * 0.7f
        canvas.drawText(String.format("%+.2f g", frame.motion.accelMps2 / 9.81), l, y, text)

        // Sources.
        y += unit * 0.9f
        text.color = DIM; text.textSize = unit * 0.38f
        canvas.drawText("Speed: ${label(frame.motion.speedSource)}", l, y, text)
        y += unit * 0.55f
        canvas.drawText("Accel: ${label(frame.motion.accelSource)}", l, y, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun label(s: SpeedSource) = when (s) {
        SpeedSource.CAR -> "car"
        SpeedSource.GPS -> "GPS"
        SpeedSource.VIRTUAL -> "demo"
        SpeedSource.NONE -> "waiting…"
    }

    private fun label(s: AccelSource) = when (s) {
        AccelSource.CAR_ACCELEROMETER -> "car sensor"
        AccelSource.PHONE_ACCELEROMETER -> "phone sensor"
        AccelSource.SPEED_DERIVATIVE -> "from speed"
        AccelSource.NONE -> "waiting…"
    }

    companion object {
        const val BACKGROUND = 0xFF0B0C10.toInt()
        private const val START_DEG = 135.0
        private const val SWEEP_DEG = 270.0
        private const val FACE = 0xFF15171C.toInt()
        private const val BEZEL = 0xFF2A2E37.toInt()
        private const val TICK = 0xFFE8EAED.toInt()
        private const val REDZONE = 0xFFB3261E.toInt()
        private const val REDZONE_TICK = 0xFFFF6B5E.toInt()
        private const val ACCENT = 0xFFE5482F.toInt()
        private const val DIM = 0xFF9AA0A6.toInt()
        private const val TRACK = 0xFF2A2E37.toInt()
        private const val LOAD = 0xFFF5A524.toInt()
        private const val COAST = 0xFF4FA3F7.toInt()
    }
}
