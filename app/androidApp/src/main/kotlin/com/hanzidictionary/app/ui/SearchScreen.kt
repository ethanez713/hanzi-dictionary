package com.hanzidictionary.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import android.graphics.Paint
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.R
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SearchResult
import com.hanzidictionary.core.SourceInfo
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.WordHit
import com.hanzidictionary.core.charOfGlyphId
import com.hanzidictionary.core.displayGloss
import com.hanzidictionary.core.extractComposeParts
import com.hanzidictionary.core.extractHanzi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: HanziRepository,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenWord: (String) -> Unit,
    onOpenStudy: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showAttributions by remember { mutableStateOf(false) }
    if (showAttributions) {
        AttributionsDialog(repository, onDismiss = { showAttributions = false })
    }

    // Back with an active query clears it instead of exiting the app — mid-search the
    // user's mental "back" is "leave these results", not "leave the dictionary".
    androidx.activity.compose.BackHandler(enabled = query.isNotBlank()) { onQueryChange("") }

    // Paste-on-open (spec §9 capture): if the clipboard holds hanzi when the window
    // gains focus (clipboard is only readable while focused on API 29+), offer it as a
    // one-tap search. Checked once per screen life; declining just means not tapping.
    val clipboard = LocalClipboardManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var clipSuggestion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(windowFocused) {
        if (windowFocused && clipSuggestion == null && query.isBlank()) {
            val text = clipboard.getText()?.text ?: return@LaunchedEffect
            val hanzi = extractHanzi(text)
            if (hanzi.isNotEmpty()) clipSuggestion = hanzi.joinToString("").take(12)
        }
    }

    // The pack includes ultra-rare characters (unranked CJK Ext-B/C/D) that the bundled
    // Noto font has no glyph for; they'd render as tofu boxes. Drop them from results by
    // testing each against Noto so search only ever shows characters we can actually draw.
    val glyphPaint = remember {
        Paint().apply { typeface = ResourcesCompat.getFont(context, R.font.noto_sans_cjk_sc) }
    }

    // Swipe-right anywhere on this screen files the item into the active list, creating
    // the default one the first time so the gesture always has a destination (spec §17).
    var activeListName by remember { mutableStateOf<String?>(null) }
    var listRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(listRefresh) {
        activeListName = withContext(Dispatchers.IO) {
            app.studyRepository.activeListId()
                ?.let { id -> app.studyRepository.lists().firstOrNull { it.id == id }?.name }
        }
    }
    fun fileAway(item: StudyItem, label: String) {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                app.studyRepository.ensureActiveList().also {
                    app.studyRepository.addToList(it.id, item)
                }
            }
            listRefresh++
            PracticeWidgetUpdater.refresh(context)
            val result = snackbar.showSnackbar("Added $label to ${list.name}", actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) {
                    app.studyRepository.removeFromList(list.id, item.itemId)
                }
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    // "From your photos": kick the (watermark-deduped) scan off immediately so the search
    // box is usable while OCR runs; null = still scanning. Re-entering with no new photos
    // costs one MediaStore metadata query and zero OCR.
    var photosGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, photoPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        photosGranted = it
    }
    var rescans by remember { mutableIntStateOf(0) }
    var photoFinds by remember { mutableStateOf<List<SearchResult>?>(null) }
    // (scanned, total) of the current OCR pass, for a determinate progress bar; null before
    // the batch size is known.
    var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // The live (non-dismissed) photo finds, resolved to drawable rows — used both after a
    // scan and to rebuild the feed when an Undo un-dismisses a row.
    suspend fun loadPhotoFinds(): List<SearchResult> = withContext(Dispatchers.IO) {
        val rows = app.userRepository.recentPhotoFinds()
        repository.glyphSummaries(rows.map { it.glyphId }).filter { glyphPaint.hasGlyph(it.char) }
    }
    LaunchedEffect(photosGranted, rescans) {
        if (!photosGranted) return@LaunchedEffect
        photoFinds = null
        scanProgress = null
        withContext(Dispatchers.IO) {
            app.createPhotoScanner().scanNewPhotos { done, total -> scanProgress = done to total }
        }
        photoFinds = loadPhotoFinds()
    }

    // Characters the user recently opened, so the home screen is a quick way back to what
    // they were studying. Re-queried on each entry (navigating back re-enters composition).
    val recentlyViewed by produceState(emptyList<SearchResult>(), rescans) {
        value = withContext(Dispatchers.IO) {
            repository.glyphSummaries(app.userRepository.recentLookups()).filter { glyphPaint.hasGlyph(it.char) }
        }
    }

    // Swipe-left removes a triaged character from the photo feed (already filed to a list, or
    // looked up and discarded), keeping the feed to newly-imported words. An Undo restores it;
    // re-photographing the character brings it back regardless.
    fun dismiss(find: SearchResult) {
        scope.launch {
            val ts = withContext(Dispatchers.IO) { app.userRepository.dismissPhotoWord(find.glyphId) }
            photoFinds = photoFinds?.filterNot { it.glyphId == find.glyphId }
            val result = snackbar.showSnackbar("Removed ${find.char}", actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) { app.userRepository.undismissPhotoWord(find.glyphId, ts) }
                photoFinds = loadPhotoFinds()
            }
        }
    }

    // Component-compose mode (spec §9 / M3.7): the query's CJK characters are read as
    // PARTS and results are the characters containing all of them (氵青 → 清).
    var composeMode by remember { mutableStateOf(false) }
    val composeParts = remember(query) { extractComposeParts(query) }

    // Characters and words are queried together so both sections land in the same frame:
    // if they raced, a words-first frame would put the words header at the top of the
    // LazyColumn and the character rows would then be inserted above the viewport.
    val (results, wordHits) = produceState(
        initialValue = emptyList<SearchResult>() to emptyList<WordHit>(),
        query, composeMode,
    ) {
        val q = query
        value = when {
            q.isBlank() -> emptyList<SearchResult>() to emptyList()
            composeMode && composeParts.isNotEmpty() -> withContext(Dispatchers.IO) {
                repository.composeSearch(composeParts).filter { glyphPaint.hasGlyph(it.char) } to emptyList()
            }
            else -> withContext(Dispatchers.IO) {
                repository.search(q).filter { glyphPaint.hasGlyph(it.char) } to repository.searchWords(q)
            }
        }
    }.value

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Hanzi Dictionary") },
                actions = {
                    TextButton(onClick = onOpenStudy) { Text("Study") }
                    IconButton(onClick = { showAttributions = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Data sources")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Character, pinyin, or English") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
            // The compose-mode chip appears only when the query holds usable parts
            // (spec §9's "compose-search as mode chip").
            if (composeParts.isNotEmpty()) {
                FilterChip(
                    selected = composeMode,
                    onClick = { composeMode = !composeMode },
                    label = { Text(if (composeMode) "Contains parts: ${composeParts.joinToString(" ")}" else "Find by parts") },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            if (query.isBlank()) {
                PhotoFeed(
                    granted = photosGranted,
                    finds = photoFinds,
                    scanProgress = scanProgress,
                    recentlyViewed = recentlyViewed,
                    activeListName = activeListName,
                    clipSuggestion = clipSuggestion,
                    onUseClipboard = { onQueryChange(it) },
                    onAllow = { permissionLauncher.launch(photoPermission) },
                    onRescan = { rescans++ },
                    onOpenCharacter = onOpenCharacter,
                    onFileAway = ::fileAway,
                    onDismiss = ::dismiss,
                )
            } else if (results.isEmpty() && wordHits.isEmpty()) {
                Text(
                    "No results for “$query”.",
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    textAlign = TextAlign.Center,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Spec §9 ranking: characters (exact/pinyin/English) first, then words.
                // The two searches land at different times; if word hits render first,
                // LazyColumn anchors scroll to the words header and character rows added
                // above it end up off-screen — so re-pin to the top on every result change.
                val listState = rememberLazyListState()
                LaunchedEffect(results, wordHits) { listState.scrollToItem(0) }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(results, key = { it.glyphId }) { r ->
                        SwipeRow(
                            rightAction = AddAction,
                            onSwipeRight = { fileAway(StudyItem.character(r.glyphId), r.char) },
                        ) {
                            ItemRow(
                                text = r.char,
                                secondaryText = r.tradChar,
                                pinyin = r.primaryPinyin,
                                gloss = r.definition?.let { displayGloss(it) },
                                onClick = { onOpenCharacter(r.glyphId) },
                            )
                        }
                        HorizontalDivider()
                    }
                    if (wordHits.isNotEmpty()) {
                        item(key = "words-header") {
                            Text(
                                "Words",
                                Modifier.padding(top = 10.dp, bottom = 2.dp),
                                fontSize = 12.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(wordHits, key = { it.id }) { w ->
                            SwipeRow(
                                rightAction = AddAction,
                                onSwipeRight = { fileAway(StudyItem.word(w.id), w.simp) },
                            ) {
                                ItemRow(
                                    text = w.simp,
                                    secondaryText = w.trad.takeIf { it != w.simp },
                                    pinyin = w.pinyin,
                                    gloss = w.gloss,
                                    glossLines = 1,
                                    onClick = { onOpenWord(w.id) },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The opening screen: everything the camera roll turned up, as full rows rather than bare
 * chips — this is the app's primary capture path, so the characters arrive already carrying
 * their sound, and a swipe right files one away to study. Glosses are deliberately absent
 * from both feeds here (see the rows below). finds == null means the scan is still running.
 */
@Composable
private fun PhotoFeed(
    granted: Boolean,
    finds: List<SearchResult>?,
    scanProgress: Pair<Int, Int>?,
    recentlyViewed: List<SearchResult>,
    activeListName: String?,
    clipSuggestion: String?,
    onUseClipboard: (String) -> Unit,
    onAllow: () -> Unit,
    onRescan: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onFileAway: (StudyItem, String) -> Unit,
    onDismiss: (SearchResult) -> Unit,
) {
    val muted = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize()) {
        clipSuggestion?.let { s ->
            item(key = "clipboard") {
                AssistChip(
                    onClick = { onUseClipboard(s) },
                    label = { Text("From clipboard: $s") },
                )
            }
        }
        item(key = "photos-header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("From your photos")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRescan, enabled = granted && finds != null) {
                    Text("Scan again")
                }
            }
        }
        when {
            !granted -> item(key = "grant") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Scan recent photos for characters.", fontSize = 13.sp, color = muted)
                    TextButton(onClick = onAllow) { Text("Allow") }
                }
            }
            finds == null -> item(key = "scanning") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val p = scanProgress
                    if (p != null && p.second > 0) {
                        LinearProgressIndicator(
                            progress = p.first.toFloat() / p.second,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("Scanning photo ${p.first} of ${p.second}…", fontSize = 12.sp, color = muted)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Scanning recent photos…", fontSize = 12.sp, color = muted)
                    }
                }
            }
            finds.isEmpty() -> item(key = "empty") {
                Text(
                    "No characters in recent photos yet. Photograph something with Chinese " +
                        "text on it, then tap Scan again.",
                    fontSize = 13.sp,
                    color = muted,
                )
            }
            else -> {
                item(key = "swipe-hint") {
                    Text(
                        activeListName?.let { "Swipe right to add to “$it”, left to remove." }
                            ?: "Swipe right to study it, left to remove.",
                        fontSize = 12.sp,
                        color = muted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                items(finds, key = { it.glyphId }) { f ->
                    SwipeRow(
                        rightAction = AddAction,
                        leftAction = RemoveAction,
                        onSwipeRight = { onFileAway(StudyItem.character(f.glyphId), f.char) },
                        onSwipeLeft = { onDismiss(f) },
                    ) {
                        // Pinyin only, no gloss: this feed is a triage queue, not a
                        // reading surface — a one-line definition per row turned it into a
                        // wall of English and halved how many characters fit on screen.
                        // The meaning is one tap away on the character page.
                        ItemRow(
                            text = f.char,
                            pinyin = f.primaryPinyin,
                            gloss = null,
                            onClick = { onOpenCharacter(f.glyphId) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        if (recentlyViewed.isNotEmpty()) {
            item(key = "recent-header") {
                SectionTitle("Recently viewed")
            }
            items(recentlyViewed, key = { "recent-${it.glyphId}" }) { r ->
                ItemRow(
                    text = r.char,
                    pinyin = r.primaryPinyin,
                    gloss = null,
                    onClick = { onOpenCharacter(r.glyphId) },
                )
                HorizontalDivider()
            }
        }
        item(key = "hint") {
            Column {
                Text(
                    "Type a character (请), pinyin (qing), or English (feeling).",
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    textAlign = TextAlign.Center,
                    color = muted,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The ⓘ attributions element (spec §15/§16): every data source in the pack with its
 * license, straight from the pack's own source table, plus the bundled font. */
@Composable
private fun AttributionsDialog(repository: HanziRepository, onDismiss: () -> Unit) {
    val sources by produceState(initialValue = emptyList<SourceInfo>()) {
        value = withContext(Dispatchers.IO) { repository.sources() }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Data sources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in sources) {
                    Column {
                        Text(s.name, fontSize = 14.sp)
                        Text(
                            "${s.license} · ${s.version}",
                            fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Scheduling: FSRS-6 (Open Spaced Repetition, MIT). UI typeface: Noto " +
                        "Sans CJK SC (SIL OFL 1.1). App icon strokes: makemeahanzi/" +
                        "hanzi-writer-data, from Arphic KaiTi fonts (Arphic Public " +
                        "License). Full license texts ship in the project's LICENSES/ " +
                        "directory.",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
