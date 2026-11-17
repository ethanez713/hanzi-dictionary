package com.hanzidictionary.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.MainActivity
import com.hanzidictionary.app.R
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.StudyRepository
import com.hanzidictionary.core.WidgetCardInfo
import com.hanzidictionary.core.WidgetEvent
import com.hanzidictionary.core.WidgetState
import com.hanzidictionary.core.nextWidgetState
import com.hanzidictionary.core.widgetView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Home-screen flashcard (spec §17).
 *
 * Launchers hand widgets taps and nothing else — no gestures — so the app's swipe grammar
 * becomes tap zones: the left gutter is Again, the right gutter is Good, the same two verbs
 * the practice screen's left/right swipes grade with. Tapping the card in the middle **flips
 * it** to reveal the pinyin and definition in place; tapping the revealed card opens the app
 * on that exact card. Grading stays on the gutters only, and archiving an item for good is a
 * decision that deserves the full page, not a home-screen mis-tap.
 *
 * The deck follows the app's **active list**. The card shown is picked semi-randomly —
 * never-seen cards first, otherwise a random due review — so the widget doesn't march a
 * fixed order. All of that (which card, whether it's flipped, what each line reads) is the
 * pure state machine in `core` ([WidgetState] / [nextWidgetState] / [widgetView]); this
 * class only does the Android side: prefs, RemoteViews, and the FSRS grade write.
 *
 * Every tap that touches the database runs in the receiver under `goAsync()`, on IO: the
 * content pack was already unpacked at first launch, so a cold process only opens SQLite.
 */
class PracticeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // One goAsync() for the whole batch: it hands out its PendingResult once, so calling
        // it per widget gave the second widget a null and crashed the app on every refresh
        // once two widgets were placed.
        applyAsync(context, appWidgetIds.toList(), WidgetEvent.Update, gradeRating = null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_ID)
        if (widgetId == INVALID_ID) return
        when (intent.action) {
            ACTION_FLIP -> {
                // A flip needs neither the deck nor the database, so it toggles state in
                // memory and redraws inline rather than paying for a goAsync coroutine.
                val prefs = prefs(context)
                val next = nextWidgetState(loadState(prefs, widgetId), WidgetEvent.Flip, emptyList(), { null })
                val editor = prefs.edit()
                saveState(editor, widgetId, next)
                editor.apply()
                render(context, widgetId, next)
            }
            ACTION_GRADE -> {
                val rating = intent.getStringExtra(EXTRA_RATING)
                    ?.let { runCatching { Rating.valueOf(it) }.getOrNull() }
                    ?: return
                // The card the gutter was drawn beside. A tap that arrives after the widget has
                // moved on (a double tap, or an app poke that re-dealt) would otherwise grade a
                // card the user never saw.
                val shown = intent.getStringExtra(EXTRA_ITEM)
                if (shown != null && shown != loadState(prefs(context), widgetId).card?.item?.itemId) return
                applyAsync(context, listOf(widgetId), WidgetEvent.Grade, rating)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        for (id in appWidgetIds) {
            for (field in FIELDS) editor.remove(key(id, field))
        }
        editor.apply()
    }

    /**
     * Runs a deck-touching [event] off the main thread. For a grade, applies [gradeRating]
     * to the current card first, then lets the pure state machine choose the next card and
     * redraws. `goAsync` keeps the receiver alive across the SQLite work — well inside the
     * ~10s a broadcast receiver is allowed.
     */
    private fun applyAsync(context: Context, widgetIds: List<Int>, event: WidgetEvent, gradeRating: Rating?) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Serialised: two taps' coroutines interleaving could grade one card twice.
                gradeLock.withLock {
                    for (widgetId in widgetIds) apply(appContext as HanziApp, widgetId, event, gradeRating)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun apply(app: HanziApp, widgetId: Int, event: WidgetEvent, gradeRating: Rating?) {
        val prefs = prefs(app)
        val prev = loadState(prefs, widgetId)

        val gradedCard = prev.card
        if (event == WidgetEvent.Grade && gradeRating != null && gradedCard != null &&
            !app.userRepository.isKnown(gradedCard.item.itemId)
        ) {
            // Not if it was archived since it was dealt: a grade would rebuild a schedule for
            // an archived item and overwrite the review row its archive memory is read from.
            app.studyRepository.grade(gradedCard.item, gradeRating, StudyRepository.ORIGIN_WIDGET)
        }

        val activeId = app.studyRepository.activeListId()
        val source = if (activeId != null) DeckSource.UserList(activeId) else DeckSource.Everything
        val deck = app.studyRepository.deckFor(source, limit = 12)
        val next = nextWidgetState(prev, event, deck, { cardInfo(app, it) })

        val editor = prefs.edit()
        saveState(editor, widgetId, next)
        // Committed synchronously: render() reads nothing back, but the receiver may
        // be torn down the moment goAsync() finishes, so the write must land first.
        editor.commit()
        render(app, widgetId, next)
    }

    companion object {
        private const val ACTION_GRADE = "com.hanzidictionary.app.widget.GRADE"
        private const val ACTION_FLIP = "com.hanzidictionary.app.widget.FLIP"
        private const val EXTRA_RATING = "rating"
        private const val EXTRA_ITEM = "item"
        private val gradeLock = Mutex()
        private const val INVALID_ID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val PREFS = "practice_widget"
        private val FIELDS = listOf("item", "kind", "front", "answer", "revealed")

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun key(widgetId: Int, field: String) = "w$widgetId.$field"

        /** Reads the persisted [WidgetState] back out of prefs. */
        private fun loadState(prefs: SharedPreferences, widgetId: Int): WidgetState {
            val itemId = prefs.getString(key(widgetId, "item"), null)
            val front = prefs.getString(key(widgetId, "front"), null)
            val card = if (itemId != null && front != null) {
                val kind = prefs.getString(key(widgetId, "kind"), StudyItem.KIND_CHARACTER)!!
                WidgetCardInfo(
                    StudyItem(itemId, kind),
                    front,
                    prefs.getString(key(widgetId, "answer"), "").orEmpty(),
                )
            } else {
                null
            }
            return WidgetState(card, prefs.getBoolean(key(widgetId, "revealed"), false))
        }

        /** Persists a [WidgetState]; an empty state clears the whole record. */
        private fun saveState(editor: SharedPreferences.Editor, widgetId: Int, state: WidgetState) {
            val card = state.card
            if (card == null) {
                for (field in FIELDS) editor.remove(key(widgetId, field))
            } else {
                editor.putString(key(widgetId, "item"), card.item.itemId)
                editor.putString(key(widgetId, "kind"), card.item.kind)
                editor.putString(key(widgetId, "front"), card.front)
                editor.putString(key(widgetId, "answer"), card.answer)
                editor.putBoolean(key(widgetId, "revealed"), state.revealed)
            }
        }

        private fun render(context: Context, widgetId: Int, state: WidgetState) {
            val view = widgetView(state)
            val views = RemoteViews(context.packageName, R.layout.widget_practice)
            views.setTextViewText(R.id.widget_card, view.glyph)
            views.setTextViewText(R.id.widget_echo, view.detail)

            val shown = state.card?.item?.itemId
            views.setOnClickPendingIntent(R.id.zone_miss, gradeIntent(context, widgetId, Rating.AGAIN, shown))
            views.setOnClickPendingIntent(R.id.zone_knew, gradeIntent(context, widgetId, Rating.GOOD, shown))
            // Face-down → the middle flips the card; revealed or empty → it opens the app.
            val cardIntent =
                if (view.cardTapOpensApp) openIntent(context, widgetId, state.card?.item)
                else flipIntent(context, widgetId)
            views.setOnClickPendingIntent(R.id.zone_card, cardIntent)
            AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
        }

        private fun flipIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, PracticeWidgetProvider::class.java).apply {
                action = ACTION_FLIP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // Distinct data URI so this doesn't collide with the grade/open intents.
                data = android.net.Uri.parse("hanzi://widget/$widgetId/flip")
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun gradeIntent(context: Context, widgetId: Int, rating: Rating, item: String?): PendingIntent {
            val intent = Intent(context, PracticeWidgetProvider::class.java).apply {
                action = ACTION_GRADE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_RATING, rating.name)
                // The card this gutter was drawn beside, checked on arrival.
                item?.let { putExtra(EXTRA_ITEM, it) }
                // Extras alone don't distinguish PendingIntents; the data URI does — one per
                // grade *and card*, so FLAG_UPDATE_CURRENT can't rewrite the item on an intent
                // a tap already holds.
                data = android.net.Uri.parse("hanzi://widget/$widgetId/grade/${rating.name}/${android.net.Uri.encode(item.orEmpty())}")
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openIntent(context: Context, widgetId: Int, item: StudyItem?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (item != null) {
                    putExtra(EXTRA_PRACTICE_ITEM, item.itemId)
                    putExtra(EXTRA_PRACTICE_KIND, item.kind)
                }
                data = android.net.Uri.parse("hanzi://widget/$widgetId/open/${item?.itemId.orEmpty()}")
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Item the user tapped on the widget. The app opens its detail page and makes it the
         * pinned card, so the study surfaces resume on the card you tapped. */
        const val EXTRA_PRACTICE_ITEM = "practice_item"
        const val EXTRA_PRACTICE_KIND = "practice_kind"

        /** Whether the hand-off also opens the item's lookup sheet over the card. Absent means
         * yes (the widget); the reminder notification sends false and lands on the revealed
         * flashcard alone. */
        const val EXTRA_PRACTICE_SHEET = "practice_sheet"
    }
}

/** Lets the app redraw its widgets after anything that changes what's due. */
object PracticeWidgetUpdater {
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context.applicationContext, PracticeWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        context.applicationContext.sendBroadcast(
            Intent(context.applicationContext, PracticeWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            },
        )
    }
}
