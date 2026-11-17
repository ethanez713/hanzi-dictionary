package com.hanzidictionary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.CharacterPage
import com.hanzidictionary.core.ComponentFamily
import com.hanzidictionary.core.ComponentRole
import com.hanzidictionary.core.FAMILY_GROUP_SHOWN
import com.hanzidictionary.core.FamilyGroup
import com.hanzidictionary.core.FamilyMember
import com.hanzidictionary.core.SoundRelation
import com.hanzidictionary.core.familyRoleOf
import com.hanzidictionary.core.soundRange
import com.hanzidictionary.core.visibleMembers
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SentenceExample
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.masterySummary
import com.hanzidictionary.core.WordPage
import com.hanzidictionary.core.isSoundBearing
import com.hanzidictionary.core.otherScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The pages the sheet loaded most recently, so going back a level draws at once (and its
 * saved scroll position lands on content rather than on an empty page that clamps it to 0).
 * Read-only pack data, so nothing in it can go stale. */
private val pageCache = object : LinkedHashMap<String, Any?>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any?>?) = size > 12
}

/** One level in the lookup sheet's trail. Exactly one of glyphId / wordId / componentId is
 * non-null. [componentId] is the bare component character (青, 女), not a "U+" id.
 *
 * A component level can say which of the part's jobs it was opened for ([role]) and from
 * which character ([fromGlyphId]): the family page leads with that job and marks that
 * character in it. Both are null for a component reached with no job in mind. */
data class LookupEntry(
    val glyphId: String? = null,
    val wordId: String? = null,
    val componentId: String? = null,
    val displayText: String = glyphId ?: wordId ?: componentId ?: "…",
    val role: ComponentRole? = null,
    val fromGlyphId: String? = null,
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
    val pageStates = rememberSaveableStateHolder()

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
            // Each trail level gets its own state, keyed by its place in the trail. Without
            // the key, two levels of the same kind (好 › 女, 青 › 氵) shared one composable
            // and the new page showed the old one's sentences, scroll and expanded groups;
            // with the saveable holder, going back a level returns to where you were on it.
            val depth = stack.trail.lastIndex
            key(depth, entry) {
                pageStates.SaveableStateProvider("$depth:$entry") {
                    SheetContent(entry, stack, repository, userRepository, tts, eth, snackbar)
                }
            }
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
                    val (list, added) = withContext(Dispatchers.IO) {
                        val list = app.studyRepository.ensureActiveList()
                        list to app.studyRepository.addToListIfAbsent(list.id, item)
                    }
                    if (!added) {
                        // Nothing changed, so there is nothing to undo — and an Undo here used to
                        // remove the membership the item already had.
                        snackbar.snack("$label is already in \"${list.name}\"")
                        return@launch
                    }
                    PracticeWidgetUpdater.refresh(context)
                    if (snackbar.snack("Added $label to \"${list.name}\"", action = "Undo")) {
                        // The list it went into, even if the active list has changed since.
                        withContext(Dispatchers.IO) { app.studyRepository.removeFromList(list.id, item.itemId) }
                        PracticeWidgetUpdater.refresh(context)
                    }
                }
            },
            archive = { item, label, onArchived ->
                scope.launch {
                    val undo = withContext(Dispatchers.IO) { app.studyRepository.archive(item) }
                    onArchived(true)
                    if (undo.wasKnown) return@launch
                    PracticeWidgetUpdater.refresh(context)
                    if (snackbar.snack("Archived $label", action = "Undo")) {
                        withContext(Dispatchers.IO) { app.studyRepository.undoArchive(undo) }
                        onArchived(false)
                        PracticeWidgetUpdater.refresh(context)
                    }
                }
            },
            unarchive = { item, label, onArchived ->
                scope.launch {
                    // Un-archiving starts the item fresh (UserRepository.setKnown): the schedule
                    // went when it was archived. Undo re-archives it, which loses nothing.
                    withContext(Dispatchers.IO) { app.userRepository.setKnown(item.itemId, false, item.kind) }
                    onArchived(false)
                    PracticeWidgetUpdater.refresh(context)
                    if (snackbar.snack("$label is back in practice", action = "Undo")) {
                        withContext(Dispatchers.IO) { app.studyRepository.archive(item) }
                        onArchived(true)
                        PracticeWidgetUpdater.refresh(context)
                    }
                }
            },
        )
    }
}

