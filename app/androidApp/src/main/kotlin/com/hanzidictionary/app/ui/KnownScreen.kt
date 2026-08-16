package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.charOfGlyphId
import com.hanzidictionary.core.extractHanzi
import com.hanzidictionary.core.glyphIdOfChar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KnownScreen(
    userRepository: UserRepository,
    hskLevels: Map<Int, List<String>>,
    onOpenCharacter: (String) -> Unit,
    onHome: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // bumped after each mutation to re-pull the known list/count
    var refresh by remember { mutableIntStateOf(0) }
    var knownChars by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var knownIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var count by remember { mutableStateOf(0L) }
    var pasteText by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(refresh) {
        val ids = withContext(Dispatchers.IO) { userRepository.knownIds() }
        knownIds = ids
        count = ids.size.toLong()
        knownChars = ids.map { it to charOfGlyphId(it) }.sortedBy { it.second }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archive") },
                navigationIcon = { BackButton(onBack) },
                actions = { HomeButton(onHome) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (count == 1L) "1 character archived as known"
                else "$count characters archived as known",
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Archived characters stay in your lists as a record, but drop out of every " +
                    "list's practice and float to the front of component series. Import " +
                    "what you already know:",
                fontSize = 13.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("HSK progress")
            for (level in hskLevels.keys.sorted()) {
                val levelIds = hskLevels[level].orEmpty()
                val knownInLevel = levelIds.count { it in knownIds }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${if (level == 7) "HSK 7-9" else "HSK $level"}: $knownInLevel / ${levelIds.size}",
                        fontSize = 13.sp,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (levelIds.isEmpty()) 0f else knownInLevel.toFloat() / levelIds.size
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionTitle("Import an HSK level")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (level in hskLevels.keys.sorted()) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { userRepository.importKnown(hskLevels[level].orEmpty()) }
                            refresh++
                        }
                    }) {
                        Text(if (level == 7) "HSK 7-9" else "HSK $level")
                    }
                }
            }

            SectionTitle("Paste characters")
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("Paste Chinese text (e.g. a Pleco export)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = pasteText.isNotBlank(),
                onClick = {
                    val ids = extractHanzi(pasteText).map { glyphIdOfChar(it) }
                    scope.launch {
                        withContext(Dispatchers.IO) { userRepository.importKnown(ids) }
                        pasteText = ""
                        refresh++
                    }
                },
            ) { Text("Add pasted characters") }

            if (knownChars.isNotEmpty()) {
                SectionTitle("Known (${knownChars.size})")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((glyphId, ch) in knownChars) {
                        Card(Modifier.clickable { onOpenCharacter(glyphId) }) {
                            Text(ch, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}
