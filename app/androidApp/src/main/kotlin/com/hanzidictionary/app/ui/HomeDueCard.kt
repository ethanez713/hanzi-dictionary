package com.hanzidictionary.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.PracticeState
import com.hanzidictionary.core.Rating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How deep a deck the home card pulls. It isn't a practice session — it's the top of the
 * pile, and the full session is one tap away — so this only needs to be deep enough that
 * grading a few cards in passing doesn't run it dry. */
private const val HOME_DECK_LIMIT = 20

/**
 * A live flashcard at the top of the home feed.
 *
 * The app's loop is photograph → file → review, and until this existed the home screen only
 * showed the first two steps: reviewing meant three taps through Study. This closes the loop
 * where you already are — the card is due, it's right there, and grading it counts exactly as
 * much as grading it in a practice session.
 *
 * It is the **same [PracticeCard]** the practice screen renders, with the same verbs: swipe
 * right knew it, swipe left didn't, tap reveals, tap again opens the full page, ⋮ archives.
 * The one gesture that doesn't survive the move is **swipe up to skip** — the card lives in a
 * scrolling feed, and a card that ate vertical drags would swallow the scroll. Scrolling past
 * a card you don't want to answer is the skip here, and it costs nothing, which is the same
 * bargain skip offered.
 *
 * The deck follows the **active list**, exactly like the home-screen widget, so all three
 * surfaces (widget, home card, Practice) agree about what's up next.
 */
@Composable
fun HomeDueCard(
    repository: HanziRepository,
    tts: MandarinTts,
    refreshKey: Int,
    onOpenCharacter: (String) -> Unit,
    onOpenWord: (String) -> Unit,
    onArchived: (String) -> Unit,
    onGraded: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(PracticeState(emptyList())) }
    var summaries by remember { mutableStateOf<Map<String, ItemSummary>>(emptyMap()) }
    var back by remember { mutableStateOf<CardBack?>(null) }

    // Rebuilt whenever the feed reloads (a find filed away, a list switched) so the card can't
    // go on offering something that was just archived.
    LaunchedEffect(refreshKey) {
        val built = withContext(Dispatchers.IO) {
            val activeId = app.studyRepository.activeListId()
            val source =
                if (activeId != null) DeckSource.UserList(activeId) else DeckSource.Everything
            val entries = app.studyRepository.deckFor(source, limit = HOME_DECK_LIMIT)
            val found = repository.itemSummaries(entries.map { it.item })
                .associateBy { it.item.itemId }
            entries.filter { it.item.itemId in found } to found
        }
        state = PracticeState(built.first)
        summaries = built.second
    }

    val current = state.current
    LaunchedEffect(current?.item) {
        back = current?.let { withContext(Dispatchers.IO) { loadBack(repository, it.item) } }
    }

    val entry = current ?: return
    val summary = summaries[entry.item.itemId]

    fun grade(rating: Rating) {
        scope.launch {
            withContext(Dispatchers.IO) { app.studyRepository.grade(entry.item, rating) }
            PracticeWidgetUpdater.refresh(context)
            // Re-counts what's due without rebuilding this deck — the card you're part-way
            // through must not be reshuffled just because the tally moved.
            onGraded()
        }
        state = state.advance(requeue = rating == Rating.AGAIN)
    }

    fun archive() {
        val label = summary?.text ?: entry.item.itemId
        scope.launch {
            withContext(Dispatchers.IO) {
                app.userRepository.setKnown(entry.item.itemId, true, entry.item.kind)
            }
            PracticeWidgetUpdater.refresh(context)
            onGraded()
            onArchived(label)
        }
        state = state.advance(requeue = false)
    }

    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SwipeCard(
            key = entry.item.itemId to state.dealt,
            rightAction = KnewAction,
            leftAction = MissedAction,
            onSwipeRight = { grade(Rating.GOOD) },
            onSwipeLeft = { grade(Rating.AGAIN) },
        ) {
            PracticeCard(
                summary = summary,
                back = back,
                reverse = false,
                showAnswer = state.revealed,
                tts = tts,
                onReveal = { state = state.reveal() },
                onOpenFull = {
                    if (entry.item.isWord) onOpenWord(entry.item.itemId)
                    else onOpenCharacter(entry.item.itemId)
                },
                onArchive = { archive() },
            )
        }
        SwipeLegend(MissedAction, KnewAction)
        Text(
            "${state.deck.size - state.index} waiting",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
