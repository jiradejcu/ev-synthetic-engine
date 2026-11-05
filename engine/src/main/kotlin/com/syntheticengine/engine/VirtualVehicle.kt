package com.syntheticengine.engine

import kotlin.math.max

/**
 * Tiny longitudinal car model used for "demo mode" (testing without driving):
 * pedal in, speed and acceleration out.
 */
class VirtualVehicle {
    var speedMps = 0.0
        private set
    var accelMps2 = 0.0
        private set

    /** @param pedal 0..1 accelerator, @param brake 0..1 */
    fun update(pedal: Double, brake: Double, dt: Double) {
        val p = pedal.coerceIn(0.0, 1.0)
        val traction = 5.0 * (1.0 - speedMps / 75.0).coerceAtLeast(0.0)
        val drag = if (speedMps > 0.05) 0.12 + 0.00035 * speedMps * speedMps else 0.0
        val engineBrake = if (p < 0.02 && speedMps > 0.5) 0.35 else 0.0
        accelMps2 = p * traction - drag - engineBrake - brake.coerceIn(0.0, 1.0) * 8.0
        speedMps = max(0.0, speedMps + accelMps2 * dt)
        if (speedMps == 0.0 && accelMps2 < 0) accelMps2 = 0.0
    }

    fun reset() {
        speedMps = 0.0
        accelMps2 = 0.0
    }
}
