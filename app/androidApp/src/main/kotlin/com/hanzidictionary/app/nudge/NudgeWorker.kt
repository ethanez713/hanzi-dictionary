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
import com.hanzidictionary.core.nextIgnoredStreak
import com.hanzidictionary.core.nudgeTitle
import com.hanzidictionary.core.shouldNudge
import com.hanzidictionary.core.startNudgeSession
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The hourly tick behind the study reminder. It only *asks* — every rule about whether now is
 * a good moment (waking window, already studied today, back-off after ignored nudges) is the
 * pure [shouldNudge] in `core`; this class gathers its inputs and posts the result.
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

        val lastReview = app.studyRepository.lastReviewAt()
        val lastNudge = store.lastNudgeAt
        // Folded in only when we actually post: hourly ticks that decline to post must not each
        // count the same unanswered nudge again.
        val ignored = nextIgnoredStreak(store.ignoredStreak, lastNudge, lastReview)

        if (!shouldNudge(now, settings, lastReview, lastNudge, ignored, hasDue = owed > 0)) return Result.success()

        val session = startNudgeSession(deck, { cardInfo(app, it) })
        if (session.card == null) return Result.success()

        val dayIndex = TimeUnit.MILLISECONDS.toDays(now + TimeZone.getDefault().getOffset(now))
        val title = nudgeTitle(dayIndex, owed)
        store.title = title
        store.saveSession(session)
        store.lastNudgeAt = now
        store.ignoredStreak = ignored
        NudgeNotifier.post(applicationContext, title, session)
        return Result.success()
    }
}

/** Starts and stops the hourly job to match the Settings switch. */
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
            NudgeNotifier.cancel(context)
            NudgeStore(context).clearSession()
        }
    }

    /** The user is in the app now, so a notification asking them to open it is just noise. */
    fun dismissForOpenApp(context: Context) {
        NudgeNotifier.cancel(context)
        NudgeStore(context).clearSession()
    }
}
