package com.hanzidictionary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.CharacterPage
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.WordPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One level in the lookup sheet's trail. Exactly one of glyphId / wordId / componentId is
 * non-null. [componentId] is the bare component character (青, 女), not a "U+" id. */
data class LookupEntry(
    val glyphId: String? = null,
    val wordId: String? = null,
    val componentId: String? = null,
    val displayText: String = glyphId ?: wordId ?: componentId ?: "…",
)

/** Sheet state held per-tab: the trail plus the suspended card's display text.
 * Empty trail means no sheet. [suspendedCardText] is what the return chip labels. */
@Stable
class LookupStack {
    var trail by mutableStateOf<List<LookupEntry>>(emptyList())
    var suspendedCardText by mutableStateOf<String?>(null)

    val isOpen get() = trail.isNotEmpty()

    fun push(entry: LookupEntry) { trail = trail + entry }

    fun pop() {
        trail = trail.dropLast(1)
    }

    fun dismiss() { trail = emptyList() }

    fun popTo(index: Int) { trail = trail.take(index + 1) }
}

/**
 * The lookup sheet: an incidental-lookup surface that layers over a card or the Dictionary
 * tab without pushing a nav destination.
 *
 * The trail allows multi-level browsing (好 › 女 › 妈) while keeping the suspended card
 * one gesture away. System Back pops one level; when the trail is one deep it dismisses
 * the sheet entirely. The return chip dismisses all at once.
 */
@Composable
fun LookupSheet(
    stack: LookupStack,
    repository: HanziRepository,
    userRepository: UserRepository,
    tts: MandarinTts,
) {
    if (!stack.isOpen) return

    val eth = EthanTheme.colors
    val snackbar = remember { SnackbarHostState() }

    // Back: pop one level, or dismiss if at depth 1
    BackHandler {
        if (stack.trail.size <= 1) stack.dismiss() else stack.pop()
    }

    // Scrim + sheet
    Box(Modifier.fillMaxSize()) {
        // Scrim: tapping dismisses the sheet
        Box(
            Modifier.fillMaxSize()
                .background(eth.scrim)
                .clickable(onClick = { stack.dismiss() }),
        )

        // Sheet itself, riding high so the definition gets most of the screen; a strip of the
        // suspended card still peeks above it as the affordance for tapping back.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 84.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(eth.paper0)
                .fillMaxSize(),
        ) {
            SheetHeader(stack, eth)
            HorizontalDivider(color = eth.inkRule)

            val entry = stack.trail.lastOrNull() ?: return@Column
            SheetContent(entry, stack, repository, userRepository, tts, eth, snackbar)
        }

        // Add-to-list / archive confirmations float above the sheet's own scroll.
        Box(Modifier.align(Alignment.BottomCenter)) { AppSnackbarHost(snackbar) }
    }
}

/** Shared study-actions for the sheet's "+ Add to list" / "Archive" buttons: file the item
 * into the active list (creating one if needed) or archive it, each with an Undo. Pokes the
 * widget after every mutation (a placed widget follows the active list / due set). */
@Composable
private fun rememberSheetActions(snackbar: SnackbarHostState): SheetActions {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    return remember(app) {
        SheetActions(
            addToList = { item, label ->
                scope.launch {
                    val listName = withContext(Dispatchers.IO) {
                        val list = app.studyRepository.ensureActiveList()
                        app.studyRepository.addToList(list.id, item)
                        list.name
                    }
                    PracticeWidgetUpdater.refresh(context)
                    if (snackbar.snack("Added $label to \"$listName\"", action = "Undo")) {
                        withContext(Dispatchers.IO) {
                            app.studyRepository.activeListId()?.let {
                                app.studyRepository.removeFromList(it, item.itemId)
                            }
                        }
                        PracticeWidgetUpdater.refresh(context)
                    }
                }
            },
            archive = { item, label ->
                scope.launch {
                    withContext(Dispatchers.IO) { app.userRepository.setKnown(item.itemId, true, item.kind) }
                    PracticeWidgetUpdater.refresh(context)
                    if (snackbar.snack("Archived $label", action = "Undo")) {
                        withContext(Dispatchers.IO) { app.userRepository.setKnown(item.itemId, false, item.kind) }
                        PracticeWidgetUpdater.refresh(context)
                    }
                }
            },
        )
    }
}

