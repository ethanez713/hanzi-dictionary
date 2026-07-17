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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.ComponentPage
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.SeriesMember
import com.hanzidictionary.core.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentScreen(
    repository: HanziRepository,
    userRepository: UserRepository,
    componentId: String,
    onOpenCharacter: (String) -> Unit,
    onPracticeSeries: () -> Unit,
    onBack: () -> Unit,
) {
    val page by produceState<ComponentPage?>(initialValue = null, componentId) {
        value = withContext(Dispatchers.IO) {
            repository.componentPage(componentId, userRepository.knownIds())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.char ?: "component") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val p = page
        if (p == null) {
            Text("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // When the component is itself a dictionary character, its glyph links
                // through to the full character page (definition, words, breakdown).
                val gid = p.glyphId
                Text(
                    p.char ?: p.componentId,
                    fontSize = 64.sp,
                    modifier = if (gid != null) Modifier.clickable { onOpenCharacter(gid) } else Modifier,
                )
                val pinyin = p.ownReadings.filter { it.system == "pinyin" }.joinToString("  ") { it.value }
                if (pinyin.isNotEmpty()) Text(pinyin, fontSize = 20.sp)
                p.definition?.let {
                    Text(
                        it,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (gid != null) {
                    TextButton(onClick = { onOpenCharacter(gid) }) { Text("Open character page →") }
                }
            }

            if (p.reliability != null) ReliabilityMeter(p)

            if (p.phoneticMembers.isNotEmpty() || p.semanticMembers.isNotEmpty()) {
                // Component reunion (spec §14/§16): drill the whole series at once —
                // knowing the component makes its siblings predictable.
                TextButton(onClick = onPracticeSeries) { Text("Practice this series →") }
            }

            if (p.phoneticMembers.isNotEmpty()) {
                SectionTitle("Phonetic series (${p.phoneticMembers.size})")
                MemberGrid(p.phoneticMembers, onOpenCharacter)
            }
            if (p.semanticMembers.isNotEmpty()) {
                SectionTitle("Used for meaning (${p.semanticMembers.size})")
                MemberGrid(p.semanticMembers, onOpenCharacter)
            }
            if (p.phoneticMembers.isEmpty() && p.semanticMembers.isEmpty()) {
                Text(
                    "No indexed characters use this component.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReliabilityMeter(p: ComponentPage) {
    SectionTitle("Phonetic reliability")
    LinearProgressIndicator(
        progress = { (p.reliability ?: 0.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
    )
    val stratum = when (p.bestStratum) {
        "pinyin" -> "still holds in modern Mandarin"
        "mc" -> "holds in Middle Chinese"
        "oc" -> "only holds in Old Chinese"
        else -> null
    }
    Text(
        buildString {
            append("${"%.0f".format((p.reliability ?: 0.0) * 100)}% across ${p.memberCount} characters")
            if (stratum != null) append(" · $stratum")
        },
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberGrid(members: List<SeriesMember>, onOpenCharacter: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in members) {
            Card(Modifier.clickable { onOpenCharacter(m.glyphId) }) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(m.char, fontSize = 28.sp)
                    m.matchClass?.let {
                        Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
