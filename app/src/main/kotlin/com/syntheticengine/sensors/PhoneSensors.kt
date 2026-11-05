package com.syntheticengine.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.syntheticengine.engine.MotionFusion
import com.syntheticengine.engine.SpeedSource

/**
 * Phone-side motion sources, used when the car does not expose its own sensors:
 *  - linear accelerometer (gravity removed); MotionFusion learns which way is forward
 *  - GPS speed (~1-2 Hz)
 */
class PhoneSensors(private val context: Context, private val fusion: MotionFusion) :
    SensorEventListener, LocationListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var started = false

    fun start() {
        if (started) return
        started = true
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        startGps()
    }

    @SuppressLint("MissingPermission") // checked below
    private fun startGps() {
        val lm = locationManager ?: return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "No location permission: GPS speed disabled")
            return
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPS unavailable", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
        try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        fusion.onDeviceLinearAcceleration(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), SystemClock.elapsedRealtimeNanos())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) {
            fusion.onSpeed(location.speed.toDouble(), location.elapsedRealtimeNanos, SpeedSource.GPS)
        }
    }

    // Implemented explicitly: these are abstract (not default) methods before API 30.
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit

    private companion object {
        const val TAG = "PhoneSensors"
    }
}
