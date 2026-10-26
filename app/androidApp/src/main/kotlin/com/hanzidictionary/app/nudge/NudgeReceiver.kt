package com.hanzidictionary.app.nudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.app.widget.cardInfo
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyRepository
import com.hanzidictionary.core.nextNudgeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles a ✗ / ✓ tap on the study notification: writes the FSRS grade (origin
 * `notification`), deals the next card with the pure [nextNudgeSession], and re-posts. Also
 * notes a swipe-away so the worker knows when to bring the reminder back.
 *
 * Same shape as the widget's grade path — `goAsync()` plus IO, since the content pack is
 * already unpacked and a cold process only has to open SQLite. Not exported: the only sender
 * is the notification's own PendingIntent.
 */
class NudgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NudgeNotifier.ACTION_GRADE -> onGrade(context, intent)
            NudgeNotifier.ACTION_DISMISS -> onDismiss(context)
        }
    }

    private fun onDismiss(context: Context) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                lock.withLock {
                    val store = NudgeStore(appContext)
                    if (store.open) {
                        store.dismissedAt = System.currentTimeMillis()
                        store.clearSession()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun onGrade(context: Context, intent: Intent) {
        val rating = intent.getStringExtra(NudgeNotifier.EXTRA_RATING)
            ?.let { runCatching { Rating.valueOf(it) }.getOrNull() }
            ?: return
        val tappedItem = intent.getStringExtra(NudgeNotifier.EXTRA_ITEM)

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Two quick taps would otherwise both read the same session and grade one
                // card twice; the second waits, then sees a different card and drops out.
                lock.withLock { grade(appContext, rating, tappedItem) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun grade(context: Context, rating: Rating, tappedItem: String?) {
        val app = context as HanziApp
        val store = NudgeStore(context)
        val session = store.loadSession() ?: return
        val card = session.card ?: return
        if (tappedItem != null && tappedItem != card.item.itemId) return

        app.studyRepository.grade(card.item, rating, StudyRepository.ORIGIN_NOTIFICATION)
        val next = nextNudgeSession(session, rating, nudgeDeck(app), { cardInfo(app, it) })
        // Nothing owed any more: the reminder is finished, not merely between cards.
        if (next.card == null) store.closeReminder()
        store.saveSession(next)
        PracticeWidgetUpdater.refresh(context)
        // The stored title is the one postFresh chose; holdsStreak says whether that
        // title already names the run, so a re-post doesn't repeat it underneath.
        NudgeNotifier.post(context, store.title, next, streakInTitle = store.holdsStreak)
    }

    private companion object {
        val lock = Mutex()
    }
}
