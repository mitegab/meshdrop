package com.meshdrop.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.meshdrop.R
import com.meshdrop.core.util.MeshLog

/**
 * Notification plumbing: channels, IDs and the two notifications MeshDrop shows.
 */
object Notifications {

    const val CHANNEL_TRANSFERS = "meshdrop_transfers"
    const val CHANNEL_INCOMING = "meshdrop_incoming"

    const val ID_RECEIVING = 1001
    const val ID_PROGRESS = 1002

    /** Incoming-request notifications are keyed by batch id so several can coexist. */
    fun incomingId(batchId: String): Int = (batchId.hashCode() and 0x0FFFFFFF) + 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return

        val transfers = NotificationChannel(
            CHANNEL_TRANSFERS,
            context.getString(R.string.transfer_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.transfer_channel_description)
            setSound(null, null)
        }

        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            context.getString(R.string.incoming_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.incoming_channel_description)
        }

        manager.createNotificationChannel(transfers)
        manager.createNotificationChannel(incoming)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** The persistent "waiting for files" notification shown while receiving is on. */
    fun receivingNotification(context: Context, deviceName: String): android.app.Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_meshdrop)
            .setContentTitle(context.getString(R.string.notification_receiving_title))
            .setContentText(context.getString(R.string.notification_receiving_text, deviceName))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .build()
    }

    fun progressNotification(
        context: Context,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean = false,
    ): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_meshdrop)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    fun cancel(context: Context, notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
            .onFailure { MeshLog.w(TAG, "Could not cancel notification $notificationId", it) }
    }

    private const val TAG = "Notifications"
}
