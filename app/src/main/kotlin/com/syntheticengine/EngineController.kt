package com.syntheticengine

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import com.syntheticengine.audio.EngineAudioThread
import com.syntheticengine.engine.AccelSource
import com.syntheticengine.engine.EngineProfile
import com.syntheticengine.engine.EngineSimulator
import com.syntheticengine.engine.EngineState
import com.syntheticengine.engine.Motion
import com.syntheticengine.engine.MotionFusion
import com.syntheticengine.engine.SpeedSource
import com.syntheticengine.engine.VirtualVehicle
import com.syntheticengine.sensors.PhoneSensors
import com.syntheticengine.ui.GaugeFrame
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide owner of the engine: shared by the phone UI and the Android Auto
 * session (both run in the same process), so whichever screen starts the engine,
 * both show the same gauge and there is only ever one sound stream.
 *
 * Data flow: sensors → [fusion] → audio thread ([tick]) → [EngineSimulator] →
 * EngineSynth → AudioTrack. The audio thread is the clock: the model is advanced once
 * per audio buffer and the latest [state] is published for the gauges to draw.
 */
class EngineController private constructor(private val app: Context) {

    val fusion = MotionFusion()
    private val phoneSensors = PhoneSensors(app, fusion)
    private val vehicle = VirtualVehicle()
    private val prefs = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var audio: EngineAudioThread? = null

    @Volatile var running = false
        private set

    @Volatile var profile: EngineProfile = EngineProfile.byId(prefs.getString(KEY_PROFILE, null))
        set(value) {
            field = value
            prefs.edit().putString(KEY_PROFILE, value.id).apply()
            notifyChanged()
        }

    @Volatile var volume: Double = prefs.getFloat(KEY_VOLUME, 0.8f).toDouble()
        set(value) {
            field = value.coerceIn(0.0, 1.0)
            prefs.edit().putFloat(KEY_VOLUME, field.toFloat()).apply()
        }

    /** Demo mode drives a virtual car from the on-screen pedal instead of sensors. */
    @Volatile var demoMode = false
        set(value) {
            field = value
            vehicle.reset()
            notifyChanged()
        }
    @Volatile var demoPedal = 0.0
    @Volatile var demoBrake = 0.0

    @Volatile var state: EngineState = EngineState.idle(profile)
        private set
    @Volatile var motion = Motion(0.0, 0.0, SpeedSource.NONE, AccelSource.NONE)
        private set

    fun frame() = GaugeFrame(state, motion, profile, running)

    fun start() {
        if (running) return
        running = true
        fusion.reset()
        vehicle.reset()
        phoneSensors.start()
        audio = EngineAudioThread(this).also { it.start() }
        EngineService.start(app)
        notifyChanged()
    }

    fun stop() {
        if (!running) return
        running = false
        audio?.shutdown()
        audio = null
        phoneSensors.stop()
        EngineService.stop(app)
        notifyChanged()
    }

    fun toggle() = if (running) stop() else start()

    fun cycleProfile() {
        val all = EngineProfile.ALL
        profile = all[(all.indexOf(profile) + 1) % all.size]
    }

    /** Call after location permission is granted so GPS speed starts flowing. */
    fun onPermissionsChanged() {
        if (running) {
            phoneSensors.stop()
            phoneSensors.start()
        }
    }

    /** Advance the model by [dt] seconds. Called from the audio thread once per buffer. */
    internal fun tick(sim: EngineSimulator, dt: Double): EngineState {
        val m = if (demoMode) {
            vehicle.update(demoPedal, demoBrake, dt)
            Motion(vehicle.speedMps, vehicle.accelMps2, SpeedSource.VIRTUAL, AccelSource.NONE)
        } else {
            fusion.estimate(SystemClock.elapsedRealtimeNanos())
        }
        motion = m
        return sim.update(m.speedMps, m.accelMps2, dt).also { state = it }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = listeners.forEach { it() }

    companion object {
        private const val KEY_PROFILE = "profile"
        private const val KEY_VOLUME = "volume"

        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: EngineController? = null

        fun get(context: Context): EngineController =
            instance ?: synchronized(this) {
                instance ?: EngineController(context.applicationContext).also { instance = it }
            }
    }
}
