package com.hanzidictionary.app.nudge

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hanzidictionary.app.MainActivity
import com.hanzidictionary.app.R
import com.hanzidictionary.app.widget.PracticeWidgetProvider
import com.hanzidictionary.core.NudgeSession
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyItem

/**
 * Builds and posts the study-reminder notification — the home-screen widget, larger.
 *
 * Same verbs as the widget and the practice swipes, for the same reason: a notification (like
 * a widget) gets taps and no gestures, so Again and Good become two gutters either side of the
 * card, and tapping the card opens it in the app. The gutters grade in place via
 * [NudgeReceiver]; there is no Hard/Easy here, as everywhere else in the app.
 *
 * A custom RemoteViews body does the drawing, but it sits inside a
 * `DecoratedCustomViewStyle` so the system still supplies the small icon, app name and
 * timestamp — and gives the user the usual long-press → mute controls.
 */
internal object NudgeNotifier {
    const val CHANNEL_ID = "study_reminders"
    const val ACTION_GRADE = "com.hanzidictionary.app.nudge.GRADE"
    const val EXTRA_RATING = "rating"

    /** The card the gutter tap was drawn for. A tap on a stale notification (a second tap
     * landing before the re-post) carries the old id and is dropped rather than graded onto
     * the wrong card. */
    const val EXTRA_ITEM = "item"

    private const val NOTIFICATION_ID = 1001

    /** How long the closing "N reviewed" card lingers before clearing itself. */
    private const val DONE_TIMEOUT_MS = 8_000L

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A flashcard you can grade straight from the notification."
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /**
     * Posts (or replaces) the reminder for [session]. [quiet] suppresses the sound and
     * vibration on a re-post after a grade — the user is already looking at it.
     */
    @SuppressLint("MissingPermission") // guarded by canPost(); the permission itself is requested from Settings
    fun post(context: Context, title: String, session: NudgeSession, quiet: Boolean) {
        if (!canPost(context)) return
        ensureChannel(context)
        val card = session.card
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_study)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(quiet)
            .setAutoCancel(false)

        if (card == null) {
            // Session over: say how it went, keep the last answer, then get out of the way.
            val last = session.previous
            builder
                .setContentTitle(if (session.graded == 1) "1 card reviewed" else "${session.graded} cards reviewed")
                .setContentText(last?.let { "${it.front}  ${it.answer}" })
                .setContentIntent(openIntent(context, null))
                .setAutoCancel(true)
                .setTimeoutAfter(DONE_TIMEOUT_MS)
        } else {
            val previousLine = session.previous?.let { "${it.front}  ${it.answer}  ${if (it.knew) "✓" else "✗"}" }
            builder
                .setContentTitle(title)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(body(context, R.layout.notification_card_small, card.item, card.front, null))
                .setCustomBigContentView(body(context, R.layout.notification_card, card.item, card.front, previousLine))
                .setContentIntent(openIntent(context, card.item))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun body(
        context: Context,
        layout: Int,
        item: StudyItem,
        front: String,
        previousLine: String?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.nudge_glyph, front)
        // The card stays face-down: its answer lives behind the open-the-app tap, as a widget
        // card's does. Only the card you just graded is shown answered (previousLine).
        views.setTextViewText(R.id.nudge_echo, "Tap card for answer")
        if (previousLine != null) {
            views.setTextViewText(R.id.nudge_previous, previousLine)
            views.setViewVisibility(R.id.nudge_previous, android.view.View.VISIBLE)
        }
        views.setOnClickPendingIntent(R.id.zone_miss, gradeIntent(context, Rating.AGAIN, item))
        views.setOnClickPendingIntent(R.id.zone_knew, gradeIntent(context, Rating.GOOD, item))
        views.setOnClickPendingIntent(R.id.zone_card, openIntent(context, item))
        return views
    }

    private fun gradeIntent(context: Context, rating: Rating, item: StudyItem): PendingIntent {
        val intent = Intent(context, NudgeReceiver::class.java).apply {
            action = ACTION_GRADE
            putExtra(EXTRA_RATING, rating.name)
            putExtra(EXTRA_ITEM, item.itemId)
            // Extras don't distinguish PendingIntents; the data URI does — one per verb so the
            // two gutters don't collapse onto a single intent (same trick as the widget).
            data = Uri.parse("hanzi://nudge/grade/${rating.name}")
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Opens the app on [item] through the widget's own hand-off — same extras, same
     * `MainActivity` path — so the card is pinned and the lookup sheet opens over it with no new
     * routing. A direct activity PendingIntent, never a trampoline: Android 12+ blocks starting
     * an activity from a notification-tap receiver or service.
     */
    private fun openIntent(context: Context, item: StudyItem?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (item != null) {
                putExtra(PracticeWidgetProvider.EXTRA_PRACTICE_ITEM, item.itemId)
                putExtra(PracticeWidgetProvider.EXTRA_PRACTICE_KIND, item.kind)
            }
            data = Uri.parse("hanzi://nudge/open/${item?.itemId.orEmpty()}")
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
