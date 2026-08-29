package com.hanzidictionary.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    lookupStack: LookupStack,
    onSwitchToDictionary: () -> Unit,
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

    LaunchedEffect(source) {
        loaded = false
        val (entries, found) = withContext(Dispatchers.IO) {
            val candidates = resolveCandidates(app, repository, hskLevels, source)
            val deck = app.studyRepository.buildDeck(candidates)
            val sums = repository.itemSummaries(deck.map { it.item }).associateBy { it.item.itemId }
            deck.filter { it.item.itemId in sums } to sums
        }
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

    fun grade(rating: Rating) {
        val entry = state.current ?: return
        scope.launch {
            withContext(Dispatchers.IO) { app.studyRepository.grade(entry.item, rating) }
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
                DeckStrip(state.deck, eth)

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
                        Row(Modifier.fillMaxSize()) {
                            GradeGutter(
                                color = eth.amber100,
                                mark = "✗",
                                label = "AGAIN",
                                labelColor = eth.amber600,
                                modifier = Modifier.width(30.dp).fillMaxHeight(),
                            )
                            Column(
                                Modifier.weight(1f).fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
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
                                        onReveal = { state = state.reveal() },
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
                                color = eth.jade100,
                                mark = "✓",
                                label = "KNEW",
                                labelColor = eth.jade600,
                                modifier = Modifier.width(30.dp).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckStrip(deck: List<DeckEntry>, eth: EthanColors) {
    val newCount = deck.count { it.bucket == DeckBucket.NEW }
    val dueCount = deck.count { it.bucket == DeckBucket.DUE }
    val aheadCount = deck.count { it.bucket == DeckBucket.AHEAD }
    val total = (newCount + dueCount + aheadCount).coerceAtLeast(1)

    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
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
    }
    HorizontalDivider(color = eth.inkRule)
}

@Composable
private fun GradeGutter(
    color: Color,
    mark: String,
    label: String,
    labelColor: Color,
    modifier: Modifier,
) {
    Box(
        modifier.background(color),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(mark, fontFamily = PlexMono, fontSize = 10.sp, color = labelColor)
            Text(label, fontFamily = PlexMono, fontSize = 10.sp, letterSpacing = 0.1.sp, color = labelColor)
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
