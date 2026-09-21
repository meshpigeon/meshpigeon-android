package app.meshpigeon.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Notification plumbing (07 §8): storm control (≥3 s between posts, bursts
 * collapse into a batch summary), deep links straight to the conversation,
 * calm default channel.
 */
object RadioNotifications {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_CONNECTION = "connection"
    const val ID_CONNECTION = 1
    const val ID_BATCH = 2
    private const val MIN_INTERVAL_MS = 3_000L

    private var lastPostAt = 0L
    private val batch = mutableMapOf<String, Int>() // title → count since last post

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages and contact requests"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Radio connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent connection status"
                setShowBadge(false)
            },
        )
    }

    fun post(context: Context, notification: app.meshpigeon.domain.ReceivePipeline.Notification) {
        ensureChannels(context)
        val now = System.currentTimeMillis()
        batch[notification.title] = (batch[notification.title] ?: 0) + 1
        if (now - lastPostAt < MIN_INTERVAL_MS) return // storm control: collapsed

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        lastPostAt = now
        val (title, body) = if (batch.size > 1 || batch.values.sum() > 1) {
            "${batch.values.sum()} new messages" to batch.entries.joinToString { "${it.key} ×${it.value}" }
        } else {
            // message requests are labeled so strangers never look like friends
            val shownTitle = if (notification.isRequest) "Request · ${notification.title}" else notification.title
            shownTitle to notification.body
        }
        batch.clear()
        // tap navigates directly to the conversation (never to the app root)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_CONVERSATION, notification.conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, notification.conversationId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification) // 🕊️ silhouette (brand)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(ID_BATCH, n)
    }

    /** The calm persistent connection notification (required for foreground). */
    fun connection(context: Context, text: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MeshPigeon")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    const val EXTRA_CONVERSATION = "app.meshpigeon.CONVERSATION_ID"
}
