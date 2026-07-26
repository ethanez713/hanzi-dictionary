package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
 * **The card is the whole interface, and grading is swipe-only.** Swipe right = knew it,
 * left = didn't, up = skip without touching the card's memory; tap reveals the answer, tap
 * again opens the full page. There are deliberately no grade *buttons* beside the gestures:
 * a screen that offers both teaches neither, and the two swipe verbs are exactly what the
 * home-screen widget's two gutters do, so the muscle memory transfers intact. (Hard/Easy
 * are gone for the same reason — FSRS-6 still accepts all four grades, but the UI only ever
 * emits Again and Good.)
 *
 * Everything that isn't a grade lives in the card's own top-right corner — the ⓘ that
 * explains scheduling, and a ⋮ menu holding "I know this", the one irreversible action here.
 * Putting them on the card rather than in the app bar keeps the whole verb set in one place
 * and leaves the bar free for navigation.
 *
 * Revealing is optional on purpose: recognising a character and knowing you recognised it is
 * the thing being tested, and forcing a reveal first would slow the common case down. The
 * revealed side is budgeted to fit one screen without scrolling — sound, meaning, component
 * breakdown, and the words it shows up in.
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
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

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
        val label = summaries[entry.item.itemId]?.text ?: entry.item.itemId
        scope.launch {
            withContext(Dispatchers.IO) {
                app.userRepository.setKnown(entry.item.itemId, true, entry.item.kind)
            }
            PracticeWidgetUpdater.refresh(context)
            // Archiving drops an item out of every list's practice for good, and it now
            // lives behind a menu instead of a button with a warning under it — so the
            // safety net moved to an Undo, the same one the list screens offer.
            val result = snackbar.showSnackbar(
                "Archived $label — out of practice everywhere",
                actionLabel = "Undo",
            )
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) {
                    app.userRepository.setKnown(entry.item.itemId, false, entry.item.kind)
                }
                PracticeWidgetUpdater.refresh(context)
            }
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
        snackbarHost = { SnackbarHost(snackbar) },
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
                // ⓘ is a screen-level explainer, not a per-card action — it says the same
                // thing whatever card is up — so it belongs in the bar beside Home rather
                // than in the card's corner competing with the one action that *is*
                // per-card.
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "How grading works")
                    }
                    HomeButton(onHome)
                },
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
                            onArchive = { archive() },
                        )
                    }
                    SwipeLegend(MissedAction, KnewAction, up = SkipAction)
                    if (state.revealed) entry.card?.let { CardHistory(it) }
                }
            }
        }
    }
}

/** The back of a card: everything worth teaching once the answer is out. */
private data class CardBack(
    val pinyin: String?,
    val gloss: String?,
    val components: List<CardComponent>,
    val wordsLabel: String,
    val words: List<BackWord>,
    val speakText: String,
)

/** One line in the card's word list — a word the character appears in, or (for a word
 * card) one of its constituent characters. */
private data class BackWord(val text: String, val pinyin: String?, val gloss: String?)

/**
 * One part of the character, flattened for the card: the glyph, its role, and what it
 * contributes. The character page's breakdown cards carry provenance badges and drift
 * confidences too; on a flashcard that's noise — what earns its space is "氵 means water,
 * 青 sounds like qīng", because that is the mnemonic that makes the character recallable
 * rather than a shape to memorise.
 *
 * The sound line is carried whatever the part's role: a component you can *say* is a
 * component you can remember, even when it isn't the one lending the character its sound.
 * For a combining form that's its name rather than a reading — see [componentLabel].
 */
private data class CardComponent(
    val char: String,
    val function: String,
    val label: ComponentLabel?,
    val gloss: String?,
)

