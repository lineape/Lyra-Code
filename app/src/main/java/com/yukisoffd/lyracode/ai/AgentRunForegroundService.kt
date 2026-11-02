package com.yukisoffd.lyracode.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.yukisoffd.lyracode.MainActivity
import com.yukisoffd.lyracode.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Raises the process out of the background while a conversation run is in flight.
 *
 * This only makes a kill less likely. It cannot survive a force-stop, a low-memory kill, or the
 * user removing the task, so resumable batches remain the correctness fix for #14.
 */
class AgentRunForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.agent_run_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, javaClass).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_floating_workspace)
                .setContentTitle(getString(R.string.agent_run_title))
                .setContentText(getString(R.string.agent_run_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.agent_run_stop), stop).build())
                .build(),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agentRun")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRuns?.invoke()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "agent_runs"
        private const val STOP = "com.yukisoffd.lyracode.STOP_AGENT_RUN"
        private const val NOTIFICATION_ID = 8793

        /**
         * The scope that owns conversation jobs. It lives for the process, not the service, so
         * the system stopping the service does not silently cancel the run's coroutines; the
         * service exists to keep the process foreground while they run.
         */
        val runScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** Set by [com.yukisoffd.lyracode.ChatController]; the notification's stop action calls it. */
        internal var stopRuns: (() -> Unit)? = null

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, AgentRunForegroundService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentRunForegroundService::class.java)) }
        }
    }
}
