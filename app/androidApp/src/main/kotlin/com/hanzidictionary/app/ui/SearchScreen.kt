package com.hanzidictionary.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.res.ResourcesCompat
import android.graphics.Paint
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.R
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.DeckSource
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
import kotlinx.coroutines.delay
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
    onPractice: (DeckSource) -> Unit,
    tts: MandarinTts,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Which density the home feed is in. Saveable so flipping to definitions, opening a
    // character and coming back doesn't silently snap you into tiles again.
    var showDefinitions by rememberSaveable { mutableStateOf(false) }

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
    var activeSource by remember { mutableStateOf<DeckSource>(DeckSource.Everything) }
    var listRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(listRefresh) {
        val loaded = withContext(Dispatchers.IO) {
            val activeId = app.studyRepository.activeListId()
            val name = activeId?.let { id ->
                app.studyRepository.lists().firstOrNull { it.id == id }?.name
            }
            name to (
                if (activeId != null) DeckSource.UserList(activeId, name.orEmpty())
                else DeckSource.Everything
                )
        }
        activeListName = loaded.first
        activeSource = loaded.second
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
    // Photo finds that have been triaged but are still on screen, holding their slot until
    // the feed settles. See [TRIAGE_SETTLE_MS] for why the removal is deferred at all.
    var pending by remember { mutableStateOf<Map<String, PendingTriage>>(emptyMap()) }
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
        // A rescan rebuilds the feed from scratch, which settles everything still pending:
        // the writes already happened, so a triaged character simply doesn't come back.
        pending = emptyMap()
        withContext(Dispatchers.IO) {
            app.createPhotoScanner().scanNewPhotos { done, total -> scanProgress = done to total }
        }
        photoFinds = loadPhotoFinds()
    }
    // Let the triaged cards go. The writes happened when they were swiped, so this decides
    // one thing only: when the grid is allowed to reflow. Every path that gets you here has
    // the same precondition — you are no longer looking at the cards in question.
    fun settleTriage() {
        if (pending.isEmpty()) return
        photoFinds = photoFinds?.filterNot { it.glyphId in pending }
        pending = emptyMap()
    }
    // The backstop: a settle timer, restarted by every swipe and every undo (both mutate
    // `pending`, which is the effect's key). One reflow, once you've stopped, instead of the
    // whole grid shuffling under your finger after each card. The paths below usually get
    // there first — this is for the case where you swipe and then just keep looking.
    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        delay(TRIAGE_SETTLE_MS)
        settleTriage()
    }
    // Searching hides the feed. Coming back to a blank query should be a clean queue, not a
    // screen of tombstones under a timer that has been running the whole time you were
    // reading a definition.
    LaunchedEffect(query.isBlank()) {
        if (query.isNotBlank()) settleTriage()
    }
    // Leaving the app does the same. Navigating *within* it — opening a character page,
    // starting Practice — needs no observer: the destination is disposed, `pending` goes with
    // it, and the rescan on re-entry rebuilds the feed without the characters you triaged.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) settleTriage()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Where the feed was scrolled to, kept across a trip into a character page and back.
    //
    // The LazyColumn's own saved state can't do this on its own: coming back re-runs the photo
    // scan, so the feed is empty at the moment the state is restored, the index clamps to 0,
    // and by the time the rows arrive the position is gone. Tapping a character out of
    // "recently viewed" therefore always dumped you at the top of the screen, several
    // scrolls above the section you were working through. So the anchor is held separately
    // and re-applied once the feed has actually been rebuilt.
    val feedState = rememberLazyListState()
    var anchorIndex by rememberSaveable { mutableIntStateOf(0) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
    val feedReady = photoFinds != null
    LaunchedEffect(feedReady) {
        if (!feedReady) return@LaunchedEffect
        if (anchorIndex > 0 || anchorOffset > 0) feedState.scrollToItem(anchorIndex, anchorOffset)
        // Track from here on, never while the feed is still building — a "position" measured
        // against a placeholder is what overwrote the real one.
        snapshotFlow { feedState.firstVisibleItemIndex to feedState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                anchorIndex = index
                anchorOffset = offset
            }
    }

    // Characters the user recently opened, so the home screen is a quick way back to what
    // they were studying. Re-queried on each entry (navigating back re-enters composition).
    val recentlyViewed by produceState(emptyList<SearchResult>(), rescans) {
        value = withContext(Dispatchers.IO) {
            repository.glyphSummaries(app.userRepository.recentLookups()).filter { glyphPaint.hasGlyph(it.char) }
        }
    }

    // Filing a search hit into the active list. Undo lives in the snackbar here because the
    // row itself doesn't change — nothing leaves the results list when you save from it.
    fun fileAway(item: StudyItem, label: String) {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                app.studyRepository.ensureActiveList().also {
                    app.studyRepository.addToList(it.id, item)
                }
            }
            photoFinds = photoFinds?.filterNot { it.glyphId == item.itemId }
            listRefresh++
            PracticeWidgetUpdater.refresh(context)
            if (snackbar.snack("Added $label to ${list.name}", action = "Undo")) {
                withContext(Dispatchers.IO) {
                    app.studyRepository.removeFromList(list.id, item.itemId)
                }
                photoFinds = loadPhotoFinds()
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    // The photo feed's two verbs. Both write immediately — the character really is filed, or
    // really is out of the queue, and the widget hears about it now — but the card stays put,
    // greyed under an Undo target, until the feed settles. Undo is *on the card* rather than
    // in a snackbar: during a run of swipes a snackbar is showing the previous card's message
    // by the time you notice the mistake, and there is only ever one of it.
    //
    // Filing a character also takes it out of the feed, which is a triage queue: a character
    // you've saved is triaged. `recentPhotoFinds` keeps it out on every later load (and would
    // drop it just the same had it been filed from its own page).
    fun triageAdd(find: SearchResult) {
        scope.launch {
            val item = StudyItem.character(find.glyphId)
            val list = withContext(Dispatchers.IO) {
                app.studyRepository.ensureActiveList().also {
                    app.studyRepository.addToList(it.id, item)
                }
            }
            listRefresh++
            PracticeWidgetUpdater.refresh(context)
            pending = pending + (
                find.glyphId to PendingTriage(AddAction) {
                    withContext(Dispatchers.IO) {
                        app.studyRepository.removeFromList(list.id, item.itemId)
                    }
                    listRefresh++
                    PracticeWidgetUpdater.refresh(context)
                }
                )
        }
    }

    // Swipe-left removes a character from the photo feed without saving it — looked up and
    // discarded, or simply not worth studying. Re-photographing the character brings it back
    // regardless of the undo.
    fun triageDismiss(find: SearchResult) {
        scope.launch {
            val ts = withContext(Dispatchers.IO) { app.userRepository.dismissPhotoWord(find.glyphId) }
            pending = pending + (
                find.glyphId to PendingTriage(RemoveAction) {
                    withContext(Dispatchers.IO) {
                        app.userRepository.undismissPhotoWord(find.glyphId, ts)
                    }
                }
                )
        }
    }

    fun undoTriage(glyphId: String) {
        val triage = pending[glyphId] ?: return
        pending = pending - glyphId          // also restarts the settle timer
        scope.launch { triage.undo() }
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
        snackbarHost = { AppSnackbarHost(snackbar) },
        // Practice is always one tap away, even once the due card has scrolled off the top.
        // It reads "Practice" rather than a due count on purpose: a number that follows you
        // down the screen is a nag, and the home screen shouldn't be keeping score. The
        // Study screen states the backlog for anyone who wants it.
        floatingActionButton = {
            // Two escapes can be live at once here — back to a suspended card, and into a
            // practice session — so they stack, the card on top because it's the more
            // specific of the two and it's the one that expires.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReturnToCardFab()
                if (query.isBlank()) {
                    ExtendedFloatingActionButton(
                        onClick = { onPractice(activeSource) },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        text = { Text("Practice") },
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Hanzi Dictionary") },
                actions = {
                    // A filled button, not a text link: this is the way into the study loop,
                    // which is what the app is *for* — the dictionary is how you feed it.
                    Button(
                        onClick = onOpenStudy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) { Text("Study") }
                    IconButton(onClick = { showAttributions = true }) {
                        Icon(Icons.Filled.Info, contentDescription = "Data sources")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = FEED_EDGE_PADDING)) {
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
                    listState = feedState,
                    granted = photosGranted,
                    finds = photoFinds,
                    pending = pending,
                    scanProgress = scanProgress,
                    recentlyViewed = recentlyViewed,
                    activeListName = activeListName,
                    clipSuggestion = clipSuggestion,
                    showDefinitions = showDefinitions,
                    onToggleDefinitions = { showDefinitions = !showDefinitions },
                    repository = repository,
                    tts = tts,
                    deckRefresh = listRefresh,
                    onOpenWord = onOpenWord,
                    onArchived = { label ->
                        scope.launch { snackbar.snack("Archived $label") }
                    },
                    onUseClipboard = { onQueryChange(it) },
                    onAllow = { permissionLauncher.launch(photoPermission) },
                    onRescan = { rescans++ },
                    onOpenCharacter = onOpenCharacter,
                    onAdd = ::triageAdd,
                    onDismiss = ::triageDismiss,
                    onUndo = ::undoTriage,
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

/** The screen's own horizontal padding. Named because the photo feed has to know how much
 * of the system's gesture strip this already clears (see [PhotoFeed]). */
private val FEED_EDGE_PADDING = 16.dp

/** Characters per row in the compact tile grid. Four rather than five because these tiles are
 * *dragged*, not just tapped: a wider tile lets the finger go down further from the screen
 * edge, and how much room it has on that side is exactly what decides whether the swipe can
 * reach its commit distance (see [SwipeTile]). */
private const val FEED_COLUMNS = 4

/** How long the feed must sit still — no swipe, no undo — before the cards that have been
 * triaged actually leave, when nothing else has settled them first. Long enough to reconsider
 * a mis-swipe, and long enough that a run of quick swipes reflows the grid **once**, at the
 * end, rather than shuffling every remaining tile into a new position under the finger after
 * each one. Leaving the feed — a search, a rescan, another screen, another app — settles it
 * sooner, because by then you are no longer looking at the cards. */
private const val TRIAGE_SETTLE_MS = 10_000L

/** The undo affordance on a triaged card. A glyph rather than a Material icon: the swipe
 * verbs are already drawn as text marks (＋, ✕), and material-icons-extended isn't a
 * dependency. */
private const val UNDO_MARK = "↺"

/**
 * A photo find that has been triaged but is still on screen: the write already happened, the
 * tile is holding its slot greyed out under an Undo target, and [undo] reverses the write if
 * the user takes it back before the feed settles.
 */
private data class PendingTriage(val action: SwipeAction, val undo: suspend () -> Unit)

/**
 * The opening screen: everything the camera roll turned up, plus what you looked at recently.
 *
 * Two densities, one switch. By default each character is a bare **tile** — the glyph and
 * nothing else — because this feed is a triage queue: you are deciding *keep or drop* on
 * characters you photographed minutes ago, and you recognise them by shape. A full-width row
 * per character spent an entire line on one glyph and a word of pinyin. **Show definitions**
 * flips both sections back to rows carrying pinyin *and* meaning, for when you're reading
 * rather than triaging.
 *
 * Section headers stick to the top as you scroll, so a screen of bare glyphs always says
 * which pile you're looking at.
 *
 * A triaged card doesn't vanish: it stays in [finds], listed in [pending], greyed out under
 * an Undo target until the queue settles (see [TRIAGE_SETTLE_MS]). So the grid you are
 * swiping across holds still while you swipe it.
 *
 * finds == null means the scan is still running.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoFeed(
    listState: LazyListState,
    granted: Boolean,
    finds: List<SearchResult>?,
    pending: Map<String, PendingTriage>,
    scanProgress: Pair<Int, Int>?,
    recentlyViewed: List<SearchResult>,
    activeListName: String?,
    clipSuggestion: String?,
    showDefinitions: Boolean,
    onToggleDefinitions: () -> Unit,
    repository: HanziRepository,
    tts: MandarinTts,
    deckRefresh: Int,
    onOpenWord: (String) -> Unit,
    onArchived: (String) -> Unit,
    onUseClipboard: (String) -> Unit,
    onAllow: () -> Unit,
    onRescan: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onAdd: (SearchResult) -> Unit,
    onDismiss: (SearchResult) -> Unit,
    onUndo: (String) -> Unit,
) {
    val muted = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    // Android's back-gesture strip owns the outer ~28dp of both screen edges: a drag that
    // STARTS there belongs to the system, which is why the first and last tile columns could
    // not be swiped at all however far you dragged them — the app never saw the gesture.
    // Inset the feed by whatever this device actually reserves (0 on 3-button navigation) so
    // no tile ever sits under the strip. Reserving it in the feed is better than asking for
    // it back with systemGestureExclusion(), which is capped at 200dp per edge for the whole
    // window — nowhere near a scrolling grid's worth of rows.
    val gestureInsets = WindowInsets.systemGestures.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val edgeInset = maxOf(
        gestureInsets.calculateLeftPadding(layoutDirection),
        gestureInsets.calculateRightPadding(layoutDirection),
    ) - FEED_EDGE_PADDING
    val gestureGap = edgeInset.coerceAtLeast(0.dp)
    // Bottom inset so the last row of tiles can be scrolled clear of the floating Practice
    // button instead of sitting permanently underneath it.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = gestureGap, end = gestureGap, bottom = 76.dp),
    ) {
        clipSuggestion?.let { s ->
            item(key = "clipboard") {
                AssistChip(
                    onClick = { onUseClipboard(s) },
                    label = { Text("From clipboard: $s") },
                )
            }
        }
        // The due card leads the feed: the loop is photograph → file → review, and this is
        // the step that used to be invisible from here. It sits *in* the scroll rather than
        // pinned above it, so looking something up never means dismissing a flashcard first.
        item(key = "due-card") {
            HomeDueCard(
                repository = repository,
                tts = tts,
                refreshKey = deckRefresh,
                onOpenCharacter = onOpenCharacter,
                onOpenWord = onOpenWord,
                onArchived = onArchived,
            )
        }
        stickyHeader(key = "photos-header") {
            FeedHeader("From your photos") {
                // The one control that changes what every row below looks like sits in the
                // header that's pinned while you scroll them.
                FilterChip(
                    selected = showDefinitions,
                    onClick = onToggleDefinitions,
                    label = { Text("Definitions", fontSize = 12.sp) },
                )
                IconButton(onClick = onRescan, enabled = granted && finds != null) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Scan photos again")
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
                        "text on it, then tap ↻.",
                    fontSize = 13.sp,
                    color = muted,
                )
            }
            else -> {
                item(key = "swipe-hint") {
                    val destination = activeListName?.let { "“$it”" } ?: "your list"
                    Text(
                        if (showDefinitions) {
                            "Swipe right to add to $destination, left to remove. Tap $UNDO_MARK to undo."
                        } else {
                            "Drag a character to the edge of the screen — right adds it to " +
                                "$destination, left removes it. Tap $UNDO_MARK to undo."
                        },
                        fontSize = 12.sp,
                        color = muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (showDefinitions) {
                    items(finds, key = { it.glyphId }) { f ->
                        val triaged = pending[f.glyphId]
                        if (triaged != null) {
                            ItemRow(
                                text = f.char,
                                pinyin = f.primaryPinyin,
                                gloss = f.definition?.let { displayGloss(it) },
                                glossLines = 1,
                                dimmed = true,
                                onClick = { onUndo(f.glyphId) },
                                trailing = {
                                    TextButton(onClick = { onUndo(f.glyphId) }) {
                                        Text("$UNDO_MARK Undo", color = triaged.action.color())
                                    }
                                },
                            )
                        } else {
                            SwipeRow(
                                rightAction = AddAction,
                                leftAction = RemoveAction,
                                onSwipeRight = { onAdd(f) },
                                onSwipeLeft = { onDismiss(f) },
                            ) {
                                ItemRow(
                                    text = f.char,
                                    pinyin = f.primaryPinyin,
                                    gloss = f.definition?.let { displayGloss(it) },
                                    glossLines = 1,
                                    onClick = { onOpenCharacter(f.glyphId) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                } else {
                    // Chunked into fixed rows rather than a LazyVerticalGrid: a grid can't
                    // nest inside this LazyColumn (unbounded height), and the column is what
                    // gives the section headers their stickiness.
                    items(finds.chunked(FEED_COLUMNS), key = { it.first().glyphId }) { row ->
                        TileRow(row) { f ->
                            val triaged = pending[f.glyphId]
                            if (triaged != null) {
                                TriagedTile(f.char, triaged.action) { onUndo(f.glyphId) }
                            } else {
                                SwipeTile(
                                    key = f.glyphId,
                                    rightAction = AddAction,
                                    leftAction = RemoveAction,
                                    onSwipeRight = { onAdd(f) },
                                    onSwipeLeft = { onDismiss(f) },
                                ) {
                                    CharacterTile(f.char) { onOpenCharacter(f.glyphId) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (recentlyViewed.isNotEmpty()) {
            stickyHeader(key = "recent-header") { FeedHeader("Recently viewed") }
            if (showDefinitions) {
                items(recentlyViewed, key = { "recent-${it.glyphId}" }) { r ->
                    ItemRow(
                        text = r.char,
                        pinyin = r.primaryPinyin,
                        gloss = r.definition?.let { displayGloss(it) },
                        glossLines = 1,
                        onClick = { onOpenCharacter(r.glyphId) },
                    )
                    HorizontalDivider()
                }
            } else {
                // No swipe verbs here: this section is a way back to what you were reading,
                // not a queue to triage.
                items(
                    recentlyViewed.chunked(FEED_COLUMNS),
                    key = { "recent-${it.first().glyphId}" },
                ) { row ->
                    TileRow(row) { r -> CharacterTile(r.char) { onOpenCharacter(r.glyphId) } }
                }
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

/** A pinned section header. Opaque on purpose — a transparent sticky header lets the tiles
 * scroll through the title behind it. */
@Composable
private fun FeedHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
            .padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(title)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** One row of the tile grid, padded out so a short last row's tiles keep their size instead
 * of stretching to fill the width. */
@Composable
private fun <T> TileRow(items: List<T>, tile: @Composable (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (item in items) Box(Modifier.weight(1f)) { tile(item) }
        repeat(FEED_COLUMNS - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A character on its own — the whole tile. Square so the grid reads as a grid. */
@Composable
private fun CharacterTile(char: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(char, fontSize = 30.sp)
        }
    }
}

/**
 * A tile whose character has been triaged and is waiting to leave.
 *
 * It keeps its slot, and the character stays visible under the wash — faded, not replaced, so
 * you can still see *which* one you just swiped — with the undo mark over it in the colour of
 * the verb that fired: green for added, red for removed. Tapping anywhere on it takes the
 * action back.
 */
@Composable
private fun TriagedTile(char: String, action: SwipeAction, onUndo: () -> Unit) {
    val actionColor = action.color()
    Card(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .clickable(onClickLabel = "Undo") { onUndo() },
    ) {
        Box(
            Modifier.fillMaxSize().background(actionColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(char, fontSize = 30.sp, color = LocalContentColor.current.copy(alpha = 0.28f))
            Text(UNDO_MARK, fontSize = 30.sp, color = actionColor.copy(alpha = 0.85f))
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
