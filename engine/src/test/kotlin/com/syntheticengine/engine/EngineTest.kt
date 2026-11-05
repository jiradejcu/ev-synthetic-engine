package com.syntheticengine.engine

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineSimulatorTest {

    private fun drive(profile: EngineProfile, seconds: Double, pedal: Double): List<EngineState> {
        val car = VirtualVehicle()
        val sim = EngineSimulator(profile)
        val dt = 0.01
        return (0 until (seconds / dt).toInt()).map {
            car.update(pedal, 0.0, dt)
            sim.update(car.speedMps, car.accelMps2, dt)
        }
    }

    @Test
    fun idlesWhenStationary() {
        val p = EngineProfile.V8_CROSSPLANE
        val s = EngineSimulator(p).let { sim -> (0 until 200).map { sim.update(0.0, 0.0, 0.01) }.last() }
        assertEquals(p.idleRpm, s.rpm, 1.0)
        assertEquals(0, s.gear)
        assertEquals(0.0, s.throttle, 1e-9)
    }

    @Test
    fun fullThrottleRunShiftsUpThroughGearsWithinRedline() {
        for (p in EngineProfile.ALL) {
            val states = drive(p, 30.0, 1.0)
            val maxGear = states.maxOf { it.gear }
            assertTrue(maxGear >= 4, "${p.id} reached gear $maxGear")
            // Gears only ever go up on a constant full-throttle pull.
            val gears = states.map { it.gear }.filter { it > 0 }
            assertTrue(gears.zipWithNext().all { (a, b) -> b >= a }, "${p.id} gear sequence not monotonic")
            assertTrue(states.all { it.rpm <= p.limiterRpm + 1 }, "${p.id} exceeded limiter")
            // Hard acceleration shifts up near redline.
            val peak = states.take(500).maxOf { it.rpm }
            assertTrue(peak > p.redlineRpm * 0.8, "${p.id} peak rpm $peak too low for WOT")
        }
    }

    @Test
    fun gentleCruiseShiftsEarlyAndKeepsRpmLow() {
        val p = EngineProfile.INLINE_4
        val sim = EngineSimulator(p)
        var s = sim.state
        // Steady 90 km/h cruise.
        repeat(1000) { s = sim.update(25.0, 0.0, 0.01) }
        assertTrue(s.gear >= 5, "cruise gear ${s.gear}")
        assertTrue(s.rpm < 2600, "cruise rpm ${s.rpm}")
        assertTrue(s.throttle < 0.3, "cruise throttle ${s.throttle}")
    }

    @Test
    fun kickdownOnHardAccelerationAtCruise() {
        val p = EngineProfile.V8_CROSSPLANE
        val sim = EngineSimulator(p)
        repeat(1000) { sim.update(22.0, 0.0, 0.01) }
        val cruiseGear = sim.state.gear
        repeat(300) { sim.update(22.0, 4.0, 0.01) }
        assertTrue(sim.state.gear < cruiseGear, "expected kickdown from $cruiseGear, got ${sim.state.gear}")
    }

    @Test
    fun brakingIsOverrun() {
        val p = EngineProfile.V8_CROSSPLANE
        val sim = EngineSimulator(p)
        repeat(500) { sim.update(30.0, 0.0, 0.01) }
        repeat(30) { sim.update(30.0, -3.0, 0.01) }
        assertTrue(sim.state.overrun)
        assertEquals(0.0, sim.state.throttle, 0.05)
    }
}

class EngineSynthTest {

