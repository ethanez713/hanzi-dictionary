package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.FinalCheckStatus
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ListStats
import com.hanzidictionary.core.StudyList
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.finalCheckStatusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LibraryState(
    val lists: List<StudyList> = emptyList(),
    val stats: Map<Long, ListStats> = emptyMap(),
    val activeId: Long? = null,
    val encounteredCount: Int = 0,
    val archivedCount: Int = 0,
    val reviewsToday: Long = 0,
    val finalCheck: FinalCheckStatus? = null,
)

/**
 * Library tab: all study lists, drill pools, archive, and session progress in one surface.
 * Lists can be created, renamed, deleted, and set active (where swipe-right files things).
 * Tapping a list or pool navigates to Practice via [onPractice].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    onPractice: (DeckSource) -> Unit,
    onLookup: (LookupEntry) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenFinalCheck: () -> Unit,
    onEditList: (Long) -> Unit,
    reloadToken: Int = 0,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val eth = EthanTheme.colors

    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(LibraryState()) }
    var showNewList by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    LaunchedEffect(refresh, reloadToken) {
        state = withContext(Dispatchers.IO) {
            val lists = app.studyRepository.lists()
            val stats = app.studyRepository.statsByList()
            val activeId = app.studyRepository.activeListId()
            val encCount = userRepository.practiceQueue().size
            val archived = userRepository.knownIds().size
            val reviews = app.studyRepository.reviewsToday()
            val finalCheck = app.studyRepository.finalCheckStatus()
            LibraryState(lists, stats, activeId, encCount, archived, reviews, finalCheck)
        }
    }

    if (showNewList) {
        AlertDialog(
            onDismissRequest = { showNewList = false; newListName = "" },
            title = { Text("New list") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newListName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val id = app.studyRepository.createList(name)
                                    if (state.activeId == null) app.studyRepository.setActiveListId(id)
                                }
                                refresh++
                                PracticeWidgetUpdater.refresh(context)
                            }
                        }
                        showNewList = false
                        newListName = ""
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewList = false; newListName = "" }) { Text("Cancel") }
            },
        )
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
                    Text(
                        "Library",
                        fontFamily = Piazzolla,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = eth.ink900,
                    )
                },
                actions = {
                    IconButton(onClick = { showNewList = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New list", tint = eth.textFaint)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = eth.textFaint)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // ── LISTS ────────────────────────────────────────────────────────────────
            LibrarySectionHeader("Lists")

            if (state.lists.isEmpty()) {
                Text(
                    "No lists yet. Tap ＋ to create one.",
                    color = eth.textFaint,
                    fontFamily = InstrumentSans,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                for (list in state.lists) {
                    val stats = state.stats[list.id]
                    val isActive = list.id == state.activeId
                    ListRow(
                        list = list,
                        stats = stats,
                        isActive = isActive,
                        onPractice = { onPractice(DeckSource.UserList(list.id, list.name)) },
                        onEdit = { onEditList(list.id) },
                        onSetActive = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    app.studyRepository.setActiveListId(if (isActive) null else list.id)
                                }
                                refresh++
                                PracticeWidgetUpdater.refresh(context)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                val name = list.name
                                withContext(Dispatchers.IO) { app.studyRepository.deleteList(list.id) }
                                refresh++
                                PracticeWidgetUpdater.refresh(context)
                                snackbar.snack("Deleted \"$name\"")
                            }
                        },
                    )
                    HorizontalDivider(color = eth.inkRule)
                }
            }

            // Everything pooled
            PoolRow(
                label = "Everything",
                sublabel = "All lists combined",
                onClick = { onPractice(DeckSource.Everything) },
            )
            HorizontalDivider(color = eth.inkRule)

            Spacer(Modifier.height(8.dp))

            // ── DRILLS & POOLS ───────────────────────────────────────────────────────
            LibrarySectionHeader("Drills & Pools")

            // Encountered (photo finds / lookups not yet in a list)
            if (state.encounteredCount > 0) {
                PoolRow(
                    label = "Encountered",
                    sublabel = "${state.encounteredCount} characters from lookups and photos",
                    onClick = { onPractice(DeckSource.Encountered) },
                )
                HorizontalDivider(color = eth.inkRule)
            }

            // HSK bands
            val hskBands = listOf(1 to "HSK 1", 2 to "HSK 2", 3 to "HSK 3",
                4 to "HSK 4", 5 to "HSK 5", 6 to "HSK 6", 7 to "HSK 7-9")
            for ((level, label) in hskBands) {
                val count = hskLevels[level]?.size ?: 0
                if (count > 0) {
                    PoolRow(
                        label = label,
                        sublabel = "$count characters",
                        onClick = { onPractice(DeckSource.Hsk(level)) },
                    )
                    HorizontalDivider(color = eth.inkRule)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── ARCHIVE ──────────────────────────────────────────────────────────────
            LibrarySectionHeader("Archive")
            PoolRow(
                label = "Final check",
                sublabel = state.finalCheck?.let(::finalCheckStatusLabel) ?: "…",
                onClick = onOpenFinalCheck,
            )
            HorizontalDivider(color = eth.inkRule)
            PoolRow(
                label = "Archived characters",
                sublabel = "${state.archivedCount} characters marked as known",
                onClick = onOpenArchive,
            )
            HorizontalDivider(color = eth.inkRule)

            Spacer(Modifier.height(8.dp))

            // ── PROGRESS ─────────────────────────────────────────────────────────────
            LibrarySectionHeader("Progress")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StatCell(value = state.reviewsToday.toString(), label = "reviews today")
                StatCell(value = state.archivedCount.toString(), label = "archived")
                StatCell(value = state.lists.sumOf { state.stats[it.id]?.total ?: 0 }.toString(), label = "in lists")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LibrarySectionHeader(title: String) {
    val eth = EthanTheme.colors
    Text(
        title.uppercase(),
        fontFamily = PlexMono,
        fontSize = 10.sp,
        letterSpacing = 0.1.sp,
        color = eth.textFaint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ListRow(
    list: StudyList,
    stats: ListStats?,
    isActive: Boolean,
    onPractice: () -> Unit,
    onEdit: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
) {
    val eth = EthanTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    list.name,
                    fontFamily = InstrumentSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = eth.textBody,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Text(
                        "active",
                        fontFamily = PlexMono,
                        fontSize = 9.sp,
                        color = eth.textAccent,
                        letterSpacing = 0.1.sp,
                    )
                }
            }
            if (stats != null) {
                Text(
                    buildString {
                        if (stats.newItems > 0) append("${stats.newItems} new  ")
                        if (stats.due > 0) append("${stats.due} due  ")
                        append("${stats.learning} learning")
                    }.trim(),
                    fontFamily = PlexMono,
                    fontSize = 11.sp,
                    color = eth.textFaint,
                )
            }
        }
        IconButton(onClick = onPractice) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Practice", tint = eth.textFaint)
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "List options", tint = eth.textFaint)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Edit list") },
                    onClick = { showMenu = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(if (isActive) "Unset as active" else "Set as active") },
                    onClick = { showMenu = false; onSetActive() },
                )
                DropdownMenuItem(
                    text = { Text("Delete list") },
                    onClick = { showMenu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun PoolRow(label: String, sublabel: String, onClick: () -> Unit) {
    val eth = EthanTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = eth.textBody)
            Text(sublabel, fontFamily = PlexMono, fontSize = 11.sp, color = eth.textFaint)
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = eth.textFaint)
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    val eth = EthanTheme.colors
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, fontFamily = PlexMono, fontSize = 20.sp, color = eth.textBody, fontWeight = FontWeight.Normal)
        Text(label, fontFamily = InstrumentSans, fontSize = 11.sp, color = eth.textFaint)
    }
}
