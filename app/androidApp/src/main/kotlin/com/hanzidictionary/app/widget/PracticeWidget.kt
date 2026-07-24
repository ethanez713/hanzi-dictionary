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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home-screen flashcard (spec §17).
 *
 * Launchers hand widgets taps and nothing else — no gestures — so the app's swipe grammar
 * becomes tap zones. Each side gutter is split into two stacked grades: the left "struggled"
 * gutter is Again (top) / Hard (bottom), the right "recalled" gutter is Good (top) / Easy
 * (bottom) — the full four-grade FSRS scale in the same small footprint. Tapping the card in
 * the middle **flips it** to reveal the pinyin and definition in place; tapping the revealed
 * card opens the app on that exact card. Grading stays on the gutters only, and archiving an
 * item for good is a decision that deserves the full page, not a home-screen mis-tap.
 *
 * The deck follows the app's **active list**, so switching lists in the app re-points the
 * widget with no widget configuration of its own. The card shown is picked semi-randomly —
 * never-seen cards first (they need a first look), otherwise a random due review — so the
 * widget doesn't march the same fixed sequence every grade, mirroring the practice screen.
 *
 * Every tap runs in the receiver under `goAsync()`, on IO: the content pack was already
 * unpacked at first launch, so a cold process only has to open SQLite.
 */
class PracticeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) refreshAsync(context, id, gradedItem = null, rating = null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_ID)
        if (widgetId == INVALID_ID) return
        when (intent.action) {
            ACTION_FLIP -> {
                // A flip is a pure UI toggle with no database work, so it updates the pref
                // in memory and redraws inline rather than paying for a goAsync coroutine.
                prefs(context).edit().putBoolean(key(widgetId, "revealed"), true).apply()
                render(context, widgetId)
            }
            ACTION_GRADE -> {
                val rating = intent.getStringExtra(EXTRA_RATING)
                    ?.let { runCatching { Rating.valueOf(it) }.getOrNull() }
                    ?: return
                val prefs = prefs(context)
                val itemId = prefs.getString(key(widgetId, "item"), null) ?: return
                val kind = prefs.getString(key(widgetId, "kind"), StudyItem.KIND_CHARACTER)!!
                refreshAsync(context, widgetId, StudyItem(itemId, kind), rating)
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
     * Applies [rating] to [gradedItem] (when there is one), chooses the next card and
     * redraws. `goAsync` keeps the receiver alive across the database work — well inside
     * the ~10s a broadcast receiver is allowed.
     */
    private fun refreshAsync(
        context: Context,
        widgetId: Int,
        gradedItem: StudyItem?,
        rating: Rating?,
    ) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = appContext as HanziApp
                val prefs = prefs(appContext)
                var echo: String? = null
                if (gradedItem != null && rating != null) {
                    app.studyRepository.grade(gradedItem, rating, StudyRepository.ORIGIN_WIDGET)
                    // The answer was resolved when this card was drawn, so the feedback
                    // line costs no extra lookup.
                    val answer = prefs.getString(key(widgetId, "answer"), null)
                    // Again is the only lapse; Hard/Good/Easy are all passes, so the echo
                    // just reports pass vs miss (the exact grade lives in the schedule).
                    val mark = if (rating == Rating.AGAIN) "✗" else "✓"
                    echo = answer?.let { "$mark  $it" }
                }
                val prevId = prefs.getString(key(widgetId, "item"), null)
                val next = pickCard(
                    app,
                    avoid = gradedItem?.itemId,
                    keepCurrent = gradedItem == null,
                    currentId = prevId,
                )
                val editor = prefs.edit()
                if (next == null) {
                    for (field in FIELDS) editor.remove(key(widgetId, field))
                } else {
                    editor.putString(key(widgetId, "item"), next.item.itemId)
                    editor.putString(key(widgetId, "kind"), next.item.kind)
                    editor.putString(key(widgetId, "front"), next.front)
                    editor.putString(key(widgetId, "answer"), next.answer)
                    // A just-graded card, or any change of card, comes up face-down; a plain
                    // redraw of the same card (list edited, launcher restarted) keeps its
                    // flip state so an app-poked update can't un-reveal under your thumb.
                    if (gradedItem != null || next.item.itemId != prevId) {
                        editor.putBoolean(key(widgetId, "revealed"), false)
                    }
                }
                if (echo != null) editor.putString(key(widgetId, "echo"), echo)
                else editor.remove(key(widgetId, "echo"))
                // Committed synchronously: render() reads these back immediately, and the
                // receiver may be torn down as soon as goAsync() finishes.
                editor.commit()
                render(appContext, widgetId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_GRADE = "com.hanzidictionary.app.widget.GRADE"
        private const val ACTION_FLIP = "com.hanzidictionary.app.widget.FLIP"
        private const val EXTRA_RATING = "rating"
        private const val INVALID_ID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val PREFS = "practice_widget"
        private val FIELDS = listOf("item", "kind", "front", "answer", "echo", "revealed")

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun key(widgetId: Int, field: String) = "w$widgetId.$field"

        /** One card, front and back, resolved before it is ever drawn. */
        private data class WidgetCard(
            val item: StudyItem,
            val front: String,
            val answer: String,
        )

        /**
         * The next card from whatever list is active. [avoid] skips the card just graded:
         * a lapsed card becomes due again in a minute, which is right inside the app but
         * on a widget would just redraw the same character under your thumb.
         *
         * Selection is semi-random, mirroring the practice screen: a never-seen card wins
         * (a first look is the only way to gain any signal on it, and [buildDeck][
         * com.hanzidictionary.core.StudyRepository.buildDeck] already shuffled the new ones
         * to the front); otherwise pick a *random* due review rather than always the single
         * lowest-retrievability card, so the widget doesn't march the same fixed order.
         */
        private fun pickCard(
            app: HanziApp,
            avoid: String?,
            keepCurrent: Boolean,
            currentId: String?,
        ): WidgetCard? {
            val activeId = app.studyRepository.activeListId()
            val source = if (activeId != null) DeckSource.UserList(activeId) else DeckSource.Everything

            val deck = app.studyRepository.deckFor(source, limit = 12)
            if (deck.isEmpty()) return null
            val available = deck.filterNot { it.item.itemId == avoid }
            val entry = deck.firstOrNull { keepCurrent && it.item.itemId == currentId }
                // A never-graded card (card == null) keeps priority for signal.
                ?: available.firstOrNull { it.card == null }
                // Otherwise vary which review comes up instead of a fixed march.
                ?: available.randomOrNull()
                ?: deck.first()

            val summary = app.repository.itemSummaries(listOf(entry.item)).firstOrNull() ?: return null
            val answer = listOfNotNull(summary.pinyin, summary.gloss?.take(48))
                .joinToString(" · ")
                .ifBlank { summary.text }
            return WidgetCard(entry.item, summary.text, answer)
        }

        private fun render(context: Context, widgetId: Int) {
            val prefs = prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_practice)
            val front = prefs.getString(key(widgetId, "front"), null)
            val revealed = prefs.getBoolean(key(widgetId, "revealed"), false)

            if (front == null) {
                // Keep the last answer visible when the deck runs dry: the tap that
                // emptied it deserves its feedback too.
                val echo = prefs.getString(key(widgetId, "echo"), "").orEmpty()
                views.setTextViewText(R.id.widget_card, "字")
                views.setTextViewText(R.id.widget_echo, echo.ifBlank { "Tap to open" })
            } else {
                views.setTextViewText(R.id.widget_card, front)
                if (revealed) {
                    // Flipped: the bottom line becomes this card's own answer (pinyin +
                    // gloss), taking over from the one-card-late echo of the last grade.
                    views.setTextViewText(R.id.widget_echo, prefs.getString(key(widgetId, "answer"), "").orEmpty())
                } else {
                    val echo = prefs.getString(key(widgetId, "echo"), "").orEmpty()
                    views.setTextViewText(R.id.widget_echo, echo.ifBlank { "Tap to reveal" })
                }
            }

            views.setOnClickPendingIntent(R.id.zone_miss, gradeIntent(context, widgetId, Rating.AGAIN))
            views.setOnClickPendingIntent(R.id.zone_hard, gradeIntent(context, widgetId, Rating.HARD))
            views.setOnClickPendingIntent(R.id.zone_knew, gradeIntent(context, widgetId, Rating.GOOD))
            views.setOnClickPendingIntent(R.id.zone_easy, gradeIntent(context, widgetId, Rating.EASY))
            // First tap flips a face-down card in place; once it's revealed (or the deck is
            // empty) the middle opens the app on that card.
            val cardIntent =
                if (front != null && !revealed) flipIntent(context, widgetId)
                else openIntent(context, widgetId)
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

        private fun gradeIntent(context: Context, widgetId: Int, rating: Rating): PendingIntent {
            val intent = Intent(context, PracticeWidgetProvider::class.java).apply {
                action = ACTION_GRADE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(EXTRA_RATING, rating.name)
                // Extras alone don't distinguish PendingIntents; the data URI does — one per
                // grade so the four gutter zones don't collapse onto a single intent.
                data = android.net.Uri.parse("hanzi://widget/$widgetId/grade/${rating.name}")
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openIntent(context: Context, widgetId: Int): PendingIntent {
            val itemId = prefs(context).getString(key(widgetId, "item"), null)
            val kind = prefs(context).getString(key(widgetId, "kind"), StudyItem.KIND_CHARACTER)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (itemId != null) {
                    putExtra(EXTRA_PRACTICE_ITEM, itemId)
                    putExtra(EXTRA_PRACTICE_KIND, kind)
                }
                data = android.net.Uri.parse("hanzi://widget/$widgetId/open/${itemId.orEmpty()}")
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Item the user tapped on the widget, opened revealed in Practice. */
        const val EXTRA_PRACTICE_ITEM = "practice_item"
        const val EXTRA_PRACTICE_KIND = "practice_kind"
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
