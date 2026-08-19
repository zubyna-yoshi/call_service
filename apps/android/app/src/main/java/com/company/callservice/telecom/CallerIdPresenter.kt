package com.company.callservice.telecom

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.company.callservice.R
import com.company.callservice.data.DirectoryEntry

object CallerIdPresenter {
    const val EXTRA_LABEL = "caller_label"
    const val EXTRA_NAME = "caller_name"
    const val EXTRA_ORGANIZATION = "caller_organization"
    const val EXTRA_NUMBER = "caller_number"
    const val EXTRA_NUMBER_TYPE = "caller_number_type"
    const val EXTRA_NOTIFICATION_ID = "caller_notification_id"

    private const val CHANNEL_ID = "company_caller_id"
    private const val NOTIFICATION_LIFETIME_MILLIS = 20_000L

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.caller_id_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.caller_id_channel_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts a heads-up/full-screen fallback and also asks Android to show the translucent activity.
     * Background-activity policy varies by OEM; the notification remains if direct launch is denied.
     */
    fun show(context: Context, entry: DirectoryEntry) {
        val notificationId = entry.phoneNumber.hashCode()
        val activityIntent = createActivityIntent(context, entry, notificationId)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        postFallbackNotification(context, entry, notificationId, pendingIntent)

        try {
            context.startActivity(activityIntent)
        } catch (_: RuntimeException) {
            // The notification is the supported fallback when an OEM blocks background launch.
        }
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun createActivityIntent(
        context: Context,
        entry: DirectoryEntry,
        notificationId: Int,
    ): Intent = Intent(context, CallerIdActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_HISTORY
        putExtra(EXTRA_LABEL, entry.displayLabel)
        putExtra(EXTRA_NAME, entry.name)
        putExtra(EXTRA_ORGANIZATION, entry.organization)
        putExtra(EXTRA_NUMBER, entry.phoneNumber)
        putExtra(EXTRA_NUMBER_TYPE, entry.numberType)
        putExtra(EXTRA_NOTIFICATION_ID, notificationId)
    }

    private fun postFallbackNotification(
        context: Context,
        entry: DirectoryEntry,
        notificationId: Int,
        pendingIntent: PendingIntent,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureNotificationChannel(context)
        val secondary = listOf(entry.organization, entry.name)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .ifBlank { entry.phoneNumber }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(entry.displayLabel)
            .setContentText(secondary)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_LIFETIME_MILLIS)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Runtime notification/full-screen permission can be revoked at any time.
        }
    }
}
