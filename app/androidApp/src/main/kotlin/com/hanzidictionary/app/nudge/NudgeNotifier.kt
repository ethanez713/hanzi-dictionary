package com.hanzidictionary.app.nudge

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hanzidictionary.app.MainActivity
import com.hanzidictionary.app.R
import com.hanzidictionary.app.widget.PracticeWidgetProvider
import com.hanzidictionary.core.NudgeGraded
import com.hanzidictionary.core.NudgeSession
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyItem

/**
 * Builds and posts the study-reminder notification — the practice screen in miniature, in its
 * own palette and type (see [NudgeArt] for why the text is drawn rather than set).
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
    // Channel importance/vibration are frozen once a channel exists, so silencing the reminder
    // needed a new id; the old one (IMPORTANCE_DEFAULT, buzzed on every hourly post) is deleted.
    const val CHANNEL_ID = "study_reminders_silent"
    private const val LEGACY_CHANNEL_ID = "study_reminders"
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
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        // LOW = shown in the shade with no sound, vibration or heads-up popup; the explicit
        // flags keep it silent even if a device's defaults for a new channel differ.
        val channel = NotificationChannel(CHANNEL_ID, "Study reminders", NotificationManager.IMPORTANCE_LOW).apply {
            description = "A flashcard you can grade straight from the notification."
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /**
     * Posts (or replaces) the reminder for [session]. The channel is silent, so a re-post never
     * alerts.
     */
    @SuppressLint("MissingPermission") // guarded by canPost(); the permission itself is requested from Settings
    fun post(context: Context, title: String, session: NudgeSession) {
        if (!canPost(context)) return
        ensureChannel(context)
        val card = session.card
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_study)
            // The app's accent on the shade header (icon and app name), as on its own chrome.
            .setColor(ContextCompat.getColor(context, R.color.nudge_accent))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
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
            val art = NudgeArt(context)
            builder
                .setContentTitle(title)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsed(context, art, card.item, card.front))
                .setCustomBigContentView(expanded(context, art, card.item, card.front, title, session.previous))
                .setContentIntent(openIntent(context, card.item))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    /** The one-row strip: marks only, glyph alone — the practice gutters with hints off. */
    private fun collapsed(context: Context, art: NudgeArt, item: StudyItem, front: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_card_small)
        val cardWidth = cardWidthDp(art) - 8f
        views.setArt(context, R.id.nudge_glyph, art.glyph(front, cardWidth, 40f, maxSp = 30f), R.color.nudge_ink, front)
        views.setArt(context, R.id.zone_miss_mark, art.line("✗", R.font.ibm_plex_mono_regular, 16f, 40f), R.color.nudge_amber_ink)
        views.setArt(context, R.id.zone_knew_mark, art.line("✓", R.font.ibm_plex_mono_regular, 16f, 40f), R.color.nudge_jade_ink)
        bindTaps(context, views, item)
        return views
    }

    /**
     * The practice screen in miniature (see notification_card.xml). Type follows the theme's
     * roles: Piazzolla for the headline, Noto CJK for anything Chinese, Plex Mono for the gutter
     * marks and their tracked labels, Instrument Sans for prose.
     */
    private fun expanded(
        context: Context,
        art: NudgeArt,
        item: StudyItem,
        front: String,
        title: String,
        previous: NudgeGraded?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_card)
        val width = art.contentWidthDp
        views.setArt(context, R.id.nudge_title, art.line(title, R.font.piazzolla_semibold, 16f, width), R.color.nudge_ink, title)

        if (previous != null) {
            views.setViewVisibility(R.id.nudge_previous, View.VISIBLE)
            views.setArt(
                context, R.id.nudge_previous_front,
                art.line(previous.front, R.font.noto_sans_cjk_sc, 15f, width * 0.25f), R.color.nudge_ink, previous.front,
            )
            views.setArt(
                context, R.id.nudge_previous_answer,
                art.line(previous.answer, R.font.instrument_sans_regular, 13f, width * 0.55f), R.color.nudge_text_muted, previous.answer,
            )
            views.setArt(
                context, R.id.nudge_previous_mark,
                art.line(if (previous.knew) "✓" else "✗", R.font.ibm_plex_mono_regular, 13f, 24f),
                if (previous.knew) R.color.nudge_jade_ink else R.color.nudge_amber_ink,
                if (previous.knew) "knew it" else "didn't know it",
            )
        }

        // The card stays face-down, as the practice card's front does: "Tap to reveal" opens it
        // in the app with the answer showing. Only the card just graded is shown answered.
        val cardWidth = cardWidthDp(art) - 16f
        views.setArt(context, R.id.nudge_glyph, art.glyph(front, cardWidth, 94f, maxSp = 72f), R.color.nudge_ink, front)
        views.setArt(context, R.id.nudge_hint, art.line("Tap to reveal", R.font.instrument_sans_regular, 12f, cardWidth), R.color.nudge_text_faint)

        val gutter = width / 5f
        views.setArt(context, R.id.zone_miss_mark, art.line("✗", R.font.ibm_plex_mono_regular, 18f, gutter), R.color.nudge_amber_ink)
        views.setArt(context, R.id.zone_miss_label, art.line("AGAIN", R.font.ibm_plex_mono_regular, 10f, gutter, 0.075f), R.color.nudge_amber_ink)
        views.setArt(context, R.id.zone_knew_mark, art.line("✓", R.font.ibm_plex_mono_regular, 18f, gutter), R.color.nudge_jade_ink)
        views.setArt(context, R.id.zone_knew_label, art.line("KNEW", R.font.ibm_plex_mono_regular, 10f, gutter, 0.075f), R.color.nudge_jade_ink)

        bindTaps(context, views, item)
        return views
    }

    /** The card's share of the row: gutters 1 : card 3 : gutter 1, less the card's margins. */
    private fun cardWidthDp(art: NudgeArt) = (art.contentWidthDp - 12f) * 3f / 5f

    private fun bindTaps(context: Context, views: RemoteViews, item: StudyItem) {
        views.setOnClickPendingIntent(R.id.zone_miss, gradeIntent(context, Rating.AGAIN, item))
        views.setOnClickPendingIntent(R.id.zone_knew, gradeIntent(context, Rating.GOOD, item))
        views.setOnClickPendingIntent(R.id.zone_card, openIntent(context, item))
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
     * `MainActivity` path — so the card is pinned and shown revealed; `EXTRA_PRACTICE_SHEET =
     * false` keeps the lookup sheet from opening over it. A direct activity PendingIntent, never
     * a trampoline: Android 12+ blocks starting an activity from a notification-tap receiver or
     * service.
     */
    private fun openIntent(context: Context, item: StudyItem?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (item != null) {
                putExtra(PracticeWidgetProvider.EXTRA_PRACTICE_ITEM, item.itemId)
                putExtra(PracticeWidgetProvider.EXTRA_PRACTICE_KIND, item.kind)
                // The card's brief answer is what a reminder tap is for, not the full page.
                putExtra(PracticeWidgetProvider.EXTRA_PRACTICE_SHEET, false)
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
