package com.syntheticengine.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the current speed reading comes from, best first. */
enum class SpeedSource { CAR, GPS, VIRTUAL, NONE }

/** Where the current acceleration reading comes from, best first. */
enum class AccelSource { CAR_ACCELEROMETER, PHONE_ACCELEROMETER, SPEED_DERIVATIVE, NONE }

data class Motion(
    val speedMps: Double,
    val accelMps2: Double,
    val speedSource: SpeedSource,
    val accelSource: AccelSource,
)

/**
 * Fuses the available motion sensors into a smooth, low-latency estimate of the car's
 * road speed and longitudinal acceleration.
 *
 * Inputs, any subset of which may be present:
 *  - car speed (Android Auto `CarInfo` speed) or GPS speed: accurate but slow (~1 Hz)
 *  - car accelerometer (Android Auto `CarSensors`), already in the vehicle frame
 *  - phone linear accelerometer in an unknown mounting orientation: the forward axis
 *    is learned online by correlating the accelerometer vector with changes in speed.
 *
 * Between speed samples, speed is dead-reckoned with the acceleration estimate, so RPM
 * reacts immediately when you press the pedal instead of waiting for the next GPS fix.
 *
 * Thread-safe: sensor callbacks may arrive on any thread.
 */
class MotionFusion {
    private val lock = Any()

    // Speed
    private var speed = 0.0
    private var speedT = Long.MIN_VALUE
    private var speedSource = SpeedSource.NONE
    private var carSpeedT = Long.MIN_VALUE
    private var derivAccel = 0.0

    // Car accelerometer
    private var carAccel = 0.0
    private var carAccelT = Long.MIN_VALUE

    // Phone accelerometer
    private val devLp = DoubleArray(3)
    private var devT = Long.MIN_VALUE
    private val devSinceSpeed = DoubleArray(3)
    private var devSinceSpeedCount = 0
    private val axis = DoubleArray(3)
    private var axisSamples = 0

    // Output smoothing
    private var outSpeed = 0.0
    private var outAccel = 0.0
    private var outT = Long.MIN_VALUE

    /** Index of the forward axis in the car accelerometer's force vector. */
    var carForwardAxis = 1

    fun reset() = synchronized(lock) {
        speed = 0.0; speedT = Long.MIN_VALUE; speedSource = SpeedSource.NONE
        carSpeedT = Long.MIN_VALUE; derivAccel = 0.0
        carAccelT = Long.MIN_VALUE; devT = Long.MIN_VALUE
        axis.fill(0.0); axisSamples = 0; devSinceSpeed.fill(0.0); devSinceSpeedCount = 0
        outSpeed = 0.0; outAccel = 0.0; outT = Long.MIN_VALUE
    }

    /** Road speed sample. Car speed always wins over GPS while it is fresh. */
    fun onSpeed(mps: Double, tNanos: Long, source: SpeedSource) = synchronized(lock) {
        if (mps.isNaN()) return@synchronized
        if (source == SpeedSource.CAR) carSpeedT = tNanos
        else if (source == SpeedSource.GPS && fresh(carSpeedT, tNanos, 2.0)) return@synchronized

        val v = max(0.0, mps)
        if (speedT != Long.MIN_VALUE) {
            val dt = (tNanos - speedT) / 1e9
            if (dt in 0.05..3.0) {
                val a = (v - speed) / dt
                derivAccel += (a - derivAccel) * (1 - exp(-dt / 0.6))
                learnAxis(a)
            }
        }
        devSinceSpeed.fill(0.0)
        devSinceSpeedCount = 0
        speed = v
        speedT = tNanos
        speedSource = source
    }

    /** Longitudinal acceleration (m/s², forward positive) already in the vehicle frame. */
    fun onCarAcceleration(forces: List<Float>, tNanos: Long) = synchronized(lock) {
        val a = forces.getOrNull(carForwardAxis)?.toDouble() ?: return@synchronized
        if (a.isNaN()) return@synchronized
        val dt = if (carAccelT == Long.MIN_VALUE) 1.0 else (tNanos - carAccelT) / 1e9
        carAccel += (a - carAccel) * (1 - exp(-max(0.0, dt) / 0.15))
        carAccelT = tNanos
    }

