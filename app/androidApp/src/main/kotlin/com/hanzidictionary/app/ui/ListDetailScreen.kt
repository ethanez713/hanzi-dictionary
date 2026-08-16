package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.CardState
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.StudyItem
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which column drives the "Studying" sort — each has its own tap target next to the count. */
private enum class MissedSort { COUNT, PERCENT }

/** Missed share of reviews, rounded. 0 (rather than null) for an ungraded card, so it sorts
 * to the quiet end of either direction alongside actually-perfect cards. */
private fun missedPercent(card: CardState?): Int {
    if (card == null || card.reviews == 0) return 0
    return (card.lapses * 100.0 / card.reviews).roundToInt()
}

/** Everything one [LaunchedEffect] pass loads, so a refresh swaps it atomically. */
private data class Loaded(
    val name: String,
    val learning: List<ItemSummary>,
    val archived: List<ItemSummary>,
    /** Keyed by item id — the per-word knew/missed trailing each row in "Studying". */
    val cardStates: Map<String, CardState>,
)

/**
 * One list's contents (spec §17). Swipe follows the app-wide grammar: **right archives**
 * (marks known, everywhere), **left removes** from this list only — two very different
 * things that the old single "I know this" button conflated.
 *
 * Archived members stay visible at the bottom rather than vanishing: the list is a record
 * of what you studied, and seeing the pile grow is the reward.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(
    listId: Long,
    onOpenItem: (StudyItem) -> Unit,
    onPractice: (DeckSource) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var learning by remember { mutableStateOf<List<ItemSummary>>(emptyList()) }
    var archived by remember { mutableStateOf<List<ItemSummary>>(emptyList()) }
    var cardStates by remember { mutableStateOf<Map<String, CardState>>(emptyMap()) }
    // Percent, most-missed-first by default: rate matters more than raw count for deciding
    // what's actually shaky (a word reviewed twice and missed once outranks one reviewed 50
    // times and missed five).
    var missedSortField by remember { mutableStateOf(MissedSort.PERCENT) }
    var missedDescending by remember { mutableStateOf(true) }
    var showArchived by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(listId, refresh) {
        val loaded = withContext(Dispatchers.IO) {
            val list = app.studyRepository.lists().firstOrNull { it.id == listId }
            val items = app.studyRepository.itemsInList(listId)
            val known = app.userRepository.knownIds()
            val summaries = app.repository.itemSummaries(items)
            Loaded(
                name = list?.name.orEmpty(),
                learning = summaries.filter { it.item.itemId !in known },
                archived = summaries.filter { it.item.itemId in known },
                cardStates = app.studyRepository.cardStates(items.map { it.itemId }),
            )
        }
        name = loaded.name
        learning = loaded.learning
        archived = loaded.archived
        cardStates = loaded.cardStates
    }

    fun archive(summary: ItemSummary) {
        scope.launch {
            withContext(Dispatchers.IO) {
                app.userRepository.setKnown(summary.item.itemId, true, summary.item.kind)
            }
            refresh++
            PracticeWidgetUpdater.refresh(context)
            if (snackbar.snack("Archived ${summary.text}", action = "Undo")) {
                withContext(Dispatchers.IO) {
                    app.userRepository.setKnown(summary.item.itemId, false, summary.item.kind)
                }
                refresh++
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    fun remove(summary: ItemSummary) {
        scope.launch {
            withContext(Dispatchers.IO) {
                app.studyRepository.removeFromList(listId, summary.item.itemId)
            }
            refresh++
            PracticeWidgetUpdater.refresh(context)
            if (snackbar.snack("Removed ${summary.text}", action = "Undo")) {
                withContext(Dispatchers.IO) {
                    app.studyRepository.addToList(listId, summary.item)
                }
                refresh++
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    val missedSortKey: (ItemSummary) -> Int = { summary ->
        val card = cardStates[summary.item.itemId]
        if (missedSortField == MissedSort.COUNT) card?.lapses ?: 0 else missedPercent(card)
    }
    val sortedLearning = if (missedDescending) {
        learning.sortedByDescending(missedSortKey)
    } else {
        learning.sortedBy(missedSortKey)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { AppSnackbarHost(snackbar) },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { "List" }) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = { onPractice(DeckSource.UserList(listId, name)) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text("Practice")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "List options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete list") },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (learning.isEmpty() && archived.isEmpty()) {
                item {
                    Text(
                        "Nothing in this list yet. Swipe right on a character or word " +
                            "anywhere in the app to file it here.",
                        Modifier.padding(top = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (learning.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle("Studying (${learning.size})")
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            MissedSortToggle(
                                label = "#",
                                field = MissedSort.COUNT,
                                active = missedSortField,
                                descending = missedDescending,
                            ) {
                                if (missedSortField == MissedSort.COUNT) missedDescending = !missedDescending
                                else missedSortField = MissedSort.COUNT
                            }
                            MissedSortToggle(
                                label = "%",
                                field = MissedSort.PERCENT,
                                active = missedSortField,
                                descending = missedDescending,
                            ) {
                                if (missedSortField == MissedSort.PERCENT) missedDescending = !missedDescending
                                else missedSortField = MissedSort.PERCENT
                            }
                        }
                    }
                }
            }
            items(sortedLearning, key = { it.item.itemId }) { summary ->
                SwipeRow(
                    rightAction = ArchiveAction,
                    leftAction = RemoveAction,
                    onSwipeRight = { archive(summary) },
                    onSwipeLeft = { remove(summary) },
                ) {
                    ItemRow(
                        text = summary.text,
                        secondaryText = summary.secondaryText,
                        pinyin = summary.pinyin,
                        gloss = summary.gloss,
                        glossLines = 1,
                        onClick = { onOpenItem(summary.item) },
                        trailing = { KnewMissedTrailing(cardStates[summary.item.itemId]) },
                    )
                }
                HorizontalDivider()
            }
            if (archived.isNotEmpty()) {
                item {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text("Archived (${archived.size})")
                        Icon(
                            if (showArchived) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                }
                if (showArchived) {
                    items(archived, key = { "archived-${it.item.itemId}" }) { summary ->
                        SwipeRow(
                            leftAction = RemoveAction,
                            onSwipeLeft = { remove(summary) },
                        ) {
                            ItemRow(
                                text = summary.text,
                                secondaryText = summary.secondaryText,
                                pinyin = summary.pinyin,
                                gloss = summary.gloss,
                                glossLines = 1,
                                dimmed = true,
                                onClick = { onOpenItem(summary.item) },
                                trailing = {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (renaming) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename list") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val newName = draft.trim()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.studyRepository.renameList(listId, newName)
                            }
                            renaming = false
                            refresh++
                        }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “$name”?") },
            text = {
                Text(
                    "The list goes away. Nothing you've archived as known is lost, and the " +
                        "characters themselves stay in the dictionary.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.studyRepository.deleteList(listId) }
                        confirmDelete = false
                        PracticeWidgetUpdater.refresh(context)
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Right-justified per-word knew/missed, at a glance next to every card in "Studying" — the
 * signal for which words are safe to archive versus still shaky. Blank until the card has
 * been graded at least once; a word you haven't reviewed yet has no knew/missed to report.
 */
@Composable
private fun KnewMissedTrailing(card: CardState?) {
    if (card == null || card.reviews == 0) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✓${card.recalled}", style = MaterialTheme.typography.bodyMedium, color = KnewAction.color())
        Text("✗${card.lapses}", style = MaterialTheme.typography.bodyMedium, color = MissedAction.color())
        Text(
            "(${missedPercent(card)}%)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One column's tap target in the "Studying" sort header — bold with an arrow when it's the
 * active sort, a plain label otherwise. Tapping the active column flips direction; tapping
 * the other one switches to it (keeping the current direction). */
@Composable
private fun MissedSortToggle(
    label: String,
    field: MissedSort,
    active: MissedSort,
    descending: Boolean,
    onClick: () -> Unit,
) {
    val isActive = field == active
    val color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClickLabel = "Sort by missed $label") { onClick() }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
        if (isActive) {
            Icon(
                if (descending) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = color,
            )
        }
    }
}
