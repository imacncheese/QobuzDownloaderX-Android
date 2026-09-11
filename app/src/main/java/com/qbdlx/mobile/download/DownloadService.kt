package com.qbdlx.mobile.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.qbdlx.mobile.MainActivity
import com.qbdlx.mobile.R
import com.qbdlx.mobile.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps downloads alive while the app is backgrounded and surfaces progress in
 * the notification shade. Actual work is delegated to [DownloadEngine]; this
 * class only owns the worker coroutines and the foreground notification.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()
    private val workerCount = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            DownloadQueue.items.value
                .filter { !it.isTerminal }
                .forEach { DownloadQueue.cancel(it.item.id) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (running.compareAndSet(false, true)) {
            startForegroundCompat()
            observeQueue()
        }
        return START_STICKY
    }

    private fun observeQueue() {
        scope.launch {
            DownloadQueue.items.collectLatest { items ->
                val pending = items.count { !it.isTerminal }
                if (pending == 0 && workerCount.get() == 0) {
                    // Nothing left to do — drop the foreground notification.
                    stopForegroundCompat()
                    running.set(false)
                    stopSelf()
                } else {
                    updateNotification(items)
                    pump()
                }
            }
        }
    }

    /** Starts as many workers as settings allow, up to the pending item count. */
    private suspend fun pump() {
        startMutex.withLock {
            val target = AppGraph.settings.current.concurrentDownloads.coerceIn(1, 4)
            while (workerCount.get() < target) {
                val pending = DownloadQueue.items.value.count { it.status == DownloadStatus.QUEUED }
                if (pending == 0) break
                workerCount.incrementAndGet()
                scope.launch {
                    try {
                        workerLoop()
                    } finally {
                        workerCount.decrementAndGet()
                    }
                }
            }
        }
    }

    private suspend fun workerLoop() {
        val engine = AppGraph.downloadEngine ?: return
        while (true) {
            // claimNext is atomic, so two concurrent workers can never pick the
            // same queued track and download it twice.
            val next = DownloadQueue.claimNext() ?: break
            engine.process(next)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(items: List<DownloadState>) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(summarise(items))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, overallPercent(items), items.any { it.totalBytes <= 0 })
        .setContentIntent(openAppIntent())
        .addAction(
            0,
            getString(R.string.cancel_all),
            PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun summarise(items: List<DownloadState>): String {
        val active = items.count { !it.isTerminal }
        val done = items.count { it.status == DownloadStatus.COMPLETED }
        val failed = items.count { it.status == DownloadStatus.FAILED }
        val current = items.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        val sb = StringBuilder()
        if (current != null) {
            sb.append(current.item.title)
            if (current.progressPercent > 0) sb.append(" — ${current.progressPercent}%")
            if (current.speedBytesPerSecond > 0) {
                sb.append(" (").append(formatSpeed(current.speedBytesPerSecond)).append(")")
            }
            sb.append(" • ")
        }
        sb.append(getString(R.string.download_progress_summary, active, done, failed))
        return sb.toString()
    }

    private fun overallPercent(items: List<DownloadState>): Int {
        val active = items.filter { !it.isTerminal }
        if (active.isEmpty()) return 100
        return active.sumOf { it.progressPercent } / active.size
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun updateNotification(items: List<DownloadState>) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(items)) }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(DownloadQueue.items.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "qbdlx_downloads"
        const val NOTIFICATION_ID = 4711
        const val ACTION_CANCEL_ALL = "com.qbdlx.mobile.CANCEL_ALL"
        const val ACTION_START = "com.qbdlx.mobile.START_DOWNLOAD"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        fun formatSpeed(bytesPerSecond: Long): String = when {
            bytesPerSecond >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSecond / 1048576.0)
            bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }
}
