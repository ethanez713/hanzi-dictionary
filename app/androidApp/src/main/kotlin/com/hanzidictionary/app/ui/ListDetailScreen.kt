package com.hanzidictionary.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.CardState
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.Mastery
import com.hanzidictionary.core.MasteryLevel
import com.hanzidictionary.core.StudyItem
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ways a list's "Studying" items can be ordered, each a single menu entry. */
private enum class ListSort(val label: String) {
    RECENT("Recently added"),
    OLDEST("Oldest added"),
    MISSED_PCT("Most missed (%)"),
    MISSED_COUNT("Most missed (#)"),
}

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
    /** Keyed by item id; only items with a schedule appear. Drives the learned/ready tags. */
    val mastery: Map<String, Mastery>,
    /** Item id -> when it was filed into the list, for the "date added" sort. */
    val addedAt: Map<String, Long>,
)

/**
 * One list's contents (spec §17). Deliberately **no swipe gestures** here: a list is a
 * place to browse, and a stray drag while scrolling it was too easy to mistake for a
 * verdict. Changes happen in an explicit edit mode (three-dots menu) where each row gets a
 * checkbox and the bar offers **Archive** (marks known, everywhere) or **Remove** (from this
 * list only) for the whole selection — two very different things the old single "I know
 * this" button conflated.
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
    var mastery by remember { mutableStateOf<Map<String, Mastery>>(emptyMap()) }
    var addedAt by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var readyOnly by remember { mutableStateOf(false) }
    // Default to most-recently-added: building a list, the thing you just filed is what you
    // want to see. Missed-rate sorts stay available for deciding what's shaky.
    var sort by remember { mutableStateOf(ListSort.RECENT) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Back leaves edit mode first, rather than dropping the whole screen with a selection.
    BackHandler(enabled = editing) { editing = false; selected = emptySet() }

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
                mastery = app.studyRepository.masteryFor(items.map { it.itemId }),
                addedAt = app.studyRepository.addedAtInList(listId),
            )
        }
        name = loaded.name
        learning = loaded.learning
        archived = loaded.archived
        cardStates = loaded.cardStates
        mastery = loaded.mastery
        addedAt = loaded.addedAt
    }

    /** "Archived Foo" for one, "Archived 3 items" for several. */
    fun describe(verb: String, items: List<ItemSummary>) =
        if (items.size == 1) "$verb ${items[0].text}" else "$verb ${items.size} items"

    fun archive(items: List<ItemSummary>) {
        if (items.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                for (i in items) app.userRepository.setKnown(i.item.itemId, true, i.item.kind)
            }
            selected = emptySet()
            refresh++
            PracticeWidgetUpdater.refresh(context)
            if (snackbar.snack(describe("Archived", items), action = "Undo")) {
                withContext(Dispatchers.IO) {
                    for (i in items) app.userRepository.setKnown(i.item.itemId, false, i.item.kind)
                }
                refresh++
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    fun remove(items: List<ItemSummary>) {
        if (items.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                for (i in items) app.studyRepository.removeFromList(listId, i.item.itemId)
            }
            selected = emptySet()
            refresh++
            PracticeWidgetUpdater.refresh(context)
            if (snackbar.snack(describe("Removed", items), action = "Undo")) {
                withContext(Dispatchers.IO) {
                    for (i in items) app.studyRepository.addToList(listId, i.item)
                }
                refresh++
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    val sortedLearning = when (sort) {
        ListSort.RECENT -> learning.sortedByDescending { addedAt[it.item.itemId] ?: 0L }
        ListSort.OLDEST -> learning.sortedBy { addedAt[it.item.itemId] ?: 0L }
        ListSort.MISSED_PCT -> learning.sortedByDescending { missedPercent(cardStates[it.item.itemId]) }
        ListSort.MISSED_COUNT -> learning.sortedByDescending { cardStates[it.item.itemId]?.lapses ?: 0 }
    }

    // Suggestions only: archiving stays the user's own swipe (see Mastery.kt for what "ready"
    // has to mean, and why one correct answer never gets a card here).
    val readyCount = learning.count { mastery[it.item.itemId]?.level == MasteryLevel.RETIRE_READY }
    val showReadyOnly = readyOnly && readyCount > 0
    val shownLearning = if (showReadyOnly) {
        sortedLearning.filter { mastery[it.item.itemId]?.level == MasteryLevel.RETIRE_READY }
    } else {
        sortedLearning
    }

    // Act only on rows actually on screen: a selection made before flipping the "ready"
    // filter or collapsing Archived must not quietly carry hidden rows into a bulk remove.
    val shownArchived = if (showArchived) archived else emptyList()
    val selectedLearning = shownLearning.filter { it.item.itemId in selected }
    val selectedArchived = shownArchived.filter { it.item.itemId in selected }
    val selectedCount = selectedLearning.size + selectedArchived.size
    val allShownSelected = selectedCount == shownLearning.size + shownArchived.size

    fun toggle(summary: ItemSummary) {
        val id = summary.item.itemId
        selected = if (id in selected) selected - id else selected + id
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            if (editing) {
                TopAppBar(
                    title = { Text(if (selectedCount == 0) "Select items" else "$selectedCount selected") },
                    navigationIcon = {
                        IconButton(onClick = { editing = false; selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Done editing")
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                selected = if (allShownSelected) emptySet()
                                else (shownLearning + shownArchived).map { it.item.itemId }.toSet()
                            },
                        ) { Text(if (allShownSelected && selectedCount > 0) "Select none" else "Select all") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(name.ifBlank { "List" }) },
                    navigationIcon = { BackButton(onBack) },
                    actions = {
                        TextButton(onClick = { onPractice(DeckSource.UserList(listId, name)) }) {
                            Text("▶ Practice")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "List options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = { menuOpen = false; editing = true },
                            )
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
                )
            }
        },
        bottomBar = {
            if (editing) {
                BottomAppBar {
                    // Archive is meaningless for a row already archived, so it counts (and
                    // enables on) only the Studying part of the selection.
                    TextButton(
                        enabled = selectedLearning.isNotEmpty(),
                        onClick = { archive(selectedLearning) },
                    ) {
                        Text(
                            "${ArchiveAction.icon} Archive" +
                                if (selectedLearning.isNotEmpty()) " (${selectedLearning.size})" else "",
                            color = if (selectedLearning.isNotEmpty()) ArchiveAction.color
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = selectedCount > 0,
                        onClick = { remove(selectedLearning + selectedArchived) },
                    ) {
                        Text(
                            "${RemoveAction.icon} Remove" +
                                if (selectedCount > 0) " ($selectedCount)" else "",
                            color = if (selectedCount > 0) RemoveAction.color
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (learning.isEmpty() && archived.isEmpty()) {
                item {
                    Text(
                        "Nothing in this list yet. Swipe right on a character or word " +
                            "anywhere in the app to file it here.",
                        Modifier.padding(top = 32.dp),
                        fontSize = 14.sp,
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
                        Box {
                            TextButton(onClick = { sortMenuOpen = true }) {
                                Text("${sort.label} ▾", fontSize = 13.sp)
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                for (option in ListSort.entries) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option.label,
                                                fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = { sort = option; sortMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (readyCount > 0) {
                item {
                    TextButton(onClick = { readyOnly = !readyOnly }) {
                        Text(
                            if (showReadyOnly) "Ready to archive · $readyCount — show all"
                            else "Ready to archive · $readyCount",
                            fontSize = 13.sp,
                            color = KnewAction.color,
                        )
                    }
                }
            }
            items(shownLearning, key = { it.item.itemId }) { summary ->
                SelectableRow(editing, summary.item.itemId in selected, { toggle(summary) }) {
                    ItemRow(
                        text = summary.text,
                        secondaryText = summary.secondaryText,
                        pinyin = summary.pinyin,
                        gloss = summary.gloss,
                        glossLines = 1,
                        onClick = { if (editing) toggle(summary) else onOpenItem(summary.item) },
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MasteryTag(mastery[summary.item.itemId])
                                KnewMissedTrailing(cardStates[summary.item.itemId])
                            }
                        },
                    )
                }
                HorizontalDivider()
            }
            if (archived.isNotEmpty()) {
                item {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(
                            if (showArchived) "Archived (${archived.size}) ▴"
                            else "Archived (${archived.size}) ▾",
                        )
                    }
                }
                if (showArchived) {
                    items(archived, key = { "archived-${it.item.itemId}" }) { summary ->
                        SelectableRow(editing, summary.item.itemId in selected, { toggle(summary) }) {
                            ItemRow(
                                text = summary.text,
                                secondaryText = summary.secondaryText,
                                pinyin = summary.pinyin,
                                gloss = summary.gloss,
                                glossLines = 1,
                                dimmed = true,
                                onClick = { if (editing) toggle(summary) else onOpenItem(summary.item) },
                                trailing = {
                                    Text(
                                        "✓",
                                        fontSize = 15.sp,
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

/** A row that grows a leading checkbox in edit mode; outside it the row is untouched, so
 * browsing keeps its full width. Tapping the checkbox or the row toggles the same selection. */
@Composable
private fun SelectableRow(
    editing: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    row: @Composable () -> Unit,
) {
    if (!editing) {
        row()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Box(Modifier.weight(1f)) { row() }
    }
}

/** A one-word verdict from [masteryOf]: ready to archive, merely learned, or a card that
 * keeps getting missed. Silent for everything still unremarkable. */
@Composable
private fun MasteryTag(m: Mastery?) {
    val (label, color) = when {
        m == null -> return
        m.level == MasteryLevel.RETIRE_READY -> "ready" to KnewAction.color
        m.level == MasteryLevel.LEARNED -> "learned" to MaterialTheme.colorScheme.onSurfaceVariant
        m.struggling -> "struggling" to MissedAction.color
        else -> return
    }
    Text(label, fontSize = 11.sp, color = color)
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
        Text("✓${card.recalled}", fontSize = 13.sp, color = KnewAction.color)
        Text("✗${card.lapses}", fontSize = 13.sp, color = MissedAction.color)
        Text(
            "(${missedPercent(card)}%)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

