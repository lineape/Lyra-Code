package com.yukisoffd.lyracode.webaccess

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.yukisoffd.lyracode.MainActivity
import com.yukisoffd.lyracode.R

/** User-visible lifetime protection; screen-on is owned by the foreground Activity. */
class WebAccessForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.web_access_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(8792, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_floating_workspace)
            .setContentTitle(getString(R.string.web_access_title))
            .setContentText(getString(R.string.web_access_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.web_access_stop), stop).build()).build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:webAccess").apply { acquire() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || stopServer == null) { stopServer?.invoke(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "web_access"
        private const val STOP = "com.yukisoffd.lyracode.STOP_WEB_ACCESS"
        internal var stopServer: (() -> Unit)? = null
    }
}
