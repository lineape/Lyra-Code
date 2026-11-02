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
import android.util.Log
import androidx.core.content.ContextCompat
import com.yukisoffd.lyracode.MainActivity
import com.yukisoffd.lyracode.R

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
            // A run can wait indefinitely on a tool approval or a user question. A bounded
            // acquire keeps a forgotten service from holding the CPU awake forever; a run that
            // outlives it still runs, only without the wake lock.
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        // A stop can arrive between startForegroundService and this call. The system then sees
        // a service brought down before it ever reached startForeground and kills the process
        // with "did not then call Service.startForeground()". Reach startForeground first, then
        // honour the deferred stop.
        if (lifecycle.onForegroundStarted()) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopRuns?.invoke()
            if (lifecycle.onStopRequested() == StopDecision.STOP_NOW) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lifecycle.onDestroyed()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AgentRunService"
        private const val CHANNEL = "agent_runs"
        private const val STOP = "com.yukisoffd.lyracode.STOP_AGENT_RUN"
        private const val NOTIFICATION_ID = 8793
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L

        /** The start-foreground/stop ordering; a separate object so a unit test can drive it. */
        internal val lifecycle = AgentRunForegroundLifecycle()

        private var stopRunsOwner: Any? = null

        /** Set by [com.yukisoffd.lyracode.ChatController]; the notification's stop action calls it. */
        internal var stopRuns: (() -> Unit)? = null
            private set

        fun bindStopRuns(owner: Any, handler: () -> Unit) {
            stopRunsOwner = owner
            stopRuns = handler
        }

        fun unbindStopRuns(owner: Any) {
            if (stopRunsOwner === owner) {
                stopRunsOwner = null
                stopRuns = null
            }
        }

        fun start(context: Context) {
            lifecycle.onStartRequested()
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentRunForegroundService::class.java)) }
                .onFailure { error ->
                    // Android 12 and later can refuse a background start. The run still goes
                    // ahead without foreground priority; say so instead of failing silently.
                    Log.w(TAG, "Foreground service start was blocked; the run continues unprotected", error)
                }
        }

        fun stop(context: Context) {
            when (lifecycle.onStopRequested()) {
                StopDecision.STOP_NOW -> runCatching { context.stopService(Intent(context, AgentRunForegroundService::class.java)) }
                    .onFailure { error -> Log.w(TAG, "Stopping the foreground service failed", error) }
                StopDecision.DEFER, StopDecision.IGNORE -> Unit
            }
        }
    }
}

/** What a stop request may do right now. */
internal enum class StopDecision {
    /** The service is foreground; stop it. */
    STOP_NOW,

    /** The service has not reached startForeground yet; onCreate stops it afterwards. */
    DEFER,

    /** Nothing is running, or the stop was already served. */
    IGNORE,
}

/**
 * Orders `startForegroundService`, `startForeground` and `stopService` so a run that ends
 * before `onCreate` reaches `startForeground` cannot crash the process.
 *
 * Pure and main-thread only. [AgentRunForegroundService] calls it from `onCreate`,
 * `onStartCommand` and `onDestroy`; a unit test drives the same transitions.
 */
internal class AgentRunForegroundLifecycle {
    private var startRequested = false
    private var foregroundStarted = false
    private var stopPending = false

    fun onStartRequested() {
        startRequested = true
        // A start after a deferred stop belongs to a newer run, so the old stop is stale. This
        // also covers a blocked start that never created the service: its stop must not bring
        // down the next service.
        stopPending = false
    }

    fun onStopRequested(): StopDecision {
        if (!startRequested) return StopDecision.IGNORE
        startRequested = false
        if (foregroundStarted) return StopDecision.STOP_NOW
        stopPending = true
        return StopDecision.DEFER
    }

    /** Returns true when the stop is pending, so the caller must stop right after [startForeground]. */
    fun onForegroundStarted(): Boolean {
        foregroundStarted = true
        return stopPending.also { stopPending = false }
    }

    fun onDestroyed() {
        startRequested = false
        foregroundStarted = false
        stopPending = false
    }
}