private suspend fun loadBack(repository: HanziRepository, item: StudyItem): CardBack? =
    if (item.isWord) {
        repository.wordPage(item.itemId)?.let { w ->
            CardBack(
                pinyin = w.pinyin,
                gloss = w.senses.take(3).joinToString("; "),
                // A word's parts are its characters, and they're already listed under
                // "Made of" — decomposing each of those into radicals as well would bury
                // the word itself.
                components = emptyList(),
                wordsLabel = "Made of",
                words = w.chars.map { BackWord(it.char, null, it.definition?.let(::shortGloss)) },
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
                components = c.breakdown.mapNotNull { b ->
                    // displayChar is null only for "ids:" pseudo-components — a dashed
                    // layout box teaches nothing on a card, so those are dropped.
                    b.displayChar?.let { ch ->
                        CardComponent(
                            ch,
                            b.function,
                            componentLabel(ch, b.pinyin),
                            b.definition?.let(::shortGloss),
                        )
                    }
                },
                // c.words is already learner-ordered (HSK, then length), so the first few
                // are the words most worth recognising this character in.
                wordsLabel = "Used in words",
                words = c.words.map { BackWord(it.simp, it.pinyin, it.gloss) },
                speakText = c.char,
            )
        }
    }

/** Components shown on the card. Each one takes an equal share of the row's width, so past
 * four they're too narrow to read — and a character with five parts is better served by the
 * full page anyway. */
private const val CARD_COMPONENTS = 4

/** Height of the card's floating ⓘ/⋮ corner — also the top inset the reverse-mode prompt
 * needs to clear it. Kept next to [CardCorner]'s button size so the two can't drift. */
private val CARD_CORNER_HEIGHT = 38.dp

/**
 * The card itself. The revealed side is three bands separated by hairlines rather than by
 * headings — **answer**, **what it's built from**, **where you'll meet it** — because a
 * label like "Components" costs a whole line to say what the row of coloured glyphs under
 * it already says. Structure carries the meaning; the ink goes to content.
 */
@Composable
private fun PracticeCard(
    summary: ItemSummary?,
    back: CardBack?,
    reverse: Boolean,
    showAnswer: Boolean,
    tts: MandarinTts,
    onReveal: () -> Unit,
    onOpenFull: () -> Unit,
    onArchive: () -> Unit,
) {
    // One tap reveals the answer; once it's showing, the next tap opens the full page —
    // the card itself is the link, so there's no separate button to hunt for.
    Card(
        Modifier.fillMaxWidth().clickable {
            if (!showAnswer) onReveal() else onOpenFull()
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 210.dp).padding(
                    start = 14.dp,
                    end = 14.dp,
                    bottom = 14.dp,
                    // Reverse mode leads with prose, and prose is the one thing wide enough
                    // to reach the corner controls — so in that mode the stack starts below
                    // them. Character-first cards lead with a centred glyph that never gets
                    // near the corner, and they keep the tighter inset.
                    top = if (reverse) CARD_CORNER_HEIGHT else 14.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Face-down the glyph sits in the middle of the card, where the eye lands
                // and where a card you're about to throw belongs; revealed, the stack flows
                // from the top so the bands under it hold still as the words load in.
                verticalArrangement = Arrangement.spacedBy(
                    6.dp,
                    if (showAnswer) Alignment.Top else Alignment.CenterVertically,
                ),
            ) {
                if (summary == null) {
                    Text("…", fontSize = 80.sp)
                    return@Column
                }
                val gloss = back?.gloss ?: summary.gloss ?: "No English gloss for this."
                // Words are longer than characters, so they get a smaller face — a five-
                // character phrase at 80sp wraps into a wall.
                val faceSize = if (summary.text.length > 2) 52.sp else 80.sp

                if (!reverse) {
                    Text(summary.text, fontSize = faceSize, lineHeight = faceSize * 1.1f)
                } else {
                    Text(gloss, fontSize = 19.sp, textAlign = TextAlign.Center, lineHeight = 26.sp)
                }

                if (!showAnswer) {
                    Text(
                        "Tap to reveal",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                if (reverse) Text(summary.text, fontSize = faceSize, lineHeight = faceSize * 1.1f)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (back?.pinyin ?: summary.pinyin)?.let { Text(it, fontSize = 22.sp) }
                    if (tts.available) {
                        TextButton(
                            onClick = { tts.speak(back?.speakText ?: summary.text) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) { Text("🔊", fontSize = 16.sp) }
                    }
                }
                if (!reverse) {
                    Text(gloss, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
                }

                val components = back?.components.orEmpty()
                if (components.isNotEmpty()) {
                    CardRule()
                    ComponentStrip(components)
                }
                // Every word the pack ranks as learner-relevant for this character, not a
                // sample of them: once the card stopped spending lines on headings there was
                // room, and the whole point of the band is meeting the character in context.
                val words = back?.words.orEmpty()
                if (words.isNotEmpty()) {
                    CardRule()
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) { words.forEach { BackWordRow(it) } }
                }
            }

            // The corner controls float over the card instead of taking a row of their own:
            // in the flow they read as a toolbar and pushed the glyph — the thing you're
            // actually here to look at — a third of the way down the card. Nothing else ever
            // reaches this corner (the glyph is centred, the bands below start lower), so
            // there's nothing for them to cover.
            CardCorner(
                modifier = Modifier.align(Alignment.TopEnd),
                onArchive = onArchive,
            )
        }
    }
}

/** The hairline between two bands of the revealed card — the whole reason it needs no
 * headings. Inset from the edges so it reads as a separator, not as a border. */
@Composable
private fun CardRule() {
    HorizontalDivider(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
    )
}

/**
 * The card's action corner: a ⋮ menu holding "I know this" — the only action that is *about
 * this card*, which is why it's the only thing here. (Grading is gestures; a tappable grade
 * in this corner would reintroduce the two-input-systems problem this layout exists to
 * remove. The grading ⓘ explains the screen rather than the card, so it lives in the top bar.)
 */
@Composable
private fun CardCorner(
    modifier: Modifier = Modifier,
    onArchive: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(CARD_CORNER_HEIGHT)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Card options",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("I know this — archive it") },
                onClick = { menuOpen = false; onArchive() },
            )
        }
    }
}

