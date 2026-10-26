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
import com.hanzidictionary.core.shouldStreakNudge
import com.hanzidictionary.core.startNudgeSession
import com.hanzidictionary.core.startStreakNudgeSession
import com.hanzidictionary.core.streakNudgeTitle
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The hourly tick behind the study reminder. It only *asks* — every rule about whether now is
 * a good moment (waking window, partial day, interval since a swipe-away, the evening streak
 * hold) is the pure [shouldNudge] / [shouldReshow] / [shouldStreakNudge] in `core`; this class
 * gathers their inputs and posts the result.
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
            // Nothing is owed, which is also when a streak reminder lives. The usual close
            // would dismiss it on the tick that posted it; it stays until a review lands today.
            if (store.open) {
                val held = heldStreakDays(app, store, now)
                if (held != null) {
                    if (deck.isNotEmpty() &&
                        !app.inForeground &&
                        !NudgeNotifier.isShowing(applicationContext) &&
                        shouldReshow(now, settings, store.dismissedAt)
                    ) {
                        NudgeScheduler.postFresh(app, deck, owed, streakDays = held)
                    }
                    return Result.success()
                }
                NudgeScheduler.close(applicationContext)
            }
            if (deck.isNotEmpty()) {
                val streak = app.studyRepository.streakState(now)
                if (shouldStreakNudge(now, settings, streak, store.lastNudgeAt) &&
                    NudgeScheduler.postFresh(app, deck, owed, streakDays = streak.current)
                ) {
                    store.openReminder(now, holdsStreak = true)
                }
            }
            return Result.success()
        }
        if (store.open) {
            if (app.inForeground || NudgeNotifier.isShowing(applicationContext)) return Result.success()
            if (shouldReshow(now, settings, store.dismissedAt)) NudgeScheduler.postFresh(app, deck, owed)
            return Result.success()
        }

        if (!shouldNudge(now, settings, app.studyRepository.lastReviewAt(), store.lastNudgeAt, hasDue = true)) {
            return Result.success()
        }
        if (NudgeScheduler.postFresh(app, deck, owed)) store.openReminder(now)
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

    /**
     * Deals a fresh session from [deck] and posts it. False if nothing could be posted.
     * [streakDays] selects the evening streak reminder: the top ahead card and the streak
     * title, instead of an owed card. An empty deck posts nothing.
     */
    internal fun postFresh(app: HanziApp, deck: List<DeckEntry>, owed: Int, streakDays: Int? = null): Boolean {
        val session = if (streakDays == null) {
            startNudgeSession(deck, { cardInfo(app, it) })
        } else {
            startStreakNudgeSession(deck, { cardInfo(app, it) })
        }
        if (session.card == null) return false
        val store = NudgeStore(app)
        val now = System.currentTimeMillis()
        val dayIndex = TimeUnit.MILLISECONDS.toDays(now + TimeZone.getDefault().getOffset(now))
        val title = if (streakDays == null) {
            nudgeTitle(dayIndex, owed)
        } else {
            streakNudgeTitle(dayIndex.toInt(), streakDays)
        }
        store.title = title
        store.saveSession(session)
        // streakDays != null is the evening reminder: its headline already names the run.
        NudgeNotifier.post(app, title, session, streakInTitle = streakDays != null)
        return true
    }

    /** The user is in the app now, so a notification asking them to open it is just noise.
     * The reminder stays open; [reshowOnLeave] brings it back. */
    fun hideForOpenApp(context: Context) {
        NudgeNotifier.cancel(context)
        NudgeStore(context).clearSession()
    }

    /** The app went to the background: if cards are still owed — or a streak reminder is still
     * waiting on today's review — put the reminder back (silently). */
    fun reshowOnLeave(app: HanziApp) {
        val settings = app.settings.nudgeSettings()
        val store = NudgeStore(app)
        if (!settings.enabled || !store.open || !NudgeNotifier.canPost(app)) return
        val now = System.currentTimeMillis()
        val deck = nudgeDeck(app)
        val owed = deck.count { it.bucket != DeckBucket.AHEAD }
        if (owed == 0) {
            val held = heldStreakDays(app, store, now)
            if (held != null) {
                if (!app.inForeground && shouldReshow(now, settings, store.dismissedAt)) {
                    postFresh(app, deck, owed, streakDays = held)
                }
                return
            }
            close(app)
            return
        }
        if (app.inForeground) return
        if (shouldReshow(now, settings, store.dismissedAt)) postFresh(app, deck, owed)
    }

    /** Nothing is owed (or reminders were switched off): clear the notification and its state. */
    fun close(context: Context) {
        NudgeNotifier.cancel(context)
        NudgeStore(context).closeReminder()
    }
}

/** Days still at risk on an open streak reminder, or null when the hold should end
 * (today is already studied, or the run is already gone). */
private fun heldStreakDays(app: HanziApp, store: NudgeStore, now: Long): Int? {
    if (!store.holdsStreak) return null
    val streak = app.studyRepository.streakState(now)
    if (streak.studiedToday || streak.current < 1) return null
    return streak.current
}
