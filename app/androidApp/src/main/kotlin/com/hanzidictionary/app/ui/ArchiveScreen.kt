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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.ItemSummary
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.extractHanzi
import com.hanzidictionary.core.glyphIdOfChar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Archive (spec §16): characters marked known, which stay in lists as a record but drop
 * out of practice. Shows the count, HSK coverage, an importer for what you already know, and
 * the grid of archived characters. Reached from Library; Back or the arrow dismisses it.
 *
 * Previously this screen was orphaned by the 3-tab redesign — Library's archive row went
 * nowhere, so archived characters appeared to vanish. This restyles it into the design system
 * and wires it back in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArchiveScreen(
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    onOpenItem: (StudyItem, String) -> Unit,
    onBack: () -> Unit,
) {
    val eth = EthanTheme.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val snackbar = remember { SnackbarHostState() }
    BackHandler(onBack = onBack)

    var refresh by remember { mutableIntStateOf(0) }
    // Characters *and* words: triage's "I know it" and a word card's Archive both archive words.
    // Their ids are "w000123", so decoding every id as a codepoint crashed this screen.
    var archived by remember { mutableStateOf<List<ItemSummary>>(emptyList()) }
    var knownIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pasteText by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    LaunchedEffect(refresh) {
        val (ids, summaries) = withContext(Dispatchers.IO) {
            val items = userRepository.knownItems()
            items.map { it.itemId }.toSet() to app.repository.itemSummaries(items)
        }
        knownIds = ids
        archived = summaries.sortedWith(compareBy({ it.item.isWord }, { it.text }))
    }

    /** Archives [ids] in one go, with an Undo that puts back every schedule it discarded. */
    fun import(label: String, ids: List<String>) {
        scope.launch {
            val undos = withContext(Dispatchers.IO) {
                app.studyRepository.archiveAll(ids.map { StudyItem.character(it) })
            }
            refresh++
            PracticeWidgetUpdater.refresh(context)
            if (undos.isEmpty()) {
                snackbar.snack("Everything in $label was already archived")
                return@launch
            }
            val noun = if (undos.size == 1) "character" else "characters"
            if (snackbar.snack("Archived ${undos.size} $noun from $label", action = "Undo")) {
                withContext(Dispatchers.IO) { app.studyRepository.undoArchives(undos) }
                refresh++
                PracticeWidgetUpdater.refresh(context)
            }
        }
    }

    confirmImport?.let { (label, ids) ->
        val fresh = ids.count { it !in knownIds }
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Archive $label?") },
            text = {
                Text(
                    "$fresh ${if (fresh == 1) "character" else "characters"} will leave practice " +
                        "everywhere and lose their schedules. You can undo this straight after.",
                )
            },
            confirmButton = {
                Button(onClick = { confirmImport = null; import(label, ids) }) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) { Text("Cancel") }
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = eth.textFaint)
                    }
                },
                title = {
                    Text(
                        "Archive",
                        fontFamily = Piazzolla,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = eth.ink900,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (archived.size == 1) "1 item archived as known"
                else "${archived.size} items archived as known",
                fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = eth.textBody,
            )
            Text(
                "Archived characters stay in your lists as a record but drop out of every " +
                    "list's practice and float to the front of component series.",
                fontFamily = InstrumentSans, fontSize = 12.5.sp, lineHeight = 17.sp, color = eth.textFaint,
            )

            SheetEyebrow("HSK PROGRESS")
            for (level in hskLevels.keys.sorted()) {
                val levelIds = hskLevels[level].orEmpty()
                val knownInLevel = levelIds.count { it in knownIds }
                val frac = if (levelIds.isEmpty()) 0f else knownInLevel.toFloat() / levelIds.size
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        "${if (level == 7) "HSK 7-9" else "HSK $level"}   $knownInLevel / ${levelIds.size}",
                        fontFamily = PlexMono, fontSize = 11.sp, color = eth.textMuted,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(eth.ink100),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(frac).height(4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(eth.accent),
                        )
                    }
                }
            }

            SheetEyebrow("IMPORT WHAT YOU KNOW")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (level in hskLevels.keys.sorted()) {
                    val label = if (level == 7) "HSK 7-9" else "HSK $level"
                    // A whole level is hundreds of characters: confirm first, then offer Undo.
                    ImportChip(label, eth) { confirmImport = label to hskLevels[level].orEmpty() }
                }
            }
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("Paste Chinese text (e.g. a Pleco export)", fontFamily = InstrumentSans) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            if (pasteText.isNotBlank()) {
                ImportChip("Add pasted characters", eth) {
                    val ids = extractHanzi(pasteText).map { glyphIdOfChar(it) }
                    pasteText = ""
                    import("the pasted text", ids)
                }
            }

            if (archived.isNotEmpty()) {
                SheetEyebrow("ARCHIVED · ${archived.size}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in archived) {
                        Box(
                            Modifier.clip(RoundedCornerShape(5.dp))
                                .background(eth.paper0)
                                .border(1.dp, eth.borderSubtle, RoundedCornerShape(5.dp))
                                .clickable { onOpenItem(a.item, a.text) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(a.text, fontSize = if (a.item.isWord) 20.sp else 24.sp, fontFamily = NotoCjk, color = eth.ink900)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ImportChip(label: String, eth: EthanColors, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = InstrumentSans,
        fontSize = 12.sp,
        color = eth.onAccentSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(eth.surfaceAccentSoft)
            .border(1.dp, eth.orange200, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
