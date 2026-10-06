package com.hanzidictionary.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.R
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.core.CardPin
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.extractHanzi
import com.hanzidictionary.core.glyphIdOfChar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.List
import androidx.compose.ui.platform.LocalContext

/** Route arguments: CJK chars need percent-encoding (not URLEncoder — it corrupts '+' in
 * glyph ids like "U+597D"). Uri.decode is idempotent on already-decoded strings. */
private fun enc(s: String): String = Uri.encode(s)
private fun dec(s: String): String = Uri.decode(s)

private fun encodeSource(source: DeckSource): String = when (source) {
    is DeckSource.Everything -> "everything"
    is DeckSource.UserList -> "list:${source.listId}:${enc(source.name)}"
    is DeckSource.Encountered -> "encountered"
    is DeckSource.Hsk -> "hsk:${source.level}"
    is DeckSource.Series -> "series:${enc(source.componentId)}"
}

private fun decodeSource(raw: String?): DeckSource {
    val value = raw?.let { dec(it) } ?: return DeckSource.Everything
    val parts = value.split(":")
    return when (parts[0]) {
        "list" -> parts.getOrNull(1)?.toLongOrNull()
            ?.let { DeckSource.UserList(it, parts.drop(2).joinToString(":").let(::dec)) }
            ?: DeckSource.Everything
        "encountered" -> DeckSource.Encountered
        "hsk" -> parts.getOrNull(1)?.toIntOrNull()?.let { DeckSource.Hsk(it) } ?: DeckSource.Everything
        "series" -> parts.drop(1).joinToString(":").let { DeckSource.Series(dec(it)) }
        else -> DeckSource.Everything
    }
}

private suspend fun activeDeckSource(app: HanziApp): DeckSource = withContext(Dispatchers.IO) {
    val activeId = app.studyRepository.activeListId() ?: return@withContext DeckSource.Everything
    val name = app.studyRepository.lists().firstOrNull { it.id == activeId }?.name
    DeckSource.UserList(activeId, name.orEmpty())
}

private enum class Tab { STUDY, DICTIONARY, LIBRARY }

/** Full-screen surfaces layered above the tabs (and the nav bar) without a NavHost. */
private sealed interface AppOverlay {
    data object Settings : AppOverlay
    data object Archive : AppOverlay
    data class ListEdit(val listId: Long) : AppOverlay

    /** Suggested HSK words to sort into "know / don't know" — offered only when the deck is thin. */
    data object Triage : AppOverlay

    /** Retirement candidates, then a spot-check of the archive. */
    data object FinalCheck : AppOverlay
}

