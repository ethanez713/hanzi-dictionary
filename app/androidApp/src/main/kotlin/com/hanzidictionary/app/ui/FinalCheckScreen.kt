package com.hanzidictionary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.FinalCheckCard
import com.hanzidictionary.core.FinalCheckKind
import com.hanzidictionary.core.FinalCheckSwipe
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.finalCheckReport
import com.hanzidictionary.core.masterySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Same grammar as practice: right affirms, left is the negative, up moves on. The words
// differ because a pass here archives a candidate and leaves an archived item where it is.
private val ArchiveSwipe = SwipeAction("Archive", "✓", Color(0xFF2E7D32))
private val StillKnowSwipe = SwipeAction("Still know", "✓", Color(0xFF2E7D32))
private val BackToStudySwipe = SwipeAction("Back to study", "✗", Color(0xFFC62828))

private data class CheckCard(
    val kind: FinalCheckKind,
    val item: StudyItem,
    val summary: ItemSummary,
    val evidence: String?,
)

/**
 * Final check: one sitting of retirement candidates, then a few archived items.
 * A whole-screen overlay, like new-word triage — not a tab.
 *
 * The card starts face-down, the way a practice card does. The role and the mastery
 * line stay visible either way, because they say what the swipe will do, not the answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalCheckScreen(
    repository: HanziRepository,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val eth = EthanTheme.colors
    BackHandler(onBack = onDone)

    var loaded by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<CheckCard>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var archived by remember { mutableIntStateOf(0) }
    var returned by remember { mutableIntStateOf(0) }
    var passed by remember { mutableIntStateOf(0) }

    // One write at a time. Two swipes otherwise interleave on the single user database.
    val writes = remember { Mutex() }

    LaunchedEffect(Unit) {
        queue = withContext(Dispatchers.IO) {
            val session = app.studyRepository.finalCheckSession()
            val summaries = repository.itemSummaries(session.map { it.item }).associateBy { it.item.itemId }
            session.map { it.toCheckCard(summaries) }
        }
        loaded = true
    }

    val current = queue.getOrNull(index)
    val finished = loaded && current == null

    fun answer(swipe: FinalCheckSwipe) {
        val card = current ?: return
        when {
            card.kind == FinalCheckKind.CANDIDATE && swipe == FinalCheckSwipe.PASS -> archived++
            card.kind == FinalCheckKind.AUDIT && swipe == FinalCheckSwipe.MISS -> returned++
            card.kind == FinalCheckKind.AUDIT && swipe == FinalCheckSwipe.PASS -> passed++
        }
        index++
        revealed = false
        val label = card.summary.text
        scope.launch {
            val undo = writes.withLock {
                withContext(Dispatchers.IO) {
                    app.studyRepository.answerFinalCheck(card.item, card.kind, swipe)
                }
            }
            if (swipe != FinalCheckSwipe.SKIP) PracticeWidgetUpdater.refresh(context)
            if (undo == null) return@launch
            val message = if (card.kind == FinalCheckKind.CANDIDATE) {
                "Archived $label"
            } else {
                "Sent $label back to study"
            }
            if (snackbar.snack(message, action = "Undo")) {
                writes.withLock {
                    withContext(Dispatchers.IO) { app.studyRepository.undoFinalCheck(undo) }
                }
                PracticeWidgetUpdater.refresh(context)
                when (card.kind) {
                    FinalCheckKind.CANDIDATE -> archived = (archived - 1).coerceAtLeast(0)
                    FinalCheckKind.AUDIT -> returned = (returned - 1).coerceAtLeast(0)
                }
            }
        }
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
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = eth.textFaint)
                    }
                },
                title = {
                    Column {
                        Text(
                            "Final check",
                            fontFamily = Piazzolla,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = eth.ink900,
                        )
                        if (loaded && queue.isNotEmpty() && !finished) {
                            Text(
                                "${index + 1} of ${queue.size}",
                                fontFamily = PlexMono,
                                fontSize = 11.sp,
                                color = eth.textFaint,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading…", color = eth.textFaint, fontFamily = InstrumentSans)
                }
                finished -> FinalCheckDone(
                    archived = archived,
                    returned = returned,
                    passed = passed,
                    wasEmpty = queue.isEmpty(),
                    eth = eth,
                    onDone = onDone,
                )
                else -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val card = checkNotNull(current) { "not finished, so there is a card" }
                    val candidate = card.kind == FinalCheckKind.CANDIDATE
                    Text(
                        "↑ skip",
                        fontFamily = PlexMono,
                        fontSize = 10.sp,
                        color = eth.textFaint,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    SwipeCard(
                        key = card.item.itemId,
                        rightAction = if (candidate) ArchiveSwipe else StillKnowSwipe,
                        leftAction = if (candidate) MissedAction else BackToStudySwipe,
                        onSwipeRight = { answer(FinalCheckSwipe.PASS) },
                        onSwipeLeft = { answer(FinalCheckSwipe.MISS) },
                        upAction = SkipAction,
                        onSwipeUp = { answer(FinalCheckSwipe.SKIP) },
                    ) {
                        FinalCheckFace(card, revealed, eth) { revealed = true }
                    }
                    Text(
                        if (candidate) "← didn't know        archive →" else "← back to study        still know →",
                        fontFamily = PlexMono,
                        fontSize = 11.sp,
                        color = eth.textFaint,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private fun FinalCheckCard.toCheckCard(summaries: Map<String, ItemSummary>): CheckCard {
    val summary = summaries[item.itemId]
        ?: ItemSummary(item, item.itemId, secondaryText = null, pinyin = null, gloss = null)
    return CheckCard(kind, item, summary, masterySummary(mastery))
}

@Composable
private fun FinalCheckFace(card: CheckCard, revealed: Boolean, eth: EthanColors, onReveal: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(eth.paper0)
            .border(1.dp, eth.borderDefault, RoundedCornerShape(12.dp))
            .clickable(enabled = !revealed, onClick = onReveal)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (card.kind == FinalCheckKind.CANDIDATE) "Final check" else "From the archive",
            fontFamily = PlexMono,
            fontSize = 11.sp,
            color = eth.textFaint,
        )
        Text(
            card.summary.text,
            fontFamily = NotoCjk,
            fontSize = if (card.summary.text.length > 2) 52.sp else 64.sp,
            color = eth.ink900,
            textAlign = TextAlign.Center,
        )
        card.evidence?.let {
            Text(it, fontFamily = PlexMono, fontSize = 11.sp, color = eth.textFaint, textAlign = TextAlign.Center)
        }
        if (revealed) {
            card.summary.pinyin?.let {
                Text(it, fontFamily = PlexMono, fontSize = 20.sp, color = eth.ink900)
            }
            card.summary.gloss?.let {
                Text(
                    it,
                    fontFamily = InstrumentSans,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    color = eth.ink700,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text("Tap to reveal", fontFamily = InstrumentSans, fontSize = 13.sp, color = eth.textFaint)
        }
    }
}

@Composable
private fun FinalCheckDone(
    archived: Int,
    returned: Int,
    passed: Int,
    wasEmpty: Boolean,
    eth: EthanColors,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (wasEmpty) {
            Text(
                "Nothing is due for a check right now.",
                fontFamily = InstrumentSans,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = eth.textBody,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            finalCheckReport(archived, returned, passed),
            fontFamily = InstrumentSans,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = eth.textBody,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (wasEmpty) 12.dp else 0.dp),
        )
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = eth.accent, contentColor = eth.onAccent),
            modifier = Modifier.padding(top = 20.dp),
        ) { Text("Done", fontFamily = InstrumentSans) }
    }
}
