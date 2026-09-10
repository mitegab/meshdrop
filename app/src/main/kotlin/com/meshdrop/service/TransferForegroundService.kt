package com.meshdrop.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meshdrop.MeshDropApplication
import com.meshdrop.R
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.util.Format
import com.meshdrop.core.util.MeshLog
import com.meshdrop.notifications.Notifications
import com.meshdrop.notifications.Notifications.canPostNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps MeshDrop alive and in the foreground while we are waiting for, or handling, a
 * transfer — and is the place where the "someone wants to send you files" notification with
 * its Accept/Reject buttons is produced.
 *
 * The sockets themselves live in the application scope (see [com.meshdrop.transfer.TransferEngine]),
 * so this service only provides priority + notifications.
 */
class TransferForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val container get() = MeshDropApplication.container()
    private val controller get() = container?.meshController

    /** Batches we currently show an incoming notification for. */
    private val postedRequests = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT -> controller?.approve(intent.batchId())
            ACTION_REJECT -> controller?.reject(intent.batchId())
            ACTION_STOP -> stop()
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        startForeground()
        observeIncomingRequests()
        observeTransfers()
    }

    private fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        postedRequests.forEach { Notifications.cancel(this, Notifications.incomingId(it)) }
        postedRequests.clear()
        Notifications.cancel(this, Notifications.ID_PROGRESS)
        stopSelf()
    }

    private fun startForeground() {
        val notification = Notifications.receivingNotification(
            this,
            controller?.deviceName?.value ?: getString(R.string.app_name),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_RECEIVING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ID_RECEIVING, notification)
        }
    }

    private fun observeIncomingRequests() {
        val controller = controller ?: return
        scope.launch {
            controller.incoming.collect { requests ->
                syncIncomingNotifications(requests)
            }
        }
    }

    private fun syncIncomingNotifications(requests: List<IncomingRequest>) {
        if (!canPostNotifications(this)) return
        val current = requests.map { it.batchId }.toSet()

        (postedRequests - current).forEach { batchId ->
            Notifications.cancel(this, Notifications.incomingId(batchId))
        }
        postedRequests.retainAll(current)

        requests.forEach { request ->
            if (postedRequests.add(request.batchId)) {
                postIncomingNotification(request)
            }
        }
    }

    private fun postIncomingNotification(request: IncomingRequest) {
        val notificationId = Notifications.incomingId(request.batchId)
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_meshdrop)
            .setContentTitle(
                getString(R.string.notification_incoming_title, request.senderName),
            )
            .setContentText(
                getString(
                    R.string.notification_incoming_text,
                    request.files.size,
                    Format.bytes(request.totalBytes),
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    request.files.joinToString("\n") { "${it.name} · ${Format.bytes(it.sizeBytes)}" },
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.notification_action_reject), decisionIntent(ACTION_REJECT, request.batchId))
            .addAction(0, getString(R.string.notification_action_accept), decisionIntent(ACTION_ACCEPT, request.batchId))
            .build()

        runCatching { NotificationManagerCompat.from(this).notify(notificationId, notification) }
            .onFailure { MeshLog.w(TAG, "Could not post incoming notification", it) }
    }

    private fun observeTransfers() {
        val controller = controller ?: return
        scope.launch {
            controller.jobs.collect { jobs ->
                val active = jobs.filter { it.isActive }
                if (active.isEmpty()) {
                    Notifications.cancel(this@TransferForegroundService, Notifications.ID_PROGRESS)
                } else {
                    updateProgressNotification(active.first())
                }
            }
        }
    }

    private fun updateProgressNotification(job: TransferJob) {
        val percent = Format.percent(job.bytesTransferred, job.totalBytes)
        val verb = if (job.direction == TransferDirection.SEND) "Sending to" else "Receiving from"
        val notification = Notifications.progressNotification(
            context = this,
            title = getString(R.string.notification_transfer_title, job.files.size),
            text = "$verb ${job.peerName} · ${Format.bytes(job.bytesTransferred)} of ${Format.bytes(job.totalBytes)}",
            progress = percent,
            indeterminate = job.bytesPerSecond <= 0L && job.bytesTransferred == 0L,
        )
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_PROGRESS, notification) }
            .onFailure { MeshLog.w(TAG, "Could not update progress notification", it) }
    }

    private fun decisionIntent(action: String, batchId: String): PendingIntent {
        val intent = Intent(this, TransferForegroundService::class.java)
            .setAction(action)
            .putExtra(EXTRA_BATCH_ID, batchId)
        return PendingIntent.getService(
            this,
            Notifications.incomingId(batchId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun Intent.batchId(): String = getStringExtra(EXTRA_BATCH_ID).orEmpty()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "TransferService"

        private const val ACTION_ACCEPT = "com.meshdrop.action.ACCEPT"
        private const val ACTION_REJECT = "com.meshdrop.action.REJECT"
        private const val ACTION_STOP = "com.meshdrop.action.STOP"
        const val EXTRA_BATCH_ID = "extra_batch_id"

        fun start(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { MeshLog.w(TAG, "Could not start the foreground service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { MeshLog.v(TAG, "Service was not running") }
        }
    }
}
