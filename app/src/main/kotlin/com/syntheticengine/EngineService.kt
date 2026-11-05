package com.syntheticengine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.syntheticengine.ui.MainActivity

/**
 * Foreground service that keeps the process (sensors + audio thread owned by
 * [EngineController]) alive while the phone is locked in your pocket or a mount.
 */
class EngineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EngineController.get(this).stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // Still works while the app/car screen is visible; just not in the background.
            Log.w(TAG, "Could not enter foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_engine)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "EngineService"
        private const val CHANNEL = "engine"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.syntheticengine.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, EngineService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service not allowed right now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