/**
 * The component breakdown, compressed to fit a flashcard: one chip per part, each carrying
 * the glyph in its function colour, the role in words, and the single thing that part
 * contributes. Chips (rather than the character page's stacked full-width cards) because
 * they read as *parts of one character* sitting side by side, which is the fact being
 * taught; the colour coding is [functionColor], shared with the character page, so the two
 * read as the same thing at two levels of detail.
 */
@Composable
private fun ComponentStrip(components: List<CardComponent>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (c in components.take(CARD_COMPONENTS)) {
            val color = functionColor(c.function)
            val label = functionLabel(c.function)
            // Equal shares, so the chips line up and a long gloss ellipsizes instead of
            // shoving its neighbour off the card.
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.char, fontSize = 24.sp, color = color)
                    Column {
                        // The sound line rides the top of the chip whatever the part's role,
                        // but only a real reading on a sound-bearing part gets full size:
                        // everything else is a footnote — useful for saying the part out
                        // loud, not a claim about how the character is pronounced.
                        val loud = isSoundBearing(c.function) && c.label?.isReading == true
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            c.label?.let {
                                Text(
                                    it.text,
                                    fontSize = if (loud) 13.sp else 10.sp,
                                    lineHeight = 14.sp,
                                    color = if (loud) LocalContentColor.current
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // The role is spelled out, not left to colour alone — the strip
                            // has to work for a colour-blind reader too.
                            if (label.isNotEmpty()) {
                                Text(
                                    label,
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    color = color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        c.gloss?.let {
                            Text(
                                it,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One line of the card's bottom band: the word, its sound, its meaning — the same
 * glyph-then-reading-then-gloss order as [ItemRow], so it scans like every other list of
 * words in the app. */
@Composable
private fun BackWordRow(w: BackWord) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(w.text, fontSize = 17.sp)
        w.pinyin?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        w.gloss?.let {
            Text(
                it,
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Explains what the three swipes do to a card's schedule (spec §17 grading). */
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
                Text("←  Didn't know — comes back within the minute, then often.", fontSize = 13.sp)
                Text("→  Knew it — the gap grows every time you get it again.", fontSize = 13.sp)
                Text("↑  Skip — move on without changing the card's schedule.", fontSize = 13.sp)
                Text(
                    "Swipes are the only way to grade, here and on the home-screen widget, " +
                        "so the same three motions work in both places. Tap the card to turn " +
                        "it over, and tap it again to open its full page.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