    private fun render(profile: EngineProfile, state: EngineState, seconds: Double = 1.0): FloatArray {
        val fs = 48_000
        val synth = EngineSynth(fs, profile)
        val frames = 256
        val out = FloatArray((seconds * fs).toInt() / frames * frames * 2)
        val buf = FloatArray(frames * 2)
        var i = 0
        while (i < out.size) {
            synth.render(buf, frames, state)
            buf.copyInto(out, i)
            i += buf.size
        }
        return out
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    @Test
    fun outputIsFiniteAndBounded() {
        for (p in EngineProfile.ALL) {
            for (rpm in listOf(p.idleRpm, 3000.0, p.limiterRpm)) {
                for (thr in listOf(0.0, 1.0)) {
                    val st = EngineState(rpm, 3, thr, 20.0, 1.0, false, thr == 0.0, rpm >= p.limiterRpm)
                    val out = render(p, st, 0.5)
                    assertTrue(out.all { it.isFinite() && abs(it) <= 1f }, "${p.id} $rpm $thr")
                    assertTrue(rms(out) > 0.01, "${p.id} silent at $rpm/$thr")
                }
            }
        }
    }

    @Test
    fun louderUnderLoad() {
        val p = EngineProfile.INLINE_4
        val coast = render(p, EngineState(3000.0, 3, 0.0, 20.0, 0.0, false, false, false))
        val wot = render(p, EngineState(3000.0, 3, 1.0, 20.0, 3.0, false, false, false))
        assertTrue(rms(wot) > rms(coast) * 1.3, "wot ${rms(wot)} coast ${rms(coast)}")
    }

    /** The dominant periodicity must be the firing frequency: rpm/60 * cylinders/2. */
    @Test
    fun pitchTracksFiringFrequency() {
        val p = EngineProfile.INLINE_4
        val fs = 48_000
        for (rpm in listOf(1500.0, 3000.0, 6000.0)) {
            val out = render(p, EngineState(rpm, 3, 0.6, 20.0, 1.0, false, false, false), 1.0)
            val mono = DoubleArray(out.size / 2) { (out[2 * it] + out[2 * it + 1]) / 2.0 }
            val expected = rpm / 60.0 * p.cylinders / 2.0
            val lag = autocorrelationPeakLag(mono, (fs / (expected * 1.5)).toInt(), (fs / (expected / 1.5)).toInt())
            val measured = fs.toDouble() / lag
            assertEquals(expected, measured, expected * 0.04, "rpm $rpm")
        }
    }

    private fun autocorrelationPeakLag(x: DoubleArray, minLag: Int, maxLag: Int): Int {
        val start = x.size / 4
        var best = minLag
        var bestVal = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in start until x.size - lag) s += x[i] * x[i + lag]
            if (s > bestVal) { bestVal = s; best = lag }
        }
        return best
    }
}

class MotionFusionTest {

    @Test
    fun carSpeedOverridesGps() {
        val f = MotionFusion()
        f.onSpeed(20.0, 0, SpeedSource.CAR)
        f.onSpeed(5.0, 100_000_000, SpeedSource.GPS)
        var m = f.estimate(0)
        repeat(50) { m = f.estimate(100_000_000L + it * 10_000_000L) }
        assertEquals(SpeedSource.CAR, m.speedSource)
        assertTrue(m.speedMps > 15)
    }

    @Test
    fun learnsPhoneOrientationAndDeadReckons() {
        val f = MotionFusion()
        // Phone mounted rotated: car forward is the phone's -X axis.
        val a = 2.0
        var v = 0.0
        var t = 0L
        val step = 20_000_000L // 50 Hz
        var m: Motion? = null
        for (i in 0 until 50 * 12) {
            f.onDeviceLinearAcceleration(-a, 0.1, 0.05, t)
            if (i % 50 == 0) f.onSpeed(v, t, SpeedSource.GPS)
            m = f.estimate(t)
            v += a * 0.02
            t += step
        }
        assertEquals(AccelSource.PHONE_ACCELEROMETER, m!!.accelSource)
        assertEquals(a, m.accelMps2, 0.2)
        // Dead reckoning keeps the estimate close to truth between 1 Hz fixes.
        assertEquals(v, m.speedMps, 1.5)
    }

    @Test
    fun carAccelerometerPreferred() {
        val f = MotionFusion()
        f.onSpeed(10.0, 0, SpeedSource.CAR)
        var m: Motion? = null
        for (i in 0 until 50) {
            f.onCarAcceleration(listOf(0.0f, 1.5f, 9.8f), i * 10_000_000L)
            m = f.estimate(i * 10_000_000L)
        }
        assertEquals(AccelSource.CAR_ACCELEROMETER, m!!.accelSource)
        assertEquals(1.5, m.accelMps2, 0.1)
    }
}
