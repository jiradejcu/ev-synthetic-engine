package com.syntheticengine.car

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.syntheticengine.EngineController

/** Entry point Android Auto binds to when the app is opened on the car display. */
class EngineCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = EngineSession()
}

class EngineSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val controller = EngineController.get(carContext)
        val carSensors = CarSensorsSource(carContext, controller.fusion)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carSensors.start()
                // Opening the app on the car display starts the engine.
                controller.start()
                requestLocationIfNeeded(controller)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                carSensors.stop()
                // Car disconnected / app closed on the head unit: switch the engine off.
                controller.stop()
            }
        })
        return TachometerScreen(carContext, controller)
    }

    /** GPS is the speed fallback when the car does not share its own speed. */
    private fun requestLocationIfNeeded(controller: EngineController) {
        val perm = Manifest.permission.ACCESS_FINE_LOCATION
        if (carContext.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        try {
            carContext.requestPermissions(listOf(perm)) { granted, _ ->
                if (perm in granted) controller.onPermissionsChanged()
            }
        } catch (_: Exception) {
            // Older hosts: the user can grant it from the phone app instead.
        }
    }
}
