package com.hanzidictionary.app.ui

import android.graphics.Paint
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
import androidx.core.content.res.ResourcesCompat
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.R
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.TriageVerdict
import com.hanzidictionary.core.charOfGlyphId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Same grammar as everywhere else — right affirms, left is the negative, up moves on — worded
// for what is being asked here. "Know" is a claim, not a grade: see StudyRepository.triage.
private val KnowAction = SwipeAction("I know it", "✓", Color(0xFF2E7D32))
private val DontKnowAction = SwipeAction("Don't know", "✗", Color(0xFFC62828))

/** Extra candidates fetched beyond the target, so a few skips don't run the queue dry. */
private const val SKIP_BUFFER = 12

/**
 * New-word triage: the app shows a character from your photos, or else an HSK word you
 * haven't met in the app, and you say whether you know it. A whole-screen overlay (not a
 * tab) like Settings and the list editor.
 *
 * Photos lead because those characters have already shown up in the user's life; the HSK
 * list only fills a thin deck. It is only reachable while the deck is thin (see
 * `newWordGap`), and it stops by itself once it has added that many items — the point is
 * to top the deck up, not to invite a binge. The app never tests the claim: "know" files
 * the item with a first rating that brings it back for a real recall check in about a
 * week, "don't know" files it as a plain new card, and up skips it for a while without
 * filing anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriageScreen(
    repository: HanziRepository,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val eth = EthanTheme.colors
    BackHandler(onBack = onDone)

    var loaded by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf<List<ItemSummary>>(emptyList()) }
    var fromPhotos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var target by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    var added by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    // Answers are written one at a time: the first one may have to create the default list, and
    // two swipes racing to do that would leave two of them.
    val writes = remember { Mutex() }

    // The pack includes characters the bundled Noto font has no glyph for; they'd render as
    // tofu boxes. Same test the photo feed uses, so triage never offers one either.
    val glyphPaint = remember {
        Paint().apply { typeface = ResourcesCompat.getFont(context, R.font.noto_sans_cjk_sc) }
    }

    LaunchedEffect(Unit) {
        val (want, picks, photoSet) = withContext(Dispatchers.IO) {
            val study = app.studyRepository
            val wanted = study.newWordsWanted()
            val seen = study.everSeenIds() + study.recentlySkippedIds()
            val quota = wanted + SKIP_BUFFER
            // A character already met in a photo is a better suggestion than the next HSK
            // word, so photo finds lead and the word list only fills what's left. Seen-state
            // lives in the other database, so both filters take it as a set.
            val photoIds = app.userRepository.recentPhotoFinds().map { it.glyphId }
            val photos = repository.glyphCandidates(photoIds, seen, quota)
                .filter { glyphPaint.hasGlyph(charOfGlyphId(it.itemId)) }
            val words = if (photos.size < quota) {
                repository.hskWordCandidates(seen + photoIds, quota - photos.size)
            } else {
                emptyList()
            }
            Triple(
                wanted,
                repository.itemSummaries(photos + words),
                photos.map { it.itemId }.toSet(),
            )
        }
        target = want
        queue = picks
        fromPhotos = photoSet
        loaded = true
    }

    val current = queue.getOrNull(index)
    val finished = loaded && (added >= target || current == null)

    fun answer(verdict: TriageVerdict) {
        val summary = current ?: return
        if (verdict != TriageVerdict.SKIP) added++
        index++
        revealed = false
        scope.launch {
            writes.withLock {
                withContext(Dispatchers.IO) { app.studyRepository.triage(summary.item, verdict) }
            }
            PracticeWidgetUpdater.refresh(context)
        }
    }

    Scaffold(
        containerColor = eth.paper1,
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
                            "New words",
                            fontFamily = Piazzolla,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = eth.ink900,
                        )
                        if (loaded && target > 0) {
                            Text(
                                "$added of $target added",
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
                !loaded -> CenteredNote("Loading…", eth)
                finished -> TriageDone(added = added, outOfWords = current == null && added < target, eth = eth, onDone = onDone)
                else -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val card = checkNotNull(current) { "not finished, so there is a card" }
                    Text(
                        "↑ skip",
                        fontFamily = PlexMono,
                        fontSize = 10.sp,
                        color = eth.textFaint,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    SwipeCard(
                        key = card.item.itemId,
                        rightAction = KnowAction,
                        leftAction = DontKnowAction,
                        onSwipeRight = { answer(TriageVerdict.KNOW) },
                        onSwipeLeft = { answer(TriageVerdict.DONT_KNOW) },
                        upAction = SkipAction,
                        onSwipeUp = { answer(TriageVerdict.SKIP) },
                    ) {
                        TriageCard(card, revealed, card.item.itemId in fromPhotos, eth) { revealed = true }
                    }
                    Text(
                        "← don't know        know →",
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

@Composable
private fun TriageCard(
    summary: ItemSummary,
    revealed: Boolean,
    fromPhotos: Boolean,
    eth: EthanColors,
    onReveal: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(eth.paper0)
            .border(1.dp, eth.borderDefault, RoundedCornerShape(12.dp))
            .clickable(enabled = !revealed, onClick = onReveal)
            .padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            summary.text,
            fontFamily = NotoCjk,
            fontSize = 64.sp,
            color = eth.ink900,
            textAlign = TextAlign.Center,
        )
        if (fromPhotos) {
            Text("from your photos", fontFamily = InstrumentSans, fontSize = 12.sp, color = eth.textFaint)
        }
        if (revealed) {
            summary.pinyin?.let {
                Text(it, fontFamily = PlexMono, fontSize = 20.sp, color = eth.ink900)
            }
            summary.gloss?.let {
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
private fun TriageDone(added: Int, outOfWords: Boolean, eth: EthanColors, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val message = when {
            added > 0 && outOfWords ->
                "Added $added. That's everything left to offer for now."
            added > 0 ->
                "Added $added new ${if (added == 1) "word" else "words"}. That's enough for the next sitting."
            outOfWords -> "Nothing left to offer right now."
            else -> "Your deck has plenty coming up — no new words needed yet."
        }
        Text(
            message,
            fontFamily = InstrumentSans,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = eth.textBody,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = eth.accent, contentColor = eth.onAccent),
            modifier = Modifier.padding(top = 20.dp),
        ) { Text("Back to study", fontFamily = InstrumentSans) }
    }
}

@Composable
private fun CenteredNote(text: String, eth: EthanColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = eth.textFaint, fontFamily = InstrumentSans)
    }
}
