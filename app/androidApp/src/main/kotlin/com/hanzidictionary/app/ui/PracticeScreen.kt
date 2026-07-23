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
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.StudyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flashcard practice, scheduled by FSRS (spec §17).
 *
 * The card is the interface: **tap** reveals the answer, **swipe right** says you knew it,
 * **swipe left** says you didn't — the same two verbs the home-screen widget offers, so
 * the muscle memory transfers. Revealing is optional on purpose: recognising a character
 * and knowing you recognised it is the thing being tested, and forcing a reveal first
 * would slow the common case down.
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
    var deck by remember { mutableStateOf<List<DeckEntry>>(emptyList()) }
    var summaries by remember { mutableStateOf<Map<String, ItemSummary>>(emptyMap()) }
    var index by remember { mutableIntStateOf(0) }
    var showAnswer by remember { mutableStateOf(startRevealed) }
    var loaded by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf<CardBack?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    var dealt by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, reloads) {
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
        deck = built.first
        summaries = built.second
        index = 0
        showAnswer = startRevealed && initialItem != null
        loaded = true
    }

    val current = deck.getOrNull(index)
    LaunchedEffect(current?.item) {
        back = current?.let { entry ->
            withContext(Dispatchers.IO) { loadBack(repository, entry.item) }
        }
    }

    fun advanceAfter(entry: DeckEntry, requeue: Boolean) {
        val rest = deck.filterIndexed { i, _ -> i != index }
        deck = if (requeue) rest + entry else rest
        if (index >= deck.size) index = 0
        showAnswer = false
        // Bumped on every advance so the card animation resets even when the *same* item
        // comes back round — a one-card deck re-queues onto itself, and keying the card
        // on its id alone would leave it stranded where it was thrown.
        dealt++
    }

    fun grade(rating: Rating) {
        val entry = deck.getOrNull(index) ?: return
        scope.launch {
            withContext(Dispatchers.IO) { app.studyRepository.grade(entry.item, rating) }
            PracticeWidgetUpdater.refresh(context)
        }
        // Missed cards come back later this session; recalled ones are done for now.
        advanceAfter(entry, requeue = rating == Rating.AGAIN)
    }

    fun archive() {
        val entry = deck.getOrNull(index) ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                app.userRepository.setKnown(entry.item.itemId, true, entry.item.kind)
            }
            PracticeWidgetUpdater.refresh(context)
        }
        advanceAfter(entry, requeue = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Practise") },
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
                AssistChip(onClick = { reloads++ }, label = { Text(sourceLabel(source)) })
                FilterChip(
                    selected = reverse,
                    onClick = { reverse = !reverse; showAnswer = false },
                    label = { Text("Meaning → character") },
                )
                OutlinedButton(onClick = onOpenConfusables) { Text("Look-alikes") }
            }

            when {
                !loaded -> Text("Loading…")
                deck.isEmpty() -> EmptyDeck(source)
                else -> {
                    Text(
                        deckSummary(deck, index),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val entry = deck[index]
                    val summary = summaries[entry.item.itemId]
                    SwipeCard(
                        key = entry.item.itemId to dealt,
                        rightAction = KnewAction,
                        leftAction = MissedAction,
                        onSwipeRight = { grade(Rating.GOOD) },
                        onSwipeLeft = { grade(Rating.AGAIN) },
                    ) {
                        PracticeCard(
                            summary = summary,
                            back = back,
                            reverse = reverse,
                            showAnswer = showAnswer,
                            tts = tts,
                            onReveal = { showAnswer = true },
                        )
                    }
                    SwipeLegend(MissedAction, KnewAction)

                    if (showAnswer) {
                        // Four-grade FSRS is measurably better than two, so Hard/Easy are
                        // here for when the difference is obvious — but the swipes stay
                        // the primary path, and they are the only two the widget has.
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { grade(Rating.HARD) }) { Text("Hard") }
                            OutlinedButton(onClick = { grade(Rating.EASY) }) { Text("Easy") }
                        }
                        Button(onClick = { archive() }) { Text("I know this — archive it") }
                        Text(
                            "Archiving removes it from every list's practice for good.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        entry.card?.let { CardHistory(it) }
                        TextButton(onClick = {
                            if (entry.item.isWord) onOpenWord(entry.item.itemId)
                            else onOpenCharacter(entry.item.itemId)
                        }) { Text("Full page →") }
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
    val example: String?,
    val speakText: String,
)

private suspend fun loadBack(repository: HanziRepository, item: StudyItem): CardBack? =
    if (item.isWord) {
        repository.wordPage(item.itemId)?.let { w ->
            CardBack(
                pinyin = w.pinyin,
                gloss = w.senses.take(3).joinToString("; "),
                example = w.chars.mapNotNull { it.definition?.substringBefore(";") }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" + ") { it.trim() },
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
                example = c.words.firstOrNull()?.let { w ->
                    "${w.simp}  ${w.pinyin.orEmpty()} — ${w.gloss.orEmpty()}"
                },
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
) {
    Card(Modifier.fillMaxWidth().clickable(enabled = !showAnswer, onClick = onReveal)) {
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
                back?.example?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
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
                "Nothing to practise. Characters you look up or find in photos land here " +
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
 * practising ahead of schedule never looks like the app losing track of time. */
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
