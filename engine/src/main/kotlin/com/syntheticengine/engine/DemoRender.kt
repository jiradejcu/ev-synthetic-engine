package com.syntheticengine.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a scripted drive (idle → full-throttle launch through the gears → lift-off
 * coast → braking) for each engine profile into WAV files, using the exact same
 * pipeline as the app: VirtualVehicle → MotionFusion → EngineSimulator → EngineSynth.
 *
 *   ./gradlew -p engine run --args="out-dir"
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "build/demo").apply { mkdirs() }
    for (profile in EngineProfile.ALL) {
        val file = File(dir, "drive-${profile.id}.wav")
        renderDrive(profile, file)
        println("wrote ${file.absolutePath}")
    }
}

fun renderDrive(profile: EngineProfile, file: File, sampleRate: Int = 48_000, seconds: Double = 26.0) {
    val frames = 480
    val dt = frames.toDouble() / sampleRate
    val car = VirtualVehicle()
    val fusion = MotionFusion()
    val sim = EngineSimulator(profile)
    val synth = EngineSynth(sampleRate, profile)
    val buf = FloatArray(frames * 2)
    val pcm = ByteBuffer.allocate((seconds * sampleRate).toInt() * 4 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)

    var t = 0.0
    var nanos = 0L
    var nextGps = 0.0
    while (t < seconds) {
        val (pedal, brake) = when {
            t < 2.5 -> 0.0 to 0.0
            t < 15.0 -> 1.0 to 0.0
            t < 21.0 -> 0.0 to 0.0
            else -> 0.0 to 0.5
        }
        car.update(pedal, brake, dt)
        // Feed the fusion the way a real phone would see it: 1 Hz GPS + accelerometer.
        if (t >= nextGps) {
            fusion.onSpeed(car.speedMps, nanos, SpeedSource.GPS); nextGps += 1.0
        }
        fusion.onDeviceLinearAcceleration(0.0, car.accelMps2, 0.0, nanos)
        val m = fusion.estimate(nanos)
        val state = sim.update(m.speedMps, m.accelMps2, dt)
        synth.render(buf, frames, state)
        for (s in buf) pcm.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        t += dt
        nanos += (dt * 1e9).toLong()
    }
    writeWav(file, pcm.array(), pcm.position(), sampleRate, 2)
}

fun writeWav(file: File, data: ByteArray, length: Int, sampleRate: Int, channels: Int) {
    RandomAccessFile(file, "rw").use { f ->
        f.setLength(0)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + length).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        h.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(length)
        f.write(h.array())
        f.write(data, 0, length)
    }
}