/**
 * What your own history says about this item, right above the Archive button: the evidence
 * for "do I really know this?" at the moment you'd claim it. Absent for an item you haven't
 * started. Loaded off the main thread and keyed on the item so a trail hop refreshes it.
 */
@Composable
private fun MasteryLine(item: StudyItem, eth: EthanColors) {
    val app = LocalContext.current.applicationContext as HanziApp
    val line by produceState<String?>(null, item.itemId) {
        value = withContext(Dispatchers.IO) {
            app.studyRepository.masteryFor(listOf(item.itemId))[item.itemId]?.let(::masterySummary)
        }
    }
    line?.let { Text(it, fontFamily = PlexMono, fontSize = 10.5.sp, color = eth.textFaint) }
}

private class SheetActions(
    val addToList: (StudyItem, String) -> Unit,
    /** Archives the item; [onArchived] hears the item's archive state after the tap and after any Undo. */
    val archive: (item: StudyItem, label: String, onArchived: (Boolean) -> Unit) -> Unit,
    /** Takes the item back out of the archive — the only way to, once an Archive's Undo has gone. */
    val unarchive: (item: StudyItem, label: String, onArchived: (Boolean) -> Unit) -> Unit,
)

/**
 * "+ Add to list" and Archive / Unarchive for an entry. The second button says what the item
 * is: an archived item offers Unarchive, which is the way back from the archive once an
 * Archive's Undo snackbar has gone (a mis-tapped Archive, or an "I know it" in triage).
 */