@Composable
fun AppRoot(
    repository: HanziRepository,
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    tts: MandarinTts,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    widgetItem: StudyItem? = null,
    onWidgetItemConsumed: () -> Unit = {},
    widgetOpensSheet: Boolean = true,
    staleSessionKey: Int = 0,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val eth = EthanTheme.colors

    var selectedTab by rememberSaveable { mutableStateOf(Tab.STUDY) }

    // Shared search query — lifted so a share intent can prefill it
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Initial deck source for Study tab (set by widget deep link)
    var studySource by remember { mutableStateOf<DeckSource?>(null) }

    // Bumped whenever a widget tap re-pins the study card. PracticeScreen keys its deck
    // (re)load on this so it repositions to the pinned card even when the deck *source* is
    // unchanged — a widget into the already-active list wouldn't otherwise re-open the deck.
    var studyResumeToken by remember { mutableIntStateOf(0) }

    // Library stays composed under an overlay opened from that tab, so closing a final
    // check has to tell it to re-read the archive row. A later visit remounts anyway.
    var libraryReload by remember { mutableIntStateOf(0) }

    // Per-tab lookup stacks: incidental lookups open a sheet over the current tab
    val studyLookupStack = remember { LookupStack() }
    val dictLookupStack = remember { LookupStack() }

    // Full-screen surfaces (Settings, Archive, list editor) layered above the tabs.
    var overlay by remember { mutableStateOf<AppOverlay?>(null) }

    // Load the active deck source on first open
    LaunchedEffect(Unit) {
        if (studySource == null) {
            studySource = withContext(Dispatchers.IO) { activeDeckSource(app) }
        }
    }

    // Stale session: clear pins, query, stacks, go to Study
    LaunchedEffect(staleSessionKey) {
        if (staleSessionKey == 0) return@LaunchedEffect
        app.practiceCardPin = null
        searchQuery = ""
        studyLookupStack.dismiss()
        dictLookupStack.dismiss()
        overlay = null
        selectedTab = Tab.STUDY
    }

    // Shared text intent → Dictionary tab with prefilled query or direct lookup
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            val hanzi = extractHanzi(sharedText)
            when {
                hanzi.size == 1 -> {
                    val gid = glyphIdOfChar(hanzi[0])
                    selectedTab = Tab.DICTIONARY
                    dictLookupStack.push(LookupEntry(glyphId = gid, displayText = hanzi[0].toString()))
                }
                hanzi.isNotEmpty() -> {
                    searchQuery = hanzi.joinToString("")
                    selectedTab = Tab.DICTIONARY
                }
            }
            onSharedTextConsumed()
        }
    }

    // Widget tap → go to Study with the tapped item pinned (and, unless the hand-off says
    // otherwise, its page open over it)
    LaunchedEffect(widgetItem) {
        val item = widgetItem ?: return@LaunchedEffect
        val pin = CardPin(item.itemId, revealed = true)
        app.practiceCardPin = pin
        val loaded = withContext(Dispatchers.IO) {
            val text = repository.itemSummaries(listOf(item)).firstOrNull()?.text
            val activeId = app.studyRepository.activeListId()
            val name = activeId?.let { id ->
                app.studyRepository.lists().firstOrNull { it.id == id }?.name
            }
            val src = if (activeId != null) DeckSource.UserList(activeId, name.orEmpty())
                      else DeckSource.Everything
            text to src
        }
        studySource = loaded.second
        if (widgetOpensSheet) {
            // Open the character/word in a lookup sheet over Study
            val displayText = loaded.first ?: item.itemId
            studyLookupStack.suspendedCardText = displayText
            studyLookupStack.push(
                if (item.isWord) LookupEntry(wordId = item.itemId, displayText = displayText)
                else LookupEntry(glyphId = item.itemId, displayText = displayText)
            )
        } else {
            // Reminder tap: the revealed card alone — clear any sheet left open over Study.
            studyLookupStack.dismiss()
        }
        selectedTab = Tab.STUDY
        studyResumeToken++
        onWidgetItemConsumed()
    }

    // Labels stay hidden to keep the bar condensed, and flash for a moment after any tap so
    // the icon set stays learnable without a permanent strip of text.
    var labelTick by remember { mutableIntStateOf(0) }
    var labelsShown by remember { mutableStateOf(false) }
    LaunchedEffect(labelTick) {
        if (labelTick == 0) return@LaunchedEffect
        labelsShown = true
        kotlinx.coroutines.delay(1500)
        labelsShown = false
    }

    Scaffold(
        containerColor = eth.paper1,
        bottomBar = {
            NavigationBar(
                modifier = Modifier.height(64.dp),
                containerColor = eth.paper0,
                tonalElevation = 0.dp,
            ) {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = eth.accent,
                    selectedTextColor = eth.accent,
                    unselectedIconColor = eth.textFaint,
                    unselectedTextColor = eth.textFaint,
                    indicatorColor = eth.surfaceAccentSoft,
                )
                fun label(text: String): (@Composable () -> Unit)? =
                    if (labelsShown) {
                        { Text(text, fontFamily = InstrumentSans, fontSize = 11.sp) }
                    } else {
                        null
                    }
                NavigationBarItem(
                    selected = selectedTab == Tab.STUDY,
                    onClick = {
                        labelTick++
                        if (selectedTab == Tab.STUDY) studyLookupStack.dismiss()
                        else selectedTab = Tab.STUDY
                    },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "Study") },
                    label = label("Study"),
                    alwaysShowLabel = false,
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.DICTIONARY,
                    onClick = {
                        labelTick++
                        if (selectedTab == Tab.DICTIONARY) dictLookupStack.dismiss()
                        else selectedTab = Tab.DICTIONARY
                    },
                    icon = { Icon(Icons.Outlined.Search, contentDescription = "Dictionary") },
                    label = label("Dictionary"),
                    alwaysShowLabel = false,
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.LIBRARY,
                    onClick = { labelTick++; selectedTab = Tab.LIBRARY },
                    icon = { Icon(Icons.Outlined.List, contentDescription = "Library") },
                    label = label("Library"),
                    alwaysShowLabel = false,
                    colors = itemColors,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                Tab.STUDY -> {
                    val src = studySource ?: DeckSource.Everything
                    PracticeScreen(
                        repository = repository,
                        hskLevels = hskLevels,
                        tts = tts,
                        source = src ?: DeckSource.Everything,
                        resumeToken = studyResumeToken,
                        lookupStack = studyLookupStack,
                        onSwitchToDictionary = { selectedTab = Tab.DICTIONARY },
                        onEditList = { listId -> overlay = AppOverlay.ListEdit(listId) },
                        onAddNewWords = { overlay = AppOverlay.Triage },
                        onFinalCheck = { overlay = AppOverlay.FinalCheck },
                    )
                    if (studyLookupStack.isOpen) {
                        LookupSheet(
                            stack = studyLookupStack,
                            repository = repository,
                            userRepository = userRepository,
                            tts = tts,
                        )
                    }
                }
                Tab.DICTIONARY -> {
                    SearchScreen(
                        repository = repository,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        lookupStack = dictLookupStack,
                        tts = tts,
                    )
                    if (dictLookupStack.isOpen) {
                        LookupSheet(
                            stack = dictLookupStack,
                            repository = repository,
                            userRepository = userRepository,
                            tts = tts,
                        )
                    }
                }
                Tab.LIBRARY -> {
                    LibraryScreen(
                        repository = repository,
                        userRepository = userRepository,
                        hskLevels = hskLevels,
                        onPractice = { src ->
                            studySource = src
                            selectedTab = Tab.STUDY
                        },
                        onLookup = { entry ->
                            dictLookupStack.push(entry)
                            selectedTab = Tab.DICTIONARY
                        },
                        onOpenSettings = { overlay = AppOverlay.Settings },
                        onOpenArchive = { overlay = AppOverlay.Archive },
                        onOpenFinalCheck = { overlay = AppOverlay.FinalCheck },
                        onEditList = { listId -> overlay = AppOverlay.ListEdit(listId) },
                        reloadToken = libraryReload,
                    )
                }
            }
        }
    }

    when (val o = overlay) {
        null -> {}
        AppOverlay.Settings ->
            SettingsScreen(app.settings, repository, onBack = { overlay = null })
        AppOverlay.Archive ->
            ArchiveScreen(
                userRepository = userRepository,
                hskLevels = hskLevels,
                onOpenCharacter = { gid ->
                    dictLookupStack.push(LookupEntry(glyphId = gid))
                    overlay = null
                    selectedTab = Tab.DICTIONARY
                },
                onBack = { overlay = null },
            )
        AppOverlay.Triage ->
            TriageScreen(
                repository = repository,
                // The deck changed under the Study tab; bumping the token reloads it (the
                // pinned card, if any, stays put) so the new words are there on return.
                onDone = {
                    studyResumeToken++
                    overlay = null
                },
            )
        AppOverlay.FinalCheck ->
            FinalCheckScreen(
                repository = repository,
                // Archiving and sending words back both change what's due. The token is
                // what PracticeScreen reloads on; Library reads reloadToken for the same
                // reason, because that tab stays composed under this overlay.
                onDone = {
                    studyResumeToken++
                    libraryReload++
                    overlay = null
                },
            )
        is AppOverlay.ListEdit ->
            ListDetailScreen(
                listId = o.listId,
                onOpenItem = { item ->
                    dictLookupStack.push(
                        if (item.isWord) LookupEntry(wordId = item.itemId)
                        else LookupEntry(glyphId = item.itemId),
                    )
                    overlay = null
                    selectedTab = Tab.DICTIONARY
                },
                onPractice = { src ->
                    studySource = src
                    overlay = null
                    selectedTab = Tab.STUDY
                },
                onBack = { overlay = null },
            )
    }
}
