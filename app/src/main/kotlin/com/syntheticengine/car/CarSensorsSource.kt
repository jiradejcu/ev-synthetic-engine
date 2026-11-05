package com.syntheticengine.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Accelerometer
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Speed
import androidx.car.app.versioning.CarAppApiLevels
import com.syntheticengine.engine.MotionFusion
import com.syntheticengine.engine.SpeedSource
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Reads the car's own sensors through Android Auto (Car App Library hardware APIs):
 *  - vehicle speed (CarInfo) — needs com.google.android.gms.permission.CAR_SPEED
 *  - accelerometer (CarSensors), already in the vehicle's frame
 *
 * Support varies by head unit; anything that is not available simply never reports
 * and [MotionFusion] falls back to the phone's GPS/accelerometer.
 */
class CarSensorsSource(private val carContext: CarContext, private val fusion: MotionFusion) {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var hardware: CarHardwareManager? = null

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val v = speed.rawSpeedMetersPerSecond
        val value = v.value
        if (v.status == CarValue.STATUS_SUCCESS && value != null) {
            // Negative while reversing; the engine only cares about magnitude.
            fusion.onSpeed(abs(value).toDouble(), SystemClock.elapsedRealtimeNanos(), SpeedSource.CAR)
        }
    }

    private val accelListener = OnCarDataAvailableListener<Accelerometer> { accel ->
        val f = accel.forces
        val value = f.value
        if (f.status == CarValue.STATUS_SUCCESS && value != null) {
            fusion.onCarAcceleration(value, SystemClock.elapsedRealtimeNanos())
        }
    }

    fun start() {
        if (hardware != null) return
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) {
            Log.i(TAG, "Host API ${carContext.carAppApiLevel} has no hardware access; using phone sensors")
            return
        }
        val hw = try {
            carContext.getCarService(CarHardwareManager::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "CarHardwareManager unavailable", e)
            return
        }
        hardware = hw
        try {
            hw.carInfo.addSpeedListener(executor, speedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Car speed unavailable", e)
        }
        try {
            hw.carSensors.addAccelerometerListener(CarSensors.UPDATE_RATE_FASTEST, executor, accelListener)
        } catch (e: Exception) {
            Log.w(TAG, "Car accelerometer unavailable", e)
        }
    }

    fun stop() {
        val hw = hardware ?: return
        hardware = null
        try { hw.carInfo.removeSpeedListener(speedListener) } catch (_: Exception) { }
        try { hw.carSensors.removeAccelerometerListener(accelListener) } catch (_: Exception) { }
    }

    private companion object {
        const val TAG = "CarSensors"
    }
}