@Composable
private fun StudyButtons(item: StudyItem, label: String, actions: SheetActions, eth: EthanColors) {
    val app = LocalContext.current.applicationContext as HanziApp
    var archived by remember(item.itemId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(item.itemId) {
        archived = withContext(Dispatchers.IO) { app.userRepository.isKnown(item.itemId) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { actions.addToList(item, label) },
            colors = ButtonDefaults.buttonColors(containerColor = eth.accent, contentColor = eth.onAccent),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) { Text("+ Add to list", fontFamily = InstrumentSans, fontSize = 12.sp) }
        val isArchived = archived ?: return@Row
        OutlinedButton(
            onClick = {
                if (isArchived) actions.unarchive(item, label) { archived = it }
                else actions.archive(item, label) { archived = it }
            },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
        ) { Text(if (isArchived) "Unarchive" else "Archive", fontFamily = InstrumentSans, fontSize = 12.sp) }
    }
}

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
            role = entry.role,
            fromGlyphId = entry.fromGlyphId,
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
    val page by produceState(pageCache[glyphId] as CharacterPage?, glyphId) {
        value = withContext(Dispatchers.IO) { repository.characterPage(glyphId) }.also { pageCache[glyphId] = it }
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
                            // Full-strength (not textFaint) so a multi-reading character —
                            // the thing that makes a word a heteronym — reads at a glance.
                            Text(
                                "also ${pinyinReadings.drop(1).joinToString(", ") { it.value }}",
                                fontFamily = PlexMono, fontSize = 11.sp, color = eth.ink900,
                            )
                        }
                    }
                }
                // CC-CEDICT leads: Unihan's gloss is a classical one and routinely misses the
                // sense the words below actually use (项 is "neck, nape" there, "item" here).
                val headlineGloss = p.modernSenses.take(4).joinToString("; ").ifEmpty { null }
                    ?: p.definition
                headlineGloss?.let { def ->
                    Text(def, fontSize = 14.5.sp, lineHeight = 19.sp, color = eth.ink700, fontFamily = InstrumentSans)
                }
                // Meta (freq · HSK · strokes) rides right under the definition rather than in
                // its own band, so the two read as one block.
                val metaParts = listOfNotNull(
                    p.freqRank?.let { "freq #$it" },
                    // glyph.hsk is the lowest level of any HSK word *containing* this
                    // character, not a level for the character itself — say so, and name the
                    // list, since HSK 2.0 and 3.0 grade the same word very differently.
                    p.hsk?.let { "HSK 3.0 ${if (it == 7L) "L7-9" else "L$it"} word" },
                    p.strokeCount?.let { "$it strokes" },
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        fontFamily = PlexMono, fontSize = 10.5.sp, color = eth.textFaint,
                    )
                }
                // The other script's form; tapping it steps to that character's page.
                otherScript(glyphId, p.variants)?.let { o ->
                    ScriptFormsLine(o.traditional, o.forms.map { it.char }, onOpen = { i ->
                        val f = o.forms[i]
                        onLookup(LookupEntry(glyphId = f.glyphId, displayText = f.char))
                    })
                }
                // Study actions
                val charItem = StudyItem.character(glyphId)
                MasteryLine(charItem, eth)
                StudyButtons(charItem, p.char, actions, eth)
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
                                // A card opens the family of the job its part does here (the
                                // sound series, or the meaning family). A part with no such job
                                // opens its own character, or its page if it has no character.
                                val gid = c.glyphId
                                val role = familyRoleOf(c)
                                if (role != null) {
                                    onLookup(LookupEntry(
                                        componentId = c.componentId, displayText = c.displayChar ?: c.componentId,
                                        role = role, fromGlyphId = glyphId,
                                    ))
                                } else if (gid != null) {
                                    onLookup(LookupEntry(glyphId = gid, displayText = c.displayChar ?: gid))
                                } else if (c.navigable) {
                                    onLookup(LookupEntry(componentId = c.componentId, displayText = c.displayChar ?: c.componentId))
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.displayChar ?: "", fontSize = 22.sp, color = color, fontFamily = NotoCjk)
                        Column(Modifier.weight(1f)) {
                            Text(roleLabel, fontFamily = PlexMono, fontSize = 8.5.sp, lineHeight = 11.sp, color = eth.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // A meaning part is glossed by what it contributes here, not by
                            // what it means alone, and the role gloss replaces rather than
                            // joins it: this line is one ellipsized row, and "head" is the
                            // whole point where "head · page, sheet, leaf" is truncated noise.
                            val gloss = c.roleGloss ?: c.definition?.let { shortGloss(it) }
                            gloss?.let { d ->
                                Text(d, fontSize = 11.sp, lineHeight = 13.sp, color = eth.ink700, fontFamily = InstrumentSans, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    // A trailing "…" on the collapsed prose signals there's more; the ▼ is the
                    // tap target that reveals it.
                    if (showFull) raw else "$firstSentence …",
                    Modifier.weight(1f, fill = false),
                    fontSize = 12.5.sp, lineHeight = 18.sp, color = eth.textMuted,
                    fontFamily = InstrumentSans,
                )
                if (hasMore) {
                    Text(
                        if (showFull) "▲" else "▼",
                        fontSize = 11.sp, color = eth.textLink, fontFamily = PlexMono,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }
        }

        // In words — the most common words this character appears in. wordsForGlyph is already
        // ordered by corpus frequency; key the dedup on simp+reading so a heteronym (便宜
        // pián yi "cheap" vs biàn yí "convenient") keeps a row for each reading, while
        // CC-CEDICT's same-reading duplicates still collapse. Capped like the flashcard's list,
        // so opening a card's character never shows less than the card did.
        val topWords by produceState(p.words.distinctBy { it.simp to it.pinyin }.take(CARD_WORDS.toInt()), glyphId) {
            value = withContext(Dispatchers.IO) {
                repository.wordsForGlyph(glyphId, limit = 60).distinctBy { it.simp to it.pinyin }.take(CARD_WORDS.toInt())
            }
        }
        if (topWords.isNotEmpty()) {
            SheetEyebrow("IN WORDS")
            Column {
                for (w in topWords) {
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

        // The same sentences the flashcard shows for this character.
        val sentences by produceState(emptyList<SentenceExample>(), p.char) {
            value = withContext(Dispatchers.IO) { repository.cardSentences(p.char) }
        }
        SentenceList(sentences, tts, eth)

        // Footer links (spec: "plain mono 11px links"): what this character is itself a part
        // of, and — only when no card above carries it — its sound part's series. The cards
        // show the first four parts; a fifth-place sound part would otherwise be unreachable.
        val shownParts = p.breakdown.filter { it.displayChar != null }.take(4)
        val soundPart = p.breakdown.firstOrNull { isSoundBearing(it.function) && it.navigable }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (soundPart != null && soundPart !in shownParts) {
                val label = soundPart.displayChar ?: soundPart.componentId
                Text(
                    "Sound series $label",
                    fontFamily = PlexMono, fontSize = 11.sp, color = eth.textLink,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        onLookup(LookupEntry(
                            componentId = soundPart.componentId, displayText = label,
                            role = ComponentRole.SOUND, fromGlyphId = glyphId,
                        ))
                    },
                )
            }
            Text(
                "${p.char} as a component",
                fontFamily = PlexMono, fontSize = 11.sp, color = eth.textLink,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    onLookup(LookupEntry(componentId = p.char, displayText = p.char))
                },
            )
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
    var loaded by remember(wordId) { mutableStateOf(false) }
    val page by produceState(pageCache["w:$wordId"] as WordPage?, wordId) {
        value = withContext(Dispatchers.IO) { repository.wordPage(wordId) }.also { pageCache["w:$wordId"] = it }
        loaded = true
    }
    val p = page
    if (p == null) {
        // Say which: a blank sheet reads as broken either way.
        Text(
            if (loaded) "Not in the data pack." else "Loading…",
            Modifier.padding(16.dp), color = eth.textFaint, fontFamily = InstrumentSans,
        )
        return
    }

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
                if (p.trad != p.simp) ScriptFormsLine(traditional = true, forms = listOf(p.trad))
                for ((i, sense) in p.senses.take(2).withIndex()) {
                    Text("${i + 1}. $sense", fontSize = 14.5.sp, color = eth.ink700, fontFamily = InstrumentSans)
                }
                val wordItem = StudyItem.word(wordId)
                MasteryLine(wordItem, eth)
                StudyButtons(wordItem, p.simp, actions, eth)
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

        // Example sentences — the point of a word page (spec §9).
        SentenceList(p.sentences, tts, eth)

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** Each Mandarin line is speakable; its English gloss sits under it. */
@Composable
private fun SentenceList(sentences: List<SentenceExample>, tts: MandarinTts, eth: EthanColors) {
    if (sentences.isEmpty()) return
    SheetEyebrow("SENTENCES")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (s in sentences) {
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
        "Sentences from Tatoeba (CC BY 2.0) & TED2020 (CC BY-NC-ND)",
        fontFamily = PlexMono, fontSize = 9.5.sp, color = eth.textFaint,
    )
}

/**
 * The component page: a part's family, sorted into the jobs it does.
 *
 * [role] is the job the reader tapped through for; that side leads and opens expanded, and
 * the other waits collapsed under its heading. With no role (the "as a component" footer
 * link) both are open, sound first.
 */
@Composable
private fun ComponentSheetContent(
    componentId: String,
    role: ComponentRole?,
    fromGlyphId: String?,
    repository: HanziRepository,
    tts: MandarinTts,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
) {
    val page by produceState(pageCache["c:$componentId"] as com.hanzidictionary.core.ComponentPage?, componentId) {
        value = withContext(Dispatchers.IO) { repository.componentPage(componentId) }.also { pageCache["c:$componentId"] = it }
    }
    val familyKey = "f:$componentId:$fromGlyphId"
    val family by produceState(pageCache[familyKey] as ComponentFamily?, componentId, fromGlyphId) {
        value = withContext(Dispatchers.IO) { repository.componentFamily(componentId, fromGlyphId) }.also { pageCache[familyKey] = it }
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
                // A combining form is named, not pronounced (氵 = 三点水), exactly as on its card.
                val label = componentLabel(p.char, p.ownReadings.firstOrNull { it.system == "pinyin" }?.value)
                if (label != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label.text, fontFamily = PlexMono, fontSize = 19.sp, color = eth.ink900)
                        val char = p.char
                        if (label.isReading && tts.available && char != null) {
                            Text("▶", fontSize = 11.sp, color = eth.textFaint,
                                modifier = Modifier.clickable { tts.speak(char) })
                        }
                    }
                }
                p.definition?.let {
                    Text(it, fontSize = 14.sp, lineHeight = 19.sp, color = eth.ink700, fontFamily = InstrumentSans)
                }
                val otherForms = family?.forms.orEmpty().filter { it != componentId }
                if (otherForms.isNotEmpty()) {
                    Text(
                        "also written ${otherForms.joinToString(" ")}",
                        fontFamily = PlexMono, fontSize = 11.sp, color = eth.textFaint,
                    )
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

        val f = family
        if (f == null) {
            Text("Loading…", color = eth.textFaint, fontFamily = InstrumentSans)
        } else {
            val sides = if (role == ComponentRole.MEANING) listOf(ComponentRole.MEANING, ComponentRole.SOUND)
            else listOf(ComponentRole.SOUND, ComponentRole.MEANING)
            for (side in sides) {
                FamilySection(
                    side = side,
                    family = f,
                    componentChar = p.char ?: componentId,
                    startsOpen = role == null || role == side,
                    fromGlyphId = fromGlyphId,
                    eth = eth,
                    onLookup = onLookup,
                )
            }
            if (f.sound.isEmpty() && f.meaning.isEmpty()) {
                Text(
                    "No common characters are built on this part.",
                    fontSize = 12.5.sp, color = eth.textMuted, fontFamily = InstrumentSans,
                )
            }
        }

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** One side of a family — the sound series or the meaning family — under its eyebrow. */
@Composable
private fun FamilySection(
    side: ComponentRole,
    family: ComponentFamily,
    componentChar: String,
    startsOpen: Boolean,
    fromGlyphId: String?,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
) {
    val sound = side == ComponentRole.SOUND
    val groups = if (sound) family.sound else family.meaning
    if (groups.isEmpty()) return
    val total = groups.sumOf { it.members.size }
    var open by rememberSaveable(componentChar, side) { mutableStateOf(startsOpen) }
    val tint = if (sound) eth.orange600 else eth.jade600

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth()
                // Read out as a control with its state, not as a bare "▼".
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (open) "Collapse" else "Expand",
                ) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
        ) {
            SheetEyebrow(
                (if (sound) "SOUND SERIES" else "MEANING FAMILY") + " · $total" + if (open) "" else "  ▼",
            )
        }
        if (!open) return@Column

        // The whole side in a line: the sounds the part stands for, or the one thing it means.
        val single = groups.singleOrNull()
        val summary = when {
            sound -> soundRange(groups).takeIf { groups.size > 1 }
            single != null -> (single.label ?: family.roleGloss)?.let { "as “$it”" }
            else -> null
        }
        summary?.let {
            Text(it, fontFamily = PlexMono, fontSize = 11.sp, color = eth.textMuted)
        }

        for (group in groups) {
            // A side with one unnamed group is a plain list: a heading would only repeat the line above.
            val heading = when {
                group.leftovers -> if (sound) "other sounds" else "other uses"
                sound || groups.size > 1 -> group.label
                else -> null
            }
            FamilyGroupBlock(
                group = group,
                heading = heading,
                note = if (sound) relationNote(group.relation, componentChar) else null,
                tint = tint,
                // In the leftovers a row has to say for itself what it is; a sound row's pinyin
                // already does, a meaning row needs its hint spelled out.
                showHints = !sound && group.leftovers,
                fromGlyphId = fromGlyphId,
                eth = eth,
                onLookup = onLookup,
            )
        }
    }
}

private fun relationNote(relation: SoundRelation?, componentChar: String): String? = when (relation) {
    SoundRelation.SAME -> "same as $componentChar"
    SoundRelation.SAME_INITIAL -> "same start, new ending"
    SoundRelation.RHYME -> "rhymes with $componentChar"
    SoundRelation.DIFFERENT -> "sound has drifted"
    null -> null
}

/** One use of the part — a syllable or a meaning — and its commonest characters. */
@Composable
private fun FamilyGroupBlock(
    group: FamilyGroup,
    heading: String?,
    note: String?,
    tint: Color,
    showHints: Boolean,
    fromGlyphId: String?,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
) {
    var showAll by rememberSaveable(group.label, group.members.size) { mutableStateOf(false) }
    val rows = if (showAll) group.members else visibleMembers(group, FAMILY_GROUP_SHOWN, fromGlyphId)

    Column {
        if (heading != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(heading, fontFamily = PlexMono, fontSize = 14.sp, color = tint, fontWeight = FontWeight.Medium)
                note?.let { Text(it, fontFamily = InstrumentSans, fontSize = 11.5.sp, color = eth.textMuted) }
                Spacer(Modifier.weight(1f))
                Text("${group.members.size}", fontFamily = PlexMono, fontSize = 10.5.sp, color = eth.textFaint)
            }
        }
        for (m in rows) {
            FamilyMemberRow(m, isOrigin = m.glyphId == fromGlyphId, showHint = showHints, eth = eth, onLookup = onLookup)
            HorizontalDivider(color = eth.inkRule)
        }
        if (group.members.size > rows.size) {
            Text(
                "show all ${group.members.size}",
                fontFamily = PlexMono, fontSize = 11.sp, color = eth.textLink,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { showAll = true }.padding(vertical = 6.dp),
            )
        }
    }
}

/** 请 qǐng · to ask · 请问 — the character, and the word it is best known from. The row opens
 * the character; the word on its right opens the word. */
@Composable
private fun FamilyMemberRow(
    m: FamilyMember,
    isOrigin: Boolean,
    showHint: Boolean,
    eth: EthanColors,
    onLookup: (LookupEntry) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            // The character the reader came from, so they can see where it sits — shaded, and
            // said aloud, since a shade alone says nothing to a screen reader.
            .background(if (isOrigin) eth.paper1 else Color.Transparent)
            .clickable(onClickLabel = "Open ${m.char}") { onLookup(LookupEntry(glyphId = m.glyphId, displayText = m.char)) }
            .semantics { if (isOrigin) stateDescription = "the character you came from" }
            .padding(vertical = 5.dp, horizontal = if (isOrigin) 4.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.char, fontSize = 22.sp, color = eth.ink900, fontFamily = NotoCjk)
        m.pinyin?.let { Text(it, fontFamily = PlexMono, fontSize = 12.sp, color = eth.ink700) }
        Text(
            listOfNotNull(m.hint?.takeIf { showHint }?.let { "[$it]" }, m.gloss).joinToString(" "),
            Modifier.weight(1f), fontSize = 12.sp, color = eth.textMuted,
            fontFamily = InstrumentSans, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        m.word?.let { w ->
            // Its own full-height target: a near miss on a 22dp word opened the character.
            Box(
                Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClickLabel = "Open the word ${w.simp}") {
                        onLookup(LookupEntry(wordId = w.id, displayText = w.simp))
                    }
                    .padding(start = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(w.simp, fontSize = 16.sp, color = eth.ink700, fontFamily = NotoCjk)
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
