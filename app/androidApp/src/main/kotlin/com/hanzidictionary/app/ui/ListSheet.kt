package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.app.widget.PracticeWidgetUpdater
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.StudyList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place an item's study state is edited, reachable from every screen that shows an
 * item: which lists hold it, and whether it's archived as known.
 *
 * Membership is a set of checkboxes because it genuinely is a set — an item can sit in
 * "HSK 4" and "Signs I saw" at once. Archiving is separated below a divider because it is
 * a different kind of statement: it applies everywhere at once (spec §17), so putting it
 * in the same visual group as per-list checkboxes would misrepresent what it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListSheet(
    item: StudyItem,
    label: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    var refresh by remember { mutableIntStateOf(0) }
    var lists by remember { mutableStateOf<List<StudyList>>(emptyList()) }
    var memberOf by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var known by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(
                app.studyRepository.lists(),
                app.studyRepository.listIdsContaining(item.itemId),
                app.userRepository.isKnown(item.itemId),
            )
        }
        lists = loaded.first
        memberOf = loaded.second
        known = loaded.third
    }

    fun mutate(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { block() }
            refresh++
            onChanged()
            // This sheet is the main way membership and archiving change, and the widget
            // follows the active list — without this poke it would keep offering a card
            // that was just archived, or stay empty after the first item is filed.
            PracticeWidgetUpdater.refresh(context)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = LocalGlyphType.current.tile)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text("Study lists", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.padding(vertical = 4.dp))

            if (lists.isEmpty() && !creating) {
                Text(
                    "No lists yet. Create one to start studying this.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                for (list in lists) {
                    val inList = list.id in memberOf
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                mutate {
                                    if (inList) app.studyRepository.removeFromList(list.id, item.itemId)
                                    else app.studyRepository.addToList(list.id, item)
                                }
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = inList, onCheckedChange = null)
                        Text(list.name, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            if (creating) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = { newListName = it },
                        label = { Text("List name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = newListName.isNotBlank(),
                        onClick = {
                            val name = newListName.trim()
                            mutate {
                                val id = app.studyRepository.createList(name)
                                app.studyRepository.addToList(id, item)
                            }
                            newListName = ""
                            creating = false
                        },
                    ) { Text("Create") }
                }
            } else {
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    Text("New list")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            // Archiving is global, and saying so on the control itself is the whole point:
            // the user asked for the known/list distinction to stop being confusing.
            FilterChip(
                selected = known,
                onClick = {
                    val next = !known
                    known = next
                    mutate { app.userRepository.setKnown(item.itemId, next, item.kind) }
                },
                label = { Text(if (known) "✓ Known — archived everywhere" else "I know this") },
            )
            Text(
                if (known) {
                    "Archived: it stays in your lists as a record, but won't come up in practice. " +
                        "Tap to start studying it again."
                } else {
                    "Marks it known in every list at once and takes it out of practice for good."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The primary study control on a definition screen: **Add to list** (spec §17 — this
 * replaced "I know this" as the default action, since filing something to study is the
 * common case and archiving is the rare one), plus a state chip once it's archived.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyActionRow(item: StudyItem, label: String, trailing: @Composable (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as HanziApp
    var sheetOpen by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var known by remember(item.itemId) { mutableStateOf(false) }
    var listCount by remember(item.itemId) { mutableStateOf(0) }

    LaunchedEffect(item.itemId, refresh) {
        val state = withContext(Dispatchers.IO) {
            app.userRepository.isKnown(item.itemId) to
                app.studyRepository.listIdsContaining(item.itemId).size
        }
        known = state.first
        listCount = state.second
    }

    // FlowRow, not Row: a screen can hang two drawers off [trailing], and with an archived
    // item's "✓ Known" beside them that overflows a phone's width — wrapping is better than
    // a clipped control.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(onClick = { sheetOpen = true }) {
            Text(
                when {
                    listCount == 1 -> "In 1 list"
                    listCount > 1 -> "In $listCount lists"
                    else -> "Add to list"
                },
            )
        }
        if (known) {
            Text(
                "✓ Known",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        trailing?.invoke()
    }

    if (sheetOpen) {
        ListSheet(
            item = item,
            label = label,
            onDismiss = { sheetOpen = false },
            onChanged = { refresh++ },
        )
    }
}