private class SheetActions(
    val addToList: (StudyItem, String) -> Unit,
    val archive: (StudyItem, String) -> Unit,
)

@Composable
private fun SheetHeader(stack: LookupStack, eth: EthanColors) {
    // Drag handle
    Box(Modifier.fillMaxWidth().padding(top = 7.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .size(width = 34.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(eth.ink200),
        )
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Return chip (left)
        val cardText = stack.suspendedCardText
        if (cardText != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(eth.surfaceAccentSoft)
                    .border(1.dp, eth.orange200, RoundedCornerShape(7.dp))
                    .clickable { stack.dismiss() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "↓ back to",
                        fontFamily = PlexMono,
                        fontSize = 11.sp,
                        color = eth.onAccentSoft,
                    )
                    Text(cardText, fontSize = 15.sp, color = eth.onAccentSoft, fontFamily = NotoCjk)
                }
            }
        }

        // Trail (center, flex)
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            stack.trail.forEachIndexed { i, e ->
                if (i > 0) {
                    Text(" › ", fontSize = 13.sp, color = eth.ink200)
                }
                val isCurrent = i == stack.trail.lastIndex
                Text(
                    e.displayText,
                    fontSize = if (isCurrent) 14.sp else 13.sp,
                    fontFamily = NotoCjk,
                    color = if (isCurrent) eth.ink900 else eth.textFaint,
                    fontWeight = if (isCurrent) FontWeight.Normal else FontWeight.Normal,
                    textDecoration = if (isCurrent) TextDecoration.Underline else TextDecoration.None,
                    modifier = if (!isCurrent) Modifier.clickable { stack.popTo(i) } else Modifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // × close (right)
        Text(
            "×",
            fontSize = 13.sp,
            color = eth.textFaint,
            modifier = Modifier.clickable { stack.dismiss() }.padding(4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetContent(
    entry: LookupEntry,
    stack: LookupStack,
    repository: HanziRepository,
    userRepository: UserRepository,
    tts: MandarinTts,
    eth: EthanColors,
    snackbar: SnackbarHostState,
) {
    val onLookup = { e: LookupEntry -> stack.push(e) }
    val actions = rememberSheetActions(snackbar)

    if (entry.glyphId != null) {
        CharacterSheetContent(
            glyphId = entry.glyphId,
            stack = stack,
            repository = repository,
            userRepository = userRepository,
            tts = tts,
            eth = eth,
            onLookup = onLookup,
            actions = actions,
        )
    } else if (entry.wordId != null) {
        WordSheetContent(
            wordId = entry.wordId,
            repository = repository,
            tts = tts,
            eth = eth,
            onLookup = onLookup,
            actions = actions,
        )
    } else if (entry.componentId != null) {
        ComponentSheetContent(
            componentId = entry.componentId,
            repository = repository,
            tts = tts,
            eth = eth,
            onLookup = onLookup,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterSheetContent(
    glyphId: String,
    stack: LookupStack,
    repository: HanziRepository,
    userRepository: UserRepository,
    tts: MandarinTts,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
    actions: SheetActions,
) {
    var loaded by remember(glyphId) { mutableStateOf(false) }
    val page by produceState<CharacterPage?>(null, glyphId) {
        value = withContext(Dispatchers.IO) { repository.characterPage(glyphId) }
        loaded = true
    }
    LaunchedEffect(glyphId) {
        withContext(Dispatchers.IO) { userRepository.logLookup(glyphId) }
    }

    val p = page
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (p == null) {
            Text(
                if (loaded) "Not in the data pack." else "Loading…",
                color = eth.textFaint, fontFamily = InstrumentSans,
            )
            return@Column
        }

        // Head row: glyph left (72dp), readings + actions right
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(p.char, fontSize = 72.sp, fontFamily = NotoCjk, color = eth.ink900)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val pinyinReadings = p.readings.filter { it.system == "pinyin" }.distinctBy { it.value }
                if (pinyinReadings.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            pinyinReadings.first().value,
                            fontFamily = PlexMono, fontSize = 21.sp, color = eth.ink900,
                        )
                        if (tts.available) {
                            Text(
                                "▶",
                                fontSize = 11.sp, color = eth.textFaint,
                                modifier = Modifier.clickable { tts.speak(p.char) },
                            )
                        }
                        if (pinyinReadings.size > 1) {
                            Text(
                                "also ${pinyinReadings.drop(1).joinToString(", ") { it.value }}",
                                fontFamily = PlexMono, fontSize = 11.sp, color = eth.textFaint,
                            )
                        }
                    }
                }
                p.definition?.let { def ->
                    Text(def, fontSize = 14.5.sp, lineHeight = 19.sp, color = eth.ink700, fontFamily = InstrumentSans)
                }
                // Meta (freq · HSK · strokes) rides right under the definition rather than in
                // its own band, so the two read as one block.
                val metaParts = listOfNotNull(
                    p.freqRank?.let { "freq #$it" },
                    p.hsk?.let { "HSK $it" },
                    p.strokeCount?.let { "$it strokes" },
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        fontFamily = PlexMono, fontSize = 10.5.sp, color = eth.textFaint,
                    )
                }
                // Study actions
                val charItem = StudyItem.character(glyphId)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { actions.addToList(charItem, p.char) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = eth.accent,
                            contentColor = eth.onAccent,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) { Text("+ Add to list", fontFamily = InstrumentSans, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { actions.archive(charItem, p.char) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) { Text("Archive", fontFamily = InstrumentSans, fontSize = 12.sp) }
                }
            }
        }

        // Formation eyebrow (formation type)
        val formationClaim = p.claims.firstOrNull { it.predicate == "component_function" }
        if (formationClaim != null) {
            val ft = try {
                org.json.JSONObject(formationClaim.value).optString("formation_type", "")
            } catch (e: Exception) { "" }
            if (ft.isNotEmpty()) {
                SheetEyebrow(ft.uppercase())
            }
        }

        // Component chips
        val components = p.breakdown.filter { it.displayChar != null }
        if (components.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (c in components.take(4)) {
                    val color = if (isSoundBearing(c.function)) eth.orange600 else eth.jade600
                    val label = componentLabel(c.displayChar, c.pinyin)
                    val roleLabel = if (isSoundBearing(c.function)) "SOUND · ${label?.text.orEmpty()}"
                    else "MEANING"
                    Row(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(eth.paper1)
                            .border(1.dp, eth.borderSubtle, RoundedCornerShape(5.dp))
                            .clickable {
                                val gid = c.glyphId
                                if (gid != null) {
                                    val displayChar = c.displayChar ?: gid
                                    onLookup(LookupEntry(glyphId = gid, displayText = displayChar))
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.displayChar ?: "", fontSize = 22.sp, color = color, fontFamily = NotoCjk)
                        Column(Modifier.weight(1f)) {
                            Text(roleLabel, fontFamily = PlexMono, fontSize = 8.5.sp, lineHeight = 11.sp, color = eth.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            c.definition?.let { d ->
                                Text(shortGloss(d), fontSize = 11.sp, lineHeight = 13.sp, color = eth.ink700, fontFamily = InstrumentSans, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Prose (etymology / formation text), collapsed to first sentence
        val etymClaim = p.claims.firstOrNull { it.predicate == "etymology_text" }
        if (etymClaim != null) {
            val raw = try {
                val v = etymClaim.value
                if (v.startsWith("\"")) org.json.JSONObject("{\"t\":$v}").getString("t") else v
            } catch (e: Exception) { etymClaim.value }
            var expanded by remember(glyphId) { mutableStateOf(false) }
            val firstSentence = raw.substringBefore(".").trimEnd()
            val hasMore = firstSentence.length < raw.trimEnd().trimEnd('.').length
            val showFull = expanded || !hasMore
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    // A trailing "…" on the collapsed prose signals there's more; the ↓ is the
                    // tap target that reveals it.
                    if (showFull) raw else "$firstSentence …",
                    Modifier.weight(1f, fill = false),
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = eth.textMuted,
                    fontFamily = InstrumentSans,
                )
                if (hasMore) {
                    Text(
                        if (showFull) "↑" else "↓",
                        fontSize = 14.sp, color = eth.textLink, fontFamily = PlexMono,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }
        }

        // In words — every word the character appears in, not a truncated peek. The page's
        // own `words` is capped at 6; this pulls the full list.
        val allWords by produceState(p.words, glyphId) {
            value = withContext(Dispatchers.IO) { repository.wordsForGlyph(glyphId, limit = 500) }
        }
        if (allWords.isNotEmpty()) {
            SheetEyebrow("IN WORDS · ${allWords.size}")
            Column {
                for (w in allWords) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onLookup(LookupEntry(wordId = w.id, displayText = w.simp)) }
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(w.simp, fontSize = 17.sp, color = eth.ink900, fontFamily = NotoCjk)
                        w.pinyin?.let { Text(it, fontFamily = PlexMono, fontSize = 11.5.sp, color = eth.textMuted) }
                        w.gloss?.let {
                            Text(
                                it, Modifier.weight(1f), fontSize = 12.sp, color = eth.textMuted,
                                fontFamily = InstrumentSans, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = eth.inkRule)
                }
            }
        }

        // Footer links: component / phonetic-series pages (spec: "plain mono 11px links").
        // Each carries the component id it opens (the bare char, not a "U+" id).
        val componentLinks = buildList {
            // The sound side this character belongs to, if it has a phonetic part.
            p.breakdown.firstOrNull { isSoundBearing(it.function) }
                ?.displayChar?.let { phon -> add("Sound series $phon" to phon) }
            // What else this character is a part of.
            add("${p.char} as a component" to p.char)
        }
        if (componentLinks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((label, componentId) in componentLinks) {
                    Text(
                        label,
                        fontFamily = PlexMono, fontSize = 11.sp, color = eth.textLink,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            onLookup(LookupEntry(componentId = componentId, displayText = componentId))
                        },
                    )
                }
            }
        }

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun WordSheetContent(
    wordId: String,
    repository: HanziRepository,
    tts: MandarinTts,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
    actions: SheetActions,
) {
    val page by produceState<WordPage?>(null, wordId) {
        value = withContext(Dispatchers.IO) { repository.wordPage(wordId) }
    }
    val p = page ?: return

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(p.simp, fontSize = 52.sp, fontFamily = NotoCjk, color = eth.ink900)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    p.pinyin?.let { Text(it, fontFamily = PlexMono, fontSize = 21.sp, color = eth.ink900) }
                    if (tts.available) {
                        Text("▶", fontSize = 11.sp, color = eth.textFaint,
                            modifier = Modifier.clickable { tts.speak(p.simp) })
                    }
                }
                for ((i, sense) in p.senses.take(2).withIndex()) {
                    Text("${i + 1}. $sense", fontSize = 14.5.sp, color = eth.ink700, fontFamily = InstrumentSans)
                }
                val wordItem = StudyItem.word(wordId)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { actions.addToList(wordItem, p.simp) },
                        colors = ButtonDefaults.buttonColors(containerColor = eth.accent, contentColor = eth.onAccent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) { Text("+ Add to list", fontFamily = InstrumentSans, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { actions.archive(wordItem, p.simp) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) { Text("Archive", fontFamily = InstrumentSans, fontSize = 12.sp) }
                }
            }
        }
        if (p.chars.isNotEmpty()) {
            SheetEyebrow("CHARACTERS")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in p.chars) {
                    Box(
                        Modifier.clip(RoundedCornerShape(5.dp))
                            .background(eth.paper1)
                            .border(1.dp, eth.borderSubtle, RoundedCornerShape(5.dp))
                            .clickable { onLookup(LookupEntry(glyphId = c.glyphId, displayText = c.char)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(c.char, fontSize = 28.sp, fontFamily = NotoCjk)
                    }
                }
            }
        }

        // Example sentences — the point of a word page (spec §9), dropped when the word view
        // became a sheet. Each Mandarin line is speakable; its English gloss sits under it.
        if (p.sentences.isNotEmpty()) {
            SheetEyebrow("SENTENCES")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in p.sentences) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                s.zh, Modifier.weight(1f),
                                fontSize = 16.sp, lineHeight = 23.sp, color = eth.ink900, fontFamily = NotoCjk,
                            )
                            if (tts.available) {
                                Text("🔊", fontSize = 14.sp, modifier = Modifier.clickable { tts.speak(s.zh) })
                            }
                        }
                        s.en?.let {
                            Text(it, fontSize = 12.5.sp, lineHeight = 17.sp, color = eth.textMuted, fontFamily = InstrumentSans)
                        }
                    }
                }
            }
            Text(
                "Sentences from Tatoeba (CC BY 2.0)",
                fontFamily = PlexMono, fontSize = 9.5.sp, color = eth.textFaint,
            )
        }

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentSheetContent(
    componentId: String,
    repository: HanziRepository,
    tts: MandarinTts,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
) {
    val page by produceState<com.hanzidictionary.core.ComponentPage?>(null, componentId) {
        value = withContext(Dispatchers.IO) { repository.componentPage(componentId) }
    }
    val p = page ?: return

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(p.char ?: componentId, fontSize = 60.sp, fontFamily = NotoCjk, color = eth.ink900)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val reading = p.ownReadings.firstOrNull { it.system == "pinyin" }?.value
                if (reading != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(reading, fontFamily = PlexMono, fontSize = 19.sp, color = eth.ink900)
                        if (tts.available && p.char != null) {
                            Text("▶", fontSize = 11.sp, color = eth.textFaint,
                                modifier = Modifier.clickable { tts.speak(p.char!!) })
                        }
                    }
                }
                p.definition?.let {
                    Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = eth.ink700, fontFamily = InstrumentSans)
                }
                if (p.glyphId != null) {
                    Text(
                        "Open character page",
                        fontFamily = PlexMono, fontSize = 11.sp, color = eth.textLink,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            onLookup(LookupEntry(glyphId = p.glyphId, displayText = p.char ?: componentId))
                        },
                    )
                }
            }
        }

        ComponentMemberSection("SOUND SERIES", p.phoneticMembers, eth, onLookup)
        ComponentMemberSection("SHARES THIS MEANING PART", p.semanticMembers, eth, onLookup)

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentMemberSection(
    title: String,
    members: List<com.hanzidictionary.core.SeriesMember>,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
) {
    if (members.isEmpty()) return
    SheetEyebrow("$title · ${members.size}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in members) {
            Box(
                Modifier.clip(RoundedCornerShape(5.dp))
                    .background(eth.paper1)
                    .border(1.dp, eth.borderSubtle, RoundedCornerShape(5.dp))
                    .clickable { onLookup(LookupEntry(glyphId = m.glyphId, displayText = m.char)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(m.char, fontSize = 24.sp, fontFamily = NotoCjk, color = eth.ink900)
            }
        }
    }
}

/** The 10px uppercase mono section eyebrow with a hairline below it. */
@Composable
fun SheetEyebrow(text: String) {
    val eth = EthanTheme.colors
    Column {
        Text(
            text,
            fontFamily = PlexMono, fontSize = 10.sp, color = eth.textFaint,
            letterSpacing = 0.075.sp,
        )
        HorizontalDivider(color = eth.inkRule)
    }
}

// orange-200 as a Color for the return-chip border (referenced from EthanColors)
private val EthanColors.orange200: Color get() = if (isDark) Color(0xFF493B31) else Color(0xFFF4D2AC)
