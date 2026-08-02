package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.hanzidictionary.core.DeckSource
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.ListStats
import com.hanzidictionary.core.ReviewStats
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.StudyList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screen loads in one IO pass, so a refresh swaps it atomically. */
private data class ListsState(
    val lists: List<StudyList> = emptyList(),
    val stats: Map<Long, ListStats> = emptyMap(),
    val activeId: Long? = null,
    val encounteredCount: Int = 0,
    val dueNow: Int = 0,
    val today: ReviewStats = ReviewStats(0, 0),
    val week: ReviewStats = ReviewStats(0, 0),
    val allTime: ReviewStats = ReviewStats(0, 0),
    val archived: Long = 0,
    /** The items with the most lapses, already resolved to display text. */
    val mostMissed: List<Pair<ItemSummary, Int>> = emptyList(),
)

/**
 * The study home (spec §17): every list you keep, what's waiting in each, and the
 * always-there decks that aren't lists at all (everything pooled, characters you've merely
 * bumped into, HSK bands).
 *
 * The **active list** chip at the top is the app's one piece of shared study state: it is
 * where swipe-right files things, and it is what the home-screen widget follows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ListsScreen(
    repository: HanziRepository,
    hskLevels: Map<Int, List<String>>,
    onOpenList: (Long) -> Unit,
    onOpenItem: (StudyItem) -> Unit,
    onPractice: (DeckSource) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenConfusables: () -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ListsState()) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        state = withContext(Dispatchers.IO) {
            val missed = app.studyRepository.mostMissed()
            val summaries = repository.itemSummaries(missed.map { it.item })
                .associateBy { it.item.itemId }
            ListsState(
                lists = app.studyRepository.lists(),
                stats = app.studyRepository.statsByList(),
                activeId = app.studyRepository.activeListId(),
                encounteredCount = app.userRepository.practiceQueue().size,
                dueNow = app.studyRepository.dueNow(),
                today = app.studyRepository.reviewStatsToday(),
                week = app.studyRepository.reviewStatsOverDays(ACCURACY_WINDOW_DAYS),
                allTime = app.studyRepository.reviewStats(),
                archived = app.userRepository.knownCount(),
                mostMissed = missed.mapNotNull { lapse ->
                    summaries[lapse.item.itemId]?.let { it to lapse.lapses }
                },
            )
        }
    }
    val (lists, stats, activeId, encounteredCount) = state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Study") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = onOpenArchive) { Text("Archive") }
                    HomeButton(onHome)
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // What's waiting, in one line — the score card below answers the other half
            // ("how did the ones I did go"), so this doesn't restate today's count.
            Text(
                if (state.dueNow > 0) "${state.dueNow} due now" else "Nothing due right now",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ScoreCard(state, onOpenItem)

            // Saving destination, stated where you can change it — swipes elsewhere in
            // the app land here, so it must never be a mystery.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Swipes and the widget use:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (list in lists) {
                        FilterChip(
                            selected = activeId == list.id,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.studyRepository.setActiveListId(list.id)
                                    }
                                    refresh++
                                    PracticeWidgetUpdater.refresh(context)
                                }
                            },
                            label = { Text(list.name) },
                        )
                    }
                    FilterChip(
                        selected = activeId == null,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { app.studyRepository.setActiveListId(null) }
                                refresh++
                                PracticeWidgetUpdater.refresh(context)
                            }
                        },
                        label = { Text("Everything") },
                    )
                }
            }

            SectionTitle("Your lists")
            if (lists.isEmpty()) {
                Text(
                    "No lists yet. Swipe right on anything — a character in your photos, a " +
                        "search result — and it lands in a list you can practice.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (list in lists) {
                ListCard(
                    list = list,
                    stats = stats[list.id],
                    onOpen = { onOpenList(list.id) },
                    onPractice = { onPractice(DeckSource.UserList(list.id, list.name)) },
                )
            }

            if (creating) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("List name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            val name = newName.trim()
                            scope.launch {
                                withContext(Dispatchers.IO) { app.studyRepository.createList(name) }
                                newName = ""
                                creating = false
                                refresh++
                            }
                        },
                    ) { Text("Create") }
                }
            } else {
                TextButton(onClick = { creating = true }) { Text("＋  New list") }
            }

            SectionTitle("Other decks")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeckChip("Everything") { onPractice(DeckSource.Everything) }
                DeckChip(
                    if (encounteredCount > 0) "Recently encountered ($encounteredCount)"
                    else "Recently encountered",
                ) { onPractice(DeckSource.Encountered) }
                for (level in hskLevels.keys.sorted()) {
                    DeckChip(if (level == 7) "HSK 7-9" else "HSK $level") {
                        onPractice(DeckSource.Hsk(level))
                    }
                }
                DeckChip("Look-alikes", onOpenConfusables)
            }
        }
    }
}

/** How far back the headline accuracy figure looks. A week is long enough to survive one bad
 * evening and short enough to still be about how you're doing *now*. */
