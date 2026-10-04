package com.hanzidictionary.app.nudge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.cardInfo
import com.hanzidictionary.core.DeckBucket
import com.hanzidictionary.core.DeckEntry
import com.hanzidictionary.core.nudgeTitle
import com.hanzidictionary.core.shouldNudge
import com.hanzidictionary.core.shouldReshow
import com.hanzidictionary.core.startNudgeSession
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The hourly tick behind the study reminder. It only *asks* — every rule about whether now is
 * a good moment (waking window, partial day, interval since a swipe-away) is the pure
 * [shouldNudge] / [shouldReshow] in `core`; this class gathers their inputs and posts the result.
 *
 * Hourly rather than scheduled-for-the-exact-minute on purpose: WorkManager periodic work is
 * deferrable and inexact, and "roughly the hour you usually study" is all the adaptive mode
 * promises. Hourly also lets both modes share one job.
 */
class NudgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as HanziApp
        val settings = app.settings.nudgeSettings()
        if (!settings.enabled || !NudgeNotifier.canPost(applicationContext)) return Result.success()

        val now = System.currentTimeMillis()
        val store = NudgeStore(applicationContext)
        val deck = nudgeDeck(app)
        // "Ahead" top-ups are practice nobody is owed; only due and never-seen cards count.
        val owed = deck.count { it.bucket != DeckBucket.AHEAD }

        if (owed == 0) {
            if (store.open) NudgeScheduler.close(applicationContext)
            return Result.success()
        }
        if (store.open) {
            if (app.inForeground || NudgeNotifier.isShowing(applicationContext)) return Result.success()
            if (shouldReshow(now, settings, store.dismissedAt)) NudgeScheduler.postFresh(app, deck, owed, quiet = false)
            return Result.success()
        }

        if (!shouldNudge(now, settings, app.studyRepository.lastReviewAt(), store.lastNudgeAt, hasDue = true)) {
            return Result.success()
        }
        if (NudgeScheduler.postFresh(app, deck, owed, quiet = false)) store.openReminder(now)
        return Result.success()
    }
}

/** Starts and stops the hourly job to match the Settings switch, and owns the reminder's
 * open → hidden → closed lifecycle around the app being in the foreground. */
object NudgeScheduler {
    private const val WORK_NAME = "study_nudge"

    /**
     * Idempotent: with KEEP, calling this on every app start re-arms the job if the system
     * dropped it (a force-stop clears WorkManager's queue) without resetting a healthy one.
     */
    fun sync(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (enabled) {
            work.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NudgeWorker>(1, TimeUnit.HOURS).build(),
            )
        } else {
            work.cancelUniqueWork(WORK_NAME)
            close(context)
        }
    }

    /** Deals a fresh session from [deck] and posts it. False if nothing could be posted. */
    internal fun postFresh(app: HanziApp, deck: List<DeckEntry>, owed: Int, quiet: Boolean): Boolean {
        val session = startNudgeSession(deck, { cardInfo(app, it) })
        if (session.card == null) return false
        val store = NudgeStore(app)
        val now = System.currentTimeMillis()
        val dayIndex = TimeUnit.MILLISECONDS.toDays(now + TimeZone.getDefault().getOffset(now))
        val title = nudgeTitle(dayIndex, owed)
        store.title = title
        store.saveSession(session)
        NudgeNotifier.post(app, title, session, quiet)
        return true
    }

    /** The user is in the app now, so a notification asking them to open it is just noise.
     * The reminder stays open; [reshowOnLeave] brings it back. */
    fun hideForOpenApp(context: Context) {
        NudgeNotifier.cancel(context)
        NudgeStore(context).clearSession()
    }

    /** The app went to the background: if cards are still owed, put the reminder back (silently)
     * rather than leaving the user with nothing to pull them back in. */
    fun reshowOnLeave(app: HanziApp) {
        val settings = app.settings.nudgeSettings()
        val store = NudgeStore(app)
        if (!settings.enabled || !store.open || !NudgeNotifier.canPost(app)) return
        val deck = nudgeDeck(app)
        val owed = deck.count { it.bucket != DeckBucket.AHEAD }
        when {
            owed == 0 -> close(app)
            app.inForeground -> Unit
            shouldReshow(System.currentTimeMillis(), settings, store.dismissedAt) -> postFresh(app, deck, owed, quiet = true)
        }
    }

    /** Nothing is owed (or reminders were switched off): clear the notification and its state. */
    fun close(context: Context) {
        NudgeNotifier.cancel(context)
        NudgeStore(context).closeReminder()
    }
}
