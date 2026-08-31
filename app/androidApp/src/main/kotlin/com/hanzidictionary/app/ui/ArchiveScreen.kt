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
import com.hanzidictionary.core.UserRepository
import com.hanzidictionary.core.charOfGlyphId
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
    onOpenCharacter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val eth = EthanTheme.colors
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var refresh by remember { mutableIntStateOf(0) }
    var knownChars by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var knownIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pasteText by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        val ids = withContext(Dispatchers.IO) { userRepository.knownIds() }
        knownIds = ids
        knownChars = ids.map { it to charOfGlyphId(it) }.sortedBy { it.second }
    }

    Scaffold(
        containerColor = eth.paper1,
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
                if (knownChars.size == 1) "1 character archived as known"
                else "${knownChars.size} characters archived as known",
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
                    ImportChip(if (level == 7) "HSK 7-9" else "HSK $level", eth) {
                        scope.launch {
                            withContext(Dispatchers.IO) { userRepository.importKnown(hskLevels[level].orEmpty()) }
                            refresh++
                        }
                    }
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
                    scope.launch {
                        withContext(Dispatchers.IO) { userRepository.importKnown(ids) }
                        pasteText = ""
                        refresh++
                    }
                }
            }

            if (knownChars.isNotEmpty()) {
                SheetEyebrow("ARCHIVED · ${knownChars.size}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((glyphId, ch) in knownChars) {
                        Box(
                            Modifier.clip(RoundedCornerShape(5.dp))
                                .background(eth.paper0)
                                .border(1.dp, eth.borderSubtle, RoundedCornerShape(5.dp))
                                .clickable { onOpenCharacter(glyphId) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(ch, fontSize = 24.sp, fontFamily = NotoCjk, color = eth.ink900)
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
