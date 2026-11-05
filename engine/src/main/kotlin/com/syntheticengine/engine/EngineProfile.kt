package com.syntheticengine.engine

/**
 * Gearbox + wheel geometry used to turn road speed into crankshaft RPM.
 */
data class Drivetrain(
    /** Ratio of each forward gear, 1st first. */
    val gearRatios: DoubleArray,
    val finalDrive: Double,
    /** Rolling circumference of the driven tyre, metres. */
    val tireCircumferenceM: Double,
    /** Torque-converter stall speed: the RPM the engine flares to on a full-throttle launch. */
    val stallRpm: Double,
) {
    val gearCount: Int get() = gearRatios.size

    /** Crank RPM for a given road speed in the given 1-based gear (no slip). */
    fun rpmAt(speedMps: Double, gear: Int): Double {
        val wheelRps = speedMps / tireCircumferenceM
        return wheelRps * 60.0 * gearRatios[gear - 1] * finalDrive
    }
}

/**
 * Everything that defines how a synthetic engine sounds and behaves.
 *
 * Firing angles are positions in the 720° four-stroke cycle at which each cylinder
 * fires. [exhaustBank] says which exhaust (0 = left, 1 = right) that cylinder's pulse
 * enters. Uneven per-bank pulse spacing (e.g. a cross-plane V8) is what produces the
 * characteristic "burble", so the banks are filtered separately before mixing.
 */
data class EngineProfile(
    val id: String,
    val displayName: String,
    val firingAnglesDeg: DoubleArray,
    val exhaustBank: IntArray,
    val idleRpm: Double,
    val redlineRpm: Double,
    val limiterRpm: Double,
    /** Exhaust/body resonance frequencies (Hz) and their mix weights. */
    val resonancesHz: DoubleArray,
    val resonanceGains: DoubleArray,
    val resonanceQ: Double,
    /** How much each cylinder's pulse strength differs from the others (0..1). */
    val cylinderVariation: Double,
    /** Amount of broadband noise in each combustion pulse (0..1). */
    val pulseNoise: Double,
    /** Detune of the right bank's resonators vs the left bank. */
    val bankDetune: Double,
    val outputGain: Double,
    val drivetrain: Drivetrain,
) {
    val cylinders: Int get() = firingAnglesDeg.size

    init {
        require(firingAnglesDeg.size == exhaustBank.size) { "one exhaust bank per cylinder" }
        require(resonancesHz.size == resonanceGains.size) { "one gain per resonance" }
        require(idleRpm < redlineRpm && redlineRpm <= limiterRpm)
    }

    companion object {
        private val sixSpeedAuto = Drivetrain(
            gearRatios = doubleArrayOf(4.17, 2.34, 1.52, 1.14, 0.87, 0.69),
            finalDrive = 3.42,
            tireCircumferenceM = 2.07,
            stallRpm = 2600.0,
        )

        /** Inline four, 1-3-4-2, 4-2-1 header pairing 1&4 / 2&3. */
        val INLINE_4 = EngineProfile(
            id = "i4",
            displayName = "Inline-4 2.0",
            firingAnglesDeg = doubleArrayOf(0.0, 540.0, 180.0, 360.0), // cyl 1,2,3,4
            exhaustBank = intArrayOf(0, 1, 1, 0),
            idleRpm = 850.0,
            redlineRpm = 6800.0,
            limiterRpm = 7200.0,
            resonancesHz = doubleArrayOf(140.0, 420.0, 1150.0),
            resonanceGains = doubleArrayOf(1.0, 0.55, 0.25),
            resonanceQ = 3.5,
            cylinderVariation = 0.06,
            pulseNoise = 0.35,
            bankDetune = 1.03,
            outputGain = 1.6,
            drivetrain = sixSpeedAuto,
        )

        /** Cross-plane V8, GM numbering (odd = left bank), firing order 1-8-7-2-6-5-4-3. */
        val V8_CROSSPLANE = run {
            val order = intArrayOf(1, 8, 7, 2, 6, 5, 4, 3)
            val angles = DoubleArray(8)
            order.forEachIndexed { i, cyl -> angles[cyl - 1] = i * 90.0 }
            EngineProfile(
                id = "v8",
                displayName = "V8 5.7 Cross-plane",
                firingAnglesDeg = angles,
                exhaustBank = IntArray(8) { if ((it + 1) % 2 == 1) 0 else 1 },
                idleRpm = 650.0,
                redlineRpm = 6200.0,
                limiterRpm = 6500.0,
                resonancesHz = doubleArrayOf(75.0, 210.0, 560.0),
                resonanceGains = doubleArrayOf(1.0, 0.7, 0.25),
                resonanceQ = 2.8,
                cylinderVariation = 0.12,
                pulseNoise = 0.45,
                bankDetune = 1.08,
                outputGain = 1.3,
                drivetrain = sixSpeedAuto.copy(
                    gearRatios = doubleArrayOf(4.03, 2.36, 1.53, 1.15, 0.85, 0.67),
                    finalDrive = 3.23,
                    stallRpm = 2300.0,
                ),
            )
        }

        /** Flat six, firing order 1-6-2-4-3-5, cylinders 1-3 left, 4-6 right. */
        val FLAT_6 = run {
            val order = intArrayOf(1, 6, 2, 4, 3, 5)
            val angles = DoubleArray(6)
            order.forEachIndexed { i, cyl -> angles[cyl - 1] = i * 120.0 }
            EngineProfile(
                id = "f6",
                displayName = "Flat-6 3.8",
                firingAnglesDeg = angles,
                exhaustBank = intArrayOf(0, 0, 0, 1, 1, 1),
                idleRpm = 800.0,
                redlineRpm = 8400.0,
                limiterRpm = 8800.0,
                resonancesHz = doubleArrayOf(115.0, 330.0, 880.0, 2100.0),
                resonanceGains = doubleArrayOf(0.9, 0.8, 0.45, 0.15),
                resonanceQ = 4.0,
                cylinderVariation = 0.05,
                pulseNoise = 0.3,
                bankDetune = 1.02,
                outputGain = 1.4,
                drivetrain = Drivetrain(
                    gearRatios = doubleArrayOf(3.91, 2.29, 1.65, 1.30, 1.08, 0.88, 0.62),
                    finalDrive = 3.44,
                    tireCircumferenceM = 2.10,
                    stallRpm = 3000.0,
                ),
            )
        }

        val ALL: List<EngineProfile> = listOf(V8_CROSSPLANE, INLINE_4, FLAT_6)

        fun byId(id: String?): EngineProfile = ALL.firstOrNull { it.id == id } ?: ALL.first()
    }
}