private const val ACCURACY_WINDOW_DAYS = 7

/**
 * Knew against missed — the one number the study loop was keeping to itself.
 *
 * Everything else on this screen counts what's *waiting*: due cards, new cards, list sizes.
 * None of it says whether the reviews you did went well, which is the question you open a
 * study screen asking. Three horizons, because one alone misleads in a different direction
 * each: today can be three answers, the week is the real signal, and all-time is the one that
 * shows the archive growing.
 *
 * "Most missed" is the actionable end of the same data: the characters whose lapse count keeps
 * climbing, each a tap away from its page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoreCard(state: ListsState, onOpenItem: (StudyItem) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Knew / missed", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(scoreLine("Today", state.today), fontSize = 13.sp)
            Text(scoreLine("Last $ACCURACY_WINDOW_DAYS days", state.week), fontSize = 13.sp, color = muted)
            Text(
                scoreLine("All time", state.allTime) + "  ·  ${state.archived} archived",
                fontSize = 13.sp,
                color = muted,
            )
            if (state.mostMissed.isNotEmpty()) {
                Text("Most missed", fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((summary, lapses) in state.mostMissed) {
                        Card(Modifier.clickable { onOpenItem(summary.item) }) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(summary.text, fontSize = 20.sp)
                                Text("×$lapses", fontSize = 12.sp, color = muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Today: 9 knew · 3 missed (75%)", or a plain zero state — an accuracy of 0% out of no
 * answers is a claim about nothing. */
private fun scoreLine(label: String, stats: ReviewStats): String {
    if (stats.total == 0) return "$label: nothing answered"
    val pct = stats.accuracy?.let { " (${"%.0f".format(it * 100)}%)" }.orEmpty()
    return "$label: ${stats.knew} knew · ${stats.missed} missed$pct"
}

@Composable
private fun ListCard(
    list: StudyList,
    stats: ListStats?,
    onOpen: () -> Unit,
    onPractice: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(list.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    summarise(stats),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(horizontal = 4.dp))
            TextButton(onClick = onPractice) { Text("▶ Practice") }
        }
    }
}

/** Leads with what's actionable — cards waiting now — and only then the totals. */
private fun summarise(stats: ListStats?): String {
    if (stats == null || stats.total == 0) return "Empty"
    val parts = mutableListOf<String>()
    if (stats.due > 0) parts += "${stats.due} due"
    if (stats.newItems > 0) parts += "${stats.newItems} new"
    if (parts.isEmpty()) parts += "nothing due"
    parts += "${stats.learning} studying"
    if (stats.archived > 0) parts += "${stats.archived} archived"
    return parts.joinToString(" · ")
}

@Composable
private fun DeckChip(label: String, onClick: () -> Unit) {
    Card(Modifier.clickable(onClick = onClick)) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp)
    }
}
