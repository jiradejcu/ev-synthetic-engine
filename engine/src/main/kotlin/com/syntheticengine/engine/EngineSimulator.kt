package com.syntheticengine.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Snapshot of the simulated engine, published to the UI. */
data class EngineState(
    val rpm: Double,
    /** 1-based gear; 0 when stationary (shown as "N"/"D"). */
    val gear: Int,
    /** Estimated throttle opening 0..1, derived from measured acceleration. */
    val throttle: Double,
    val speedMps: Double,
    val accelMps2: Double,
    val shifting: Boolean,
    /** Engine braking / coasting with closed throttle. */
    val overrun: Boolean,
    /** Bouncing off the rev limiter. */
    val limiter: Boolean,
) {
    val speedKmh: Double get() = speedMps * 3.6

    companion object {
        fun idle(profile: EngineProfile) = EngineState(
            rpm = profile.idleRpm, gear = 1, throttle = 0.0, speedMps = 0.0,
            accelMps2 = 0.0, shifting = false, overrun = false, limiter = false,
        )
    }
}

/**
 * Turns measured vehicle motion (speed + longitudinal acceleration) into a plausible
 * engine state: an automatic gearbox with throttle-dependent shift points, a
 * torque-converter that lets the engine flare on launch, and a load estimate.
 *
 * The real car's engine is never read: throttle is inferred from how hard the car
 * is accelerating relative to what the current gear could deliver.
 */
class EngineSimulator(profile: EngineProfile) {

    var profile: EngineProfile = profile
        set(value) {
            field = value
            gear = gear.coerceIn(1, value.drivetrain.gearCount)
        }

    private var gear = 1
    private var rpm = profile.idleRpm
    private var throttle = 0.0
    private var shiftTimer = 0.0
    private var timeSinceShift = 10.0
    private var limiterHold = 0.0

    var state: EngineState = EngineState.idle(profile)
        private set

    fun reset() {
        gear = 1
        rpm = profile.idleRpm
        throttle = 0.0
        shiftTimer = 0.0
        timeSinceShift = 10.0
        state = EngineState.idle(profile)
    }

    /**
     * Advance the model by [dt] seconds.
     * @param speedMps current road speed
     * @param accelMps2 longitudinal acceleration (positive = speeding up). Using a
     *   specific-force measurement (accelerometer) is ideal: climbing a hill reads as load.
     */
    fun update(speedMps: Double, accelMps2: Double, dt: Double): EngineState {
        val p = profile
        val dtr = p.drivetrain
        val v = max(0.0, speedMps)

        // --- Load / throttle estimate -------------------------------------------
        // Acceleration needed to overcome rolling resistance + aero drag at this speed.
        val resistance = if (v > 0.5) 0.12 + 0.00035 * v * v else 0.0
        val demand = accelMps2 + resistance
        // What full throttle could deliver in this gear (scaled by ratio vs 1st gear).
        val maxAccel = 4.2 * dtr.gearRatios[gear - 1] / dtr.gearRatios[0] + 0.6
        val rawThrottle = when {
            v < 0.3 && accelMps2 < 0.15 -> 0.0
            else -> (demand / maxAccel).coerceIn(0.0, 1.0)
        }
        val shiftCut = if (shiftTimer > 0) 0.35 else 1.0
        throttle += (rawThrottle * shiftCut - throttle) * smoothing(dt, 0.12)

        // --- Automatic gear selection ----------------------------------------
        timeSinceShift += dt
        if (shiftTimer > 0) shiftTimer -= dt
        // Above ~80% throttle the box holds gears to the redline.
        val shape = (throttle / 0.8).coerceAtMost(1.0).pow(1.5)
        val upRpm = lerp(max(1900.0, p.idleRpm * 2.4), p.redlineRpm * 0.94, shape)
        val downRpm = lerp(p.idleRpm * 1.35, p.redlineRpm * 0.45, shape)
        if (shiftTimer <= 0 && timeSinceShift > 0.6) {
            val inGear = dtr.rpmAt(v, gear)
            if (gear < dtr.gearCount && inGear > upRpm) {
                shift(gear + 1)
            } else if (gear > 1) {
                val lower = dtr.rpmAt(v, gear - 1)
                val kickdown = throttle > 0.8 && lower < p.redlineRpm * 0.82
                if ((inGear < downRpm || kickdown) && lower < upRpm * 0.92) shift(gear - 1)
            }
        }
        if (v < 0.5) gear = 1

        // --- Target RPM ----------------------------------------------------------
        val wheelRpm = dtr.rpmAt(v, gear)
        // Torque converter slip: on launch the engine flares towards stall RPM with
        // throttle and holds there until wheel speed catches up; the effect fades out
        // at higher road speed where the converter is locked up.
        val slip = (1.0 - (v - 6.0) / 10.0).coerceIn(0.0, 1.0)
        val converterRpm = p.idleRpm + throttle * (dtr.stallRpm - p.idleRpm) * slip
        var target = max(max(wheelRpm, converterRpm), p.idleRpm)

        var limiter = false
        if (target >= p.limiterRpm) {
            target = p.limiterRpm
            limiter = true
        }

        val tau = when {
            shiftTimer > 0 -> 0.07
            target > rpm -> 0.10 + 0.15 * (1 - throttle) // revs build quicker under load
            else -> 0.16
        }
        rpm += (target - rpm) * smoothing(dt, tau)
        if (limiter) limiterHold = 0.15 else limiterHold -= dt

        val overrun = rawThrottle < 0.03 && accelMps2 < -0.25 && rpm > p.idleRpm * 1.6
        state = EngineState(
            rpm = rpm,
            gear = if (v < 0.3) 0 else gear,
            throttle = throttle,
            speedMps = v,
            accelMps2 = accelMps2,
            shifting = shiftTimer > 0,
            overrun = overrun,
            limiter = limiterHold > 0,
        )
        return state
    }

    private fun shift(newGear: Int) {
        gear = newGear
        shiftTimer = 0.22
        timeSinceShift = 0.0
    }

    private fun smoothing(dt: Double, tau: Double) = 1.0 - exp(-dt / tau)

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * min(1.0, max(0.0, t))
}