    /** Phone linear acceleration (gravity removed), device frame, any orientation. */
    fun onDeviceLinearAcceleration(x: Double, y: Double, z: Double, tNanos: Long) = synchronized(lock) {
        val dt = if (devT == Long.MIN_VALUE) 1.0 else (tNanos - devT) / 1e9
        val k = 1 - exp(-max(0.0, dt) / 0.15)
        devLp[0] += (x - devLp[0]) * k
        devLp[1] += (y - devLp[1]) * k
        devLp[2] += (z - devLp[2]) * k
        devT = tNanos
        devSinceSpeed[0] += x; devSinceSpeed[1] += y; devSinceSpeed[2] += z
        devSinceSpeedCount++
    }

    /** Correlate the phone accelerometer with the measured change in speed. */
    private fun learnAxis(speedAccel: Double) {
        if (devSinceSpeedCount < 3 || abs(speedAccel) < 0.35) return
        val n = devSinceSpeedCount.toDouble()
        val mx = devSinceSpeed[0] / n; val my = devSinceSpeed[1] / n; val mz = devSinceSpeed[2] / n
        val mag = sqrt(mx * mx + my * my + mz * mz)
        if (mag < 0.2) return
        val sign = if (speedAccel > 0) 1.0 else -1.0
        val rate = if (axisSamples < 10) 0.3 else 0.08
        axis[0] += (sign * mx / mag - axis[0]) * rate
        axis[1] += (sign * my / mag - axis[1]) * rate
        axis[2] += (sign * mz / mag - axis[2]) * rate
        axisSamples++
    }

    private fun phoneForwardAccel(): Double? {
        val norm = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        if (axisSamples < 4 || norm < 0.5) return null
        return (devLp[0] * axis[0] + devLp[1] * axis[1] + devLp[2] * axis[2]) / norm
    }

    /** Best estimate at time [tNanos]. Call once per audio buffer / frame. */
    fun estimate(tNanos: Long): Motion = synchronized(lock) {
        val (accel, accelSource) = when {
            fresh(carAccelT, tNanos, 0.5) -> carAccel to AccelSource.CAR_ACCELEROMETER
            fresh(devT, tNanos, 0.5) && phoneForwardAccel() != null ->
                phoneForwardAccel()!! to AccelSource.PHONE_ACCELEROMETER
            fresh(speedT, tNanos, 3.0) -> derivAccel to AccelSource.SPEED_DERIVATIVE
            else -> 0.0 to AccelSource.NONE
        }

        val predicted = if (speedT == Long.MIN_VALUE) 0.0 else {
            val since = min(1.5, max(0.0, (tNanos - speedT) / 1e9))
            // Only dead-reckon with a real accelerometer; the derivative is already "old".
            val a = if (accelSource == AccelSource.SPEED_DERIVATIVE) 0.0 else accel
            max(0.0, speed + a * since)
        }
        val source = if (speedT != Long.MIN_VALUE && fresh(speedT, tNanos, 5.0)) speedSource else SpeedSource.NONE
        val target = if (source == SpeedSource.NONE) outSpeed * 0.98 else predicted

        val dt = if (outT == Long.MIN_VALUE) 1.0 else max(0.0, (tNanos - outT) / 1e9)
        outSpeed += (target - outSpeed) * (1 - exp(-dt / 0.25))
        outAccel += (accel - outAccel) * (1 - exp(-dt / 0.08))
        outT = tNanos
        if (outSpeed < 0.05) outSpeed = 0.0
        Motion(outSpeed, outAccel, source, accelSource)
    }

    private fun fresh(t: Long, now: Long, maxAgeS: Double) =
        t != Long.MIN_VALUE && (now - t) / 1e9 <= maxAgeS
}
