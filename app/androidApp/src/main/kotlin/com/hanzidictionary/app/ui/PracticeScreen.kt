package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.CardState
import com.hanzidictionary.core.DeckBucket
import com.hanzidictionary.core.DeckEntry
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.PracticeState
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.StudyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flashcard practice, scheduled by FSRS (spec §17).
 *
 * The card is the interface: **tap** reveals the answer (**tap again** opens its full
 * page), **swipe right** says you knew it, **swipe left** says you didn't — the same two
 * verbs the home-screen widget offers, so the muscle memory transfers. **Swipe up** skips
 * a card without touching its memory. Revealing is optional on purpose: recognising a
 * character and knowing you recognised it is the thing being tested, and forcing a reveal
 * first would slow the common case down.
 *
 * A missed card goes to the back of the session queue rather than out of it, so within a
 * sitting you do see the ones you don't know more often — and across days FSRS decides,
 * scheduling by predicted recall rather than by a fixed ratio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    repository: HanziRepository,
    hskLevels: Map<Int, List<String>>,
    tts: MandarinTts,
    source: DeckSource,
    initialItem: StudyItem?,
    startRevealed: Boolean,
    onOpenCharacter: (String) -> Unit,
    onOpenWord: (String) -> Unit,
    onOpenConfusables: () -> Unit,
    onOpenArchive: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()

    var reverse by remember { mutableStateOf(false) }
    // All the in-session deck state (which card, its position, whether it's flipped) lives in
    // one immutable PracticeState so its transitions are unit-tested in core; reassigning it
    // drives recomposition.
    var state by remember { mutableStateOf(PracticeState(emptyList(), revealed = startRevealed)) }
    var summaries by remember { mutableStateOf<Map<String, ItemSummary>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf<CardBack?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        loaded = false
        val built = withContext(Dispatchers.IO) {
            val candidates = resolveCandidates(app, repository, hskLevels, source)
            var entries = app.studyRepository.buildDeck(candidates)

            // A card opened from the widget goes to the front rather than making the user
            // hunt for the character they were just looking at — and it goes there even
            // if the scheduler wouldn't have served it (already graded on the widget, or
            // past the deck limit): the user tapped *that* card.
            initialItem?.let { wanted ->
                val at = entries.indexOfFirst { it.item == wanted }
                entries = when {
                    at == 0 -> entries
                    at > 0 -> listOf(entries[at]) + entries.filterIndexed { i, _ -> i != at }
                    else -> listOf(
                        DeckEntry(wanted, app.studyRepository.cardState(wanted.itemId), DeckBucket.AHEAD),
                    ) + entries
                }
            }

            // Resolve display text for the whole deck in one pass, and drop anything the
            // pack no longer carries so a stale list entry can't render as a blank card.
            val found = repository.itemSummaries(entries.map { it.item }).associateBy { it.item.itemId }
            entries.filter { it.item.itemId in found } to found
        }
        state = PracticeState(
            deck = built.first,
            revealed = startRevealed && initialItem != null,
        )
        summaries = built.second
        loaded = true
    }

    val current = state.current
    LaunchedEffect(current?.item) {
        back = current?.let { entry ->
            withContext(Dispatchers.IO) { loadBack(repository, entry.item) }
        }
    }

    fun grade(rating: Rating) {
        val entry = state.current ?: return
        scope.launch {
            withContext(Dispatchers.IO) { app.studyRepository.grade(entry.item, rating) }
            PracticeWidgetUpdater.refresh(context)
        }
        // Missed cards come back later this session; recalled ones are done for now.
        state = state.advance(requeue = rating == Rating.AGAIN)
    }

    fun archive() {
        val entry = state.current ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                app.userRepository.setKnown(entry.item.itemId, true, entry.item.kind)
            }
            PracticeWidgetUpdater.refresh(context)
        }
        state = state.advance(requeue = false)
    }

    fun skip() {
        // A skip is deliberately weightless: no grade, no review_log row, no widget poke —
        // it sends the card to the back of this session's queue and moves on, leaving its
        // FSRS memory exactly as it was.
        if (state.current != null) state = state.advance(requeue = true)
    }

    if (showInfo) GradingInfoDialog(onDismiss = { showInfo = false })

    Scaffold(
        topBar = {
            TopAppBar(
                // The deck's source (list name / HSK band / "Recently encountered") lives up
                // here beside the header, not as a chip in the scroll row — it's a label, not
                // a control, so it belongs where the screen names itself.
                title = {
                    Column {
                        Text("Practice", fontSize = 20.sp)
                        Text(
                            sourceLabel(source),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = { TextButton(onClick = onOpenArchive) { Text("Archive") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Both are chips so they read as one row of toggles/actions rather than a
                // chip next to a differently-shaped button. "Meaning → character" reflects a
                // real on/off state; "Look-alikes" is a one-shot jump, so it never looks
                // selected.
                FilterChip(
                    selected = reverse,
                    onClick = { reverse = !reverse; state = state.collapse() },
                    label = { Text("Meaning → character") },
                )
                FilterChip(
                    selected = false,
                    onClick = onOpenConfusables,
                    label = { Text("Look-alikes") },
                )
            }

            val entry = state.current
            when {
                !loaded -> Text("Loading…")
                entry == null -> EmptyDeck(source)
                else -> {
                    Text(
                        deckSummary(state.deck, state.index),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val summary = summaries[entry.item.itemId]
                    val openFull = {
                        if (entry.item.isWord) onOpenWord(entry.item.itemId)
                        else onOpenCharacter(entry.item.itemId)
                    }
                    SwipeCard(
                        key = entry.item.itemId to state.dealt,
                        rightAction = KnewAction,
                        leftAction = MissedAction,
                        onSwipeRight = { grade(Rating.GOOD) },
                        onSwipeLeft = { grade(Rating.AGAIN) },
                        upAction = SkipAction,
                        onSwipeUp = { skip() },
                    ) {
                        PracticeCard(
                            summary = summary,
                            back = back,
                            reverse = reverse,
                            showAnswer = state.revealed,
                            tts = tts,
                            onReveal = { state = state.reveal() },
                            onOpenFull = openFull,
                        )
                    }
                    SwipeLegend(MissedAction, KnewAction, up = SkipAction)

                    if (state.revealed) {
                        // Four-grade FSRS is measurably better than two, so Hard/Easy are
                        // here for when the difference is obvious — but the swipes stay
                        // the primary path, and they are the only two the widget has. The
                        // ⓘ explains what any of these do to the schedule.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { grade(Rating.HARD) }) { Text("Hard") }
                            OutlinedButton(onClick = { grade(Rating.EASY) }) { Text("Easy") }
                            TextButton(onClick = { showInfo = true }) {
                                Text("ⓘ", fontSize = 20.sp)
                            }
                        }
                        Button(onClick = { archive() }) { Text("I know this — archive it") }
                        Text(
                            "Archiving removes it from every list's practice for good.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        entry.card?.let { CardHistory(it) }
                    }
                }
            }
        }
    }
}

/** The back of a card: everything worth teaching once the answer is out. */
private data class CardBack(
    val pinyin: String?,
    val gloss: String?,
    val wordsLabel: String,
    val words: List<BackWord>,
    val speakText: String,
)

/** One line in the card's word list — a word the character appears in, or (for a word
 * card) one of its constituent characters. */
private data class BackWord(val text: String, val pinyin: String?, val gloss: String?)

private suspend fun loadBack(repository: HanziRepository, item: StudyItem): CardBack? =
    if (item.isWord) {
        repository.wordPage(item.itemId)?.let { w ->
            CardBack(
                pinyin = w.pinyin,
                gloss = w.senses.take(3).joinToString("; "),
                // A word can't be "used in" much, so its back teaches what it's built from.
                wordsLabel = "Made of",
                words = w.chars.map {
                    BackWord(it.char, null, it.definition?.substringBefore(";")?.trim())
                },
                speakText = w.simp,
            )
        }
    } else {
        repository.characterPage(item.itemId)?.let { c ->
            CardBack(
                pinyin = c.readings.filter { it.system == "pinyin" }
                    .distinctBy { it.value }
                    .joinToString("  ") { it.value }
                    .ifBlank { null },
                gloss = c.definition,
                // c.words is already learner-ordered (HSK, then length), so the first few
                // are the words most worth recognising this character in.
                wordsLabel = "Used in words",
                words = c.words.map { BackWord(it.simp, it.pinyin, it.gloss) },
                speakText = c.char,
            )
        }
    }

@Composable
private fun PracticeCard(
    summary: ItemSummary?,
    back: CardBack?,
    reverse: Boolean,
    showAnswer: Boolean,
    tts: MandarinTts,
    onReveal: () -> Unit,
    onOpenFull: () -> Unit,
) {
    // One tap reveals the answer; once it's showing, the next tap opens the full page —
    // the card itself is the link, so there's no separate button to hunt for.
    Card(
        Modifier.fillMaxWidth().clickable {
            if (!showAnswer) onReveal() else onOpenFull()
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (summary == null) {
                Text("…", fontSize = 80.sp)
                return@Card
            }
            val gloss = back?.gloss ?: summary.gloss ?: "No English gloss for this."
            // Words are longer than characters, so they get a smaller face — a five-
            // character phrase at 88sp wraps into a wall.
            val faceSize = if (summary.text.length > 2) 52.sp else 88.sp

            if (!reverse) {
                Text(summary.text, fontSize = faceSize, lineHeight = faceSize * 1.1f)
            } else {
                Text(gloss, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 27.sp)
            }

            if (!showAnswer) {
                Text(
                    "Tap to reveal",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (reverse) Text(summary.text, fontSize = faceSize, lineHeight = faceSize * 1.1f)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (back?.pinyin ?: summary.pinyin)?.let { Text(it, fontSize = 22.sp) }
                    if (tts.available) {
                        TextButton(onClick = { tts.speak(back?.speakText ?: summary.text) }) {
                            Text("🔊")
                        }
                    }
                }
                if (!reverse) {
                    Text(gloss, fontSize = 16.sp, textAlign = TextAlign.Center)
                }
                back?.words?.take(6)?.takeIf { it.isNotEmpty() }?.let { words ->
                    Column(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            back.wordsLabel,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        words.forEach { w ->
                            Text(
                                buildString {
                                    append(w.text)
                                    w.pinyin?.let { append("  "); append(it) }
                                    w.gloss?.let { append(" — "); append(it) }
                                },
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    "Tap card for full page →",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Explains what the grades and swipes do to a card's schedule (spec §17 grading). */
@Composable
private fun GradingInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How grading works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Each answer tells the scheduler how well you knew the card, and it sets " +
                        "when you'll see it again — the better you knew it, the longer the gap.",
                    fontSize = 14.sp,
                )
                Text("✗  Didn't know  (swipe left) — comes back within the minute, then often.", fontSize = 13.sp)
                Text("Hard — you got it, but barely; it returns sooner than a normal card.", fontSize = 13.sp)
                Text("✓  Knew it  (swipe right) — a normal step; the gap grows each time.", fontSize = 13.sp)
                Text("Easy — effortless; the next gap jumps well past normal.", fontSize = 13.sp)
                Text("↑  Swipe up to skip — move on without changing the card's schedule.", fontSize = 13.sp)
            }
        },
    )
}

/** Per-card history, in plain words rather than SRS jargon. */
@Composable
private fun CardHistory(card: CardState) {
    if (card.reviews == 0) return
    Text(
        "recalled ${card.recalled} · missed ${card.lapses}",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyDeck(source: DeckSource) {
    Text(
        when (source) {
            is DeckSource.Encountered ->
                "Nothing to practice. Characters you look up or find in photos land here " +
                    "until you file or archive them."
            is DeckSource.Hsk -> "Every character in this HSK band is archived. 好棒!"
            is DeckSource.Series -> "Every character in this series is archived."
            is DeckSource.UserList -> "This list is empty, or everything in it is archived."
            is DeckSource.Everything ->
                "No lists yet. Swipe right on a character anywhere in the app to start one."
        },
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 32.dp),
    )
}

/** Leads with position, then says *why* these cards are up — "ahead" is worth flagging so
 * practicing ahead of schedule never looks like the app losing track of time. */
private fun deckSummary(deck: List<DeckEntry>, index: Int): String {
    val due = deck.count { it.bucket == DeckBucket.DUE }
    val fresh = deck.count { it.bucket == DeckBucket.NEW }
    val ahead = deck.count { it.bucket == DeckBucket.AHEAD }
    val parts = buildList {
        if (due > 0) add("$due due")
        if (fresh > 0) add("$fresh new")
        if (ahead > 0) add("$ahead ahead of schedule")
    }
    return "${index + 1} of ${deck.size}" + if (parts.isEmpty()) "" else "  ·  ${parts.joinToString(" · ")}"
}

private fun sourceLabel(source: DeckSource): String = when (source) {
    is DeckSource.Everything -> "Everything"
    is DeckSource.UserList -> source.name.ifBlank { "List" }
    is DeckSource.Encountered -> "Recently encountered"
    is DeckSource.Hsk -> if (source.level == 7) "HSK 7-9" else "HSK ${source.level}"
    is DeckSource.Series -> "${source.componentId} series"
}

/** Turns a deck source into the items it stands for. Lists live in the user database;
 * the HSK, series and encountered decks need the content pack, which is why this sits in
 * the app layer rather than in [StudyRepository]. */
private fun resolveCandidates(
    app: HanziApp,
    repository: HanziRepository,
    hskLevels: Map<Int, List<String>>,
    source: DeckSource,
): List<StudyItem> = when (source) {
    is DeckSource.Everything -> app.studyRepository.allListItems()
    is DeckSource.UserList -> app.studyRepository.itemsInList(source.listId)
    is DeckSource.Encountered -> app.userRepository.practiceQueue().map { StudyItem.character(it) }
    is DeckSource.Hsk ->
        repository.sortByFrequency(hskLevels[source.level].orEmpty()).map { StudyItem.character(it) }
    is DeckSource.Series -> {
        val page = repository.componentPage(source.componentId)
        (page.phoneticMembers + page.semanticMembers)
            .map { it.glyphId }
            .distinct()
            .map { StudyItem.character(it) }
    }
}
