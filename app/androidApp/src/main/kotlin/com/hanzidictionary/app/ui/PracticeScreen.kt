package com.hanzidictionary.app.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.CardPin
import com.hanzidictionary.core.DeckBucket
import com.hanzidictionary.core.DeckEntry
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.PracticeState
import com.hanzidictionary.core.Rating
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.deckOpenedAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Study tab: the flashcard practice surface.
 *
 * The glyph is fixed in a 200dp zone and NEVER moves when the card flips — content
 * expands below the zone on reveal. Grade gutters are always visible (amber left ✗ AGAIN,
 * jade right ✓ KNEW). Deck strip at the top shows new/due/ahead counts with a progress bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    repository: HanziRepository,
    hskLevels: Map<Int, List<String>>,
    tts: MandarinTts,
    source: DeckSource,
    resumeToken: Int = 0,
    lookupStack: LookupStack,
    onSwitchToDictionary: () -> Unit,
    onEditList: (Long) -> Unit = {},
    onAddNewWords: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val eth = EthanTheme.colors

    var state by remember { mutableStateOf(PracticeState(emptyList())) }
    var summaries by remember { mutableStateOf<Map<String, ItemSummary>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf<CardBack?>(null) }
    // How many new words the thin-deck rule would offer right now (0 hides the prompt). Judged
    // against all your lists, not just the one on screen: the question is whether the whole
    // deck is running dry.
    var newWordsWanted by remember { mutableIntStateOf(0) }

    LaunchedEffect(source, resumeToken) {
        loaded = false
        val (entries, found) = withContext(Dispatchers.IO) {
            val candidates = resolveCandidates(app, repository, hskLevels, source)
            val deck = app.studyRepository.buildDeck(candidates)
            val sums = repository.itemSummaries(deck.map { it.item }).associateBy { it.item.itemId }
            deck.filter { it.item.itemId in sums } to sums
        }
        newWordsWanted = withContext(Dispatchers.IO) { app.studyRepository.newWordsWanted() }
        state = deckOpenedAt(deck = entries, pin = app.practiceCardPin)
        summaries = found
        loaded = true
    }

    val current = state.current
    LaunchedEffect(current?.item) {
        back = current?.let { entry ->
            withContext(Dispatchers.IO) { loadBack(repository, entry.item) }
        }
    }
    LaunchedEffect(current?.item?.itemId, state.revealed) {
        val entry = current ?: return@LaunchedEffect
        app.practiceCardPin = CardPin(entry.item.itemId, state.revealed)
    }

    // How long this card sat face-down before the reveal tap. Recorded with the grade as
    // reveal_ms (see 3.sqm) for later analysis; null when the card was resumed already
    // revealed or the reveal was never tapped, since neither measures recall effort.
    var shownAt by remember { mutableLongStateOf(0L) }
    var revealMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current?.item?.itemId, state.dealt) {
        shownAt = SystemClock.elapsedRealtime()
        revealMs = null
    }

    fun grade(rating: Rating) {
        val entry = state.current ?: return
        // A gap this long means the app was backgrounded mid-card, not that recall took minutes.
        val reveal = revealMs?.takeIf { it <= REVEAL_MS_CAP }
        scope.launch {
            withContext(Dispatchers.IO) { app.studyRepository.grade(entry.item, rating, revealMs = reveal) }
            PracticeWidgetUpdater.refresh(context)
        }
        state = state.advance(requeue = rating == Rating.AGAIN)
    }

    fun archive() {
        val entry = state.current ?: return
        val label = summaries[entry.item.itemId]?.text ?: entry.item.itemId
        scope.launch {
            withContext(Dispatchers.IO) {
                app.userRepository.setKnown(entry.item.itemId, true, entry.item.kind)
            }
            PracticeWidgetUpdater.refresh(context)
            if (snackbar.snack("Archived $label — out of practice everywhere", action = "Undo")) {
                withContext(Dispatchers.IO) {
                    app.userRepository.setKnown(entry.item.itemId, false, entry.item.kind)
                }
                PracticeWidgetUpdater.refresh(context)
            }
        }
        state = state.advance(requeue = false)
    }

    fun skip() {
        if (state.current != null) state = state.advance(requeue = true)
    }

    Scaffold(
        containerColor = eth.paper1,
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = eth.paper1,
                    titleContentColor = eth.ink900,
                ),
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Study",
                            fontFamily = Piazzolla,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = eth.ink900,
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(eth.surfaceAccentSoft)
                                .border(1.dp, eth.orange200, RoundedCornerShape(50.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                sourceLabel(source) + " ▾",
                                fontFamily = InstrumentSans,
                                fontSize = 11.5.sp,
                                color = eth.onAccentSoft,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onSwitchToDictionary() },
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .border(1.dp, eth.borderDefault, RoundedCornerShape(5.dp))
                            .size(30.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Look up a character",
                            tint = eth.textFaint,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                // Tapping the deck strip edits the list being studied — but only a real
                // user list is editable; HSK/Encountered/Everything pools are not.
                val editableListId = (source as? DeckSource.UserList)?.listId
                DeckStrip(
                    deck = state.deck,
                    eth = eth,
                    onClick = editableListId?.let { { onEditList(it) } },
                    newWordsWanted = if (loaded) newWordsWanted else 0,
                    onAddNewWords = onAddNewWords,
                )

                val entry = state.current
                when {
                    !loaded -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Loading…", color = eth.textFaint, fontFamily = InstrumentSans)
                        }
                    }
                    entry == null -> EmptyDeck(source, eth)
                    else -> {
                        val summary = summaries[entry.item.itemId]
                        // Hidden hints collapse the gutters to thin colour strips, handing the
                        // reclaimed width to the card.
                        val showHints = app.settings.showSwipeHints
                        val gutterWidth = if (showHints) 30.dp else 6.dp
                        // Hidden hints also drop the gutter tint — the reclaimed strips blend
                        // into the background rather than lingering as bare colour bars.
                        Row(Modifier.fillMaxSize()) {
                            GradeGutter(
                                color = if (showHints) eth.amber100 else eth.paper1,
                                mark = "✗",
                                label = "AGAIN",
                                labelColor = eth.amber600,
                                showLabel = showHints,
                                modifier = Modifier.width(gutterWidth).fillMaxHeight(),
                            )
                            Column(
                                Modifier.weight(1f).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (showHints) {
                                    Text(
                                        "↑ skip",
                                        fontFamily = PlexMono,
                                        fontSize = 10.sp,
                                        color = eth.textFaint,
                                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                    )
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
                                        showAnswer = state.revealed,
                                        tts = tts,
                                        onReveal = {
                                            if (!state.revealed) revealMs = SystemClock.elapsedRealtime() - shownAt
                                            state = state.reveal()
                                        },
                                        onOpenFull = {
                                            val label = summary?.text ?: entry.item.itemId
                                            lookupStack.suspendedCardText = label
                                            if (entry.item.isWord) {
                                                lookupStack.push(LookupEntry(wordId = entry.item.itemId, displayText = label))
                                            } else {
                                                lookupStack.push(LookupEntry(glyphId = entry.item.itemId, displayText = label))
                                            }
                                        },
                                        onArchive = { archive() },
                                    )
                                }
                            }
                            GradeGutter(
                                color = if (showHints) eth.jade100 else eth.paper1,
                                mark = "✓",
                                label = "KNEW",
                                labelColor = eth.jade600,
                                showLabel = showHints,
                                modifier = Modifier.width(gutterWidth).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Longest face-down time worth recording; beyond it the app was almost certainly backgrounded. */
private const val REVEAL_MS_CAP = 120_000L

@Composable
private fun DeckStrip(
    deck: List<DeckEntry>,
    eth: EthanColors,
    onClick: (() -> Unit)? = null,
    newWordsWanted: Int = 0,
    onAddNewWords: () -> Unit = {},
) {
    val newCount = deck.count { it.bucket == DeckBucket.NEW }
    val dueCount = deck.count { it.bucket == DeckBucket.DUE }
    val aheadCount = deck.count { it.bucket == DeckBucket.AHEAD }
    val total = (newCount + dueCount + aheadCount).coerceAtLeast(1)

    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$newCount new", fontFamily = PlexMono, fontSize = 11.sp, color = eth.textAccent)
            Text("$dueCount due", fontFamily = PlexMono, fontSize = 11.sp, color = eth.textMuted)
            Text("$aheadCount ahead", fontFamily = PlexMono, fontSize = 11.sp, color = eth.textFaint)
            if (onClick != null) {
                Spacer(Modifier.weight(1f))
                Text("edit ›", fontFamily = PlexMono, fontSize = 11.sp, color = eth.textLink)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val segments = listOf(
                newCount.toFloat() to eth.orange500,
                dueCount.toFloat() to eth.ink300,
                aheadCount.toFloat() to eth.ink100,
            )
            for ((w, color) in segments) {
                if (w > 0f) {
                    Box(
                        Modifier.weight(w / total).fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(color),
                    )
                }
            }
        }
        // The only place the app ever suggests more vocabulary, and only while the thin-deck
        // rule says the next sitting would run short — with the reason, not just the nudge.
        if (newWordsWanted > 0) {
            Text(
                "Not much coming up — add up to $newWordsWanted new words ›",
                fontFamily = PlexMono,
                fontSize = 11.sp,
                color = eth.textLink,
                modifier = Modifier.clickable(onClick = onAddNewWords).padding(top = 2.dp),
            )
        }
    }
    HorizontalDivider(color = eth.inkRule)
}

@Composable
private fun GradeGutter(
    color: Color,
    mark: String,
    label: String,
    labelColor: Color,
    showLabel: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier.background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (showLabel) {
            // One glyph per line so a narrow gutter never has to wrap the word mid-letter.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(mark, fontFamily = PlexMono, fontSize = 11.sp, color = labelColor)
                for (ch in label) {
                    Text(ch.toString(), fontFamily = PlexMono, fontSize = 10.sp, lineHeight = 12.sp, color = labelColor)
                }
            }
        }
    }
}

@Composable
private fun EmptyDeck(source: DeckSource, eth: EthanColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            when (source) {
                is DeckSource.Encountered ->
                    "Nothing to practice. Characters you look up or find in photos land here."
                is DeckSource.Hsk -> "Every character in this HSK band is archived."
                is DeckSource.Series -> "Every character in this series is archived."
                is DeckSource.UserList -> "This list is empty, or everything in it is archived."
                is DeckSource.Everything ->
                    "No lists yet. Look up a character and add it to start."
            },
            textAlign = TextAlign.Center,
            color = eth.textFaint,
            fontFamily = InstrumentSans,
            fontSize = 14.sp,
            modifier = Modifier.padding(32.dp),
        )
    }
}

private fun sourceLabel(source: DeckSource): String = when (source) {
    is DeckSource.Everything -> "Everything"
    is DeckSource.UserList -> source.name.ifBlank { "List" }
    is DeckSource.Encountered -> "Encountered"
    is DeckSource.Hsk -> if (source.level == 7) "HSK 7-9" else "HSK ${source.level}"
    is DeckSource.Series -> "${source.componentId} series"
}

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
            .map { it.glyphId }.distinct().map { StudyItem.character(it) }
    }
}
