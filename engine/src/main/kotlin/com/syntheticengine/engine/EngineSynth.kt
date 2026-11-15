package com.syntheticengine.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real-time internal-combustion engine sound synthesizer.
 *
 * Model (per sample):
 *  1. A crank-angle accumulator runs over the 720° four-stroke cycle at the current RPM.
 *  2. Each time it passes a cylinder's firing angle, a combustion pulse (decaying
 *     impulse + noise, scaled by throttle) is injected into that cylinder's exhaust bank.
 *  3. Each bank is shaped by a set of band-pass resonators (exhaust/body modes, which
 *     rise slightly with load as gas temperature rises), plus a low-passed "thump".
 *  4. A low engine-order tone, intake noise, overrun crackle and limiter cuts are added,
 *     then everything is DC-blocked and soft-clipped. Banks are panned left/right.
 *
 * With the throttle closed the engine is still turning, so loudness keeps rising with
 * RPM. On overrun the pulses turn weak and irregular (a lean burble with occasional
 * pops) and the closed throttle plate adds a vacuum hiss.
 *
 * RPM/throttle are interpolated across each buffer so there is no zipper noise.
 */
class EngineSynth(
    private val sampleRate: Int,
    profile: EngineProfile,
    seed: Long = 0x5EED,
) {
    var profile: EngineProfile = profile
        set(value) {
            field = value
            configure()
        }

    /** Master volume 0..1. */
    @Volatile
    var volume: Double = 0.8

    private val rng = XorShift(seed)
    private var crankDeg = 0.0
    private var lastRpm = profile.idleRpm
    private var lastThrottle = 0.0
    private var lastOverrun = 0.0

    private lateinit var cylGain: DoubleArray
    private val pulseEnv = DoubleArray(2)
    private val pulseNoiseAmt = DoubleArray(2)
    private lateinit var resonators: Array<Array<Biquad>>
    private val thumpLp = DoubleArray(2)
    private val dcX = DoubleArray(2)
    private val dcY = DoubleArray(2)
    private var intakeBp = Biquad()
    private var hissBp = Biquad()
    private var limiterSkip = 0
    private val pulseDecay = exp(-1.0 / (0.0018 * sampleRate))

    init {
        configure()
    }

    private fun configure() {
        val p = profile
        cylGain = DoubleArray(p.cylinders) { 1.0 + p.cylinderVariation * (rng.nextDouble() * 2 - 1) }
        resonators = Array(2) { Array(p.resonancesHz.size) { Biquad() } }
        updateResonators(0.0)
        intakeBp.setBandPass(1800.0, 1.2, sampleRate)
        hissBp.setBandPass(2600.0, 0.9, sampleRate)
    }

    private fun updateResonators(throttle: Double) {
        val p = profile
        val heat = 1.0 + 0.12 * throttle
        for (bank in 0..1) {
            val detune = if (bank == 0) 1.0 else p.bankDetune
            for (i in p.resonancesHz.indices) {
                resonators[bank][i].setBandPass(p.resonancesHz[i] * detune * heat, p.resonanceQ, sampleRate)
            }
        }
    }

    /**
     * Render [frames] stereo frames (interleaved L,R) into [out], moving from the
     * previous engine state to [state] over the buffer.
     */
    fun render(out: FloatArray, frames: Int, state: EngineState) {
        val p = profile
        val targetRpm = state.rpm.coerceIn(0.0, p.limiterRpm * 1.05)
        val targetThrottle = state.throttle.coerceIn(0.0, 1.0)
        val targetOverrun = if (state.overrun) 1.0 else 0.0
        updateResonators(targetThrottle)

        val invFs = 1.0 / sampleRate
        val thumpCoef = 1.0 - exp(-2 * PI * (180.0 + 1400.0 * targetThrottle) * invFs)
        val dcR = 1.0 - 2 * PI * 25.0 * invFs
        val gains = p.resonanceGains
        val vol = volume

        for (n in 0 until frames) {
            val t = (n + 1).toDouble() / frames
            val rpm = lastRpm + (targetRpm - lastRpm) * t
            val thr = lastThrottle + (targetThrottle - lastThrottle) * t
            val ov = lastOverrun + (targetOverrun - lastOverrun) * t
            val rpmNorm = rpm / p.redlineRpm

            // --- Crank advance & firing events ---
            val prev = crankDeg
            crankDeg += rpm / 60.0 * 360.0 * invFs
            val wrapped = crankDeg >= 720.0
            if (wrapped) crankDeg -= 720.0
            for (c in 0 until p.cylinders) {
                val a = p.firingAnglesDeg[c]
                val fired = if (!wrapped) a > prev && a <= crankDeg else a > prev || a <= crankDeg
                if (fired) fire(c, thr, rpmNorm, state)
            }

            // --- Exhaust banks ---
            var left = 0.0
            var right = 0.0
            for (bank in 0..1) {
                val env = pulseEnv[bank]
                val excitation = env * (1.0 + pulseNoiseAmt[bank] * (rng.nextDouble() * 2 - 1))
                pulseEnv[bank] = env * pulseDecay

                var s = 0.0
                val res = resonators[bank]
                for (i in res.indices) s += gains[i] * res[i].process(excitation)
                thumpLp[bank] += (excitation - thumpLp[bank]) * thumpCoef
                s += 0.9 * thumpLp[bank]

                // DC blocker
                val y = s - dcX[bank] + dcR * dcY[bank]
                dcX[bank] = s
                dcY[bank] = y
                if (bank == 0) left = y else right = y
            }

            // --- Engine order tone (gives the low-end body) ---
            val orderPhase = crankDeg / 720.0 * 2 * PI
            val tone = (0.10 + 0.06 * rpmNorm + 0.12 * thr) * sin(orderPhase * p.cylinders) +
                0.035 * sin(orderPhase * p.cylinders * 0.5 + 0.7)

            // --- Intake / mechanical noise ---
            val intake = intakeBp.process(rng.nextDouble() * 2 - 1) *
                (0.02 + 0.10 * thr * rpmNorm) * (0.5 + pulseEnv[0] + pulseEnv[1])

            // Closed-throttle vacuum hiss through the throttle plate while on overrun.
            val hiss = hissBp.process(rng.nextDouble() * 2 - 1) * 0.07 * ov * rpmNorm

            // Extra gain on overrun so coasting/braking stays clearly audible.
            val drive = p.outputGain * (0.55 + 0.30 * rpmNorm * (1 - thr) + 0.45 * thr) * (1 + OVERRUN_BOOST * ov)
            val mixL = 0.68 * left + 0.32 * right + tone + intake + hiss
            val mixR = 0.32 * left + 0.68 * right + tone + intake + hiss
            out[2 * n] = (tanh(mixL * drive) * 0.85 * vol).toFloat()
            out[2 * n + 1] = (tanh(mixR * drive) * 0.85 * vol).toFloat()
        }
        lastRpm = targetRpm
        lastThrottle = targetThrottle
        lastOverrun = targetOverrun
    }

    private companion object {
        /** Extra drive while on overrun; 0.6 is about +4 dB. */
        const val OVERRUN_BOOST = 0.6
    }

    private fun fire(cyl: Int, throttle: Double, rpmNorm: Double, state: EngineState) {
        val p = profile
        val bank = p.exhaustBank[cyl]
        if (state.limiter) {
            // Spark cut: drop most combustion events while on the limiter.
            limiterSkip = (limiterSkip + 1) % 3
            if (limiterSkip != 0) return
        }
        // Even with the throttle shut, a faster-spinning engine pumps harder and is louder.
        val base = (0.30 + 0.30 * rpmNorm).coerceAtMost(0.6)
        var amp = (base + (1 - base) * throttle) * cylGain[cyl] * (1.0 + 0.08 * (rng.nextDouble() * 2 - 1))
        var noise = p.pulseNoise * (0.4 + 0.6 * throttle)
        if (state.overrun && state.rpm > 1800 && rng.nextDouble() < (0.012 + 0.03 * rpmNorm) * p.overrunPopRate) {
            // Overrun pop: unburnt fuel igniting in the exhaust.
            amp = 1.2 + rng.nextDouble()
            noise = 1.0
        } else if (state.overrun) {
            // Lean, partly misfiring combustion: weak and irregular, a crackly burble.
            amp *= 0.45 + 0.5 * rng.nextDouble()
            noise = maxOf(noise, 0.85)
        }
        pulseEnv[bank] += amp
        pulseNoiseAmt[bank] = noise
    }
}

/** RBJ biquad, used here as a constant 0 dB peak band-pass resonator. */
internal class Biquad {
    private var b0 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun setBandPass(freq: Double, q: Double, sampleRate: Int) {
        val f = freq.coerceAtMost(sampleRate * 0.45)
        val w0 = 2 * PI * f / sampleRate
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        b0 = alpha / a0
        b2 = -alpha / a0
        a1 = -2 * cos(w0) / a0
        a2 = (1 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/** Small, allocation-free PRNG for the audio thread. */
internal class XorShift(seed: Long) {
    private var s = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

    fun nextDouble(): Double {
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return (s ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
