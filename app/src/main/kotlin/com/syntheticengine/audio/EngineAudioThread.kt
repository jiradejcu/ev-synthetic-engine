package com.syntheticengine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.syntheticengine.EngineController
import com.syntheticengine.engine.EngineSimulator
import com.syntheticengine.engine.EngineSynth
import kotlin.math.max

/**
 * Streams synthesized engine audio. Each loop: advance the engine model by one buffer,
 * render that buffer, blocking-write it to the AudioTrack. The blocking write paces the
 * loop, so the model runs in lock-step with what you hear (~5 ms per step).
 *
 * Audio uses USAGE_MEDIA, so with Android Auto it plays through the car speakers.
 */
class EngineAudioThread(private val controller: EngineController) : Thread("engine-audio") {

    @Volatile private var alive = true

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: 48_000
        val frames = 256
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBytes, frames * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Could not create AudioTrack", e)
            return
        }

        var profile = controller.profile
        val sim = EngineSimulator(profile)
        val synth = EngineSynth(sampleRate, profile)
        val buf = FloatArray(frames * 2)
        val dt = frames.toDouble() / sampleRate

        // Short fade in/out so starting/stopping never clicks.
        var fade = 0.0
        val fadeStep = dt / 0.4
        track.play()
        try {
            while (alive || fade > 0.0) {
                if (controller.profile !== profile) {
                    profile = controller.profile
                    sim.profile = profile
                    synth.profile = profile
                }
                fade = if (alive) minOf(1.0, fade + fadeStep) else maxOf(0.0, fade - fadeStep * 2)
                synth.volume = controller.volume * fade
                val state = controller.tick(sim, dt)
                synth.render(buf, frames, state)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop failed", e)
        } finally {
            track.pause()
            track.flush()
            track.release()
        }
    }

    fun shutdown() {
        alive = false
        join(1000)
    }

    private companion object {
        const val TAG = "EngineAudio"
    }
}
