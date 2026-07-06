package com.hanzidictionary.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.hanzidictionary.core.CharacterPage
import com.hanzidictionary.core.HanziRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterScreen(
    repository: HanziRepository,
    glyphId: String,
    onOpenComponent: (String) -> Unit,
    onOpenCharacter: (String) -> Unit,
    onBack: () -> Unit,
) {
    val page by produceState<CharacterPage?>(initialValue = null, glyphId) {
        value = withContext(Dispatchers.IO) { repository.characterPage(glyphId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.char ?: "…") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        val p = page
        if (p == null) {
            Text("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Header(p)
            BreakdownStrip(p, onOpenComponent)
            SoundLine(p)
            Formation(p)
            if (p.variants.isNotEmpty()) ScriptMapping(p, onOpenCharacter)
            Provenance(p)
        }
    }
}

@Composable
private fun Header(p: CharacterPage) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(p.char, fontSize = 72.sp)
        val pinyin = p.readings.filter { it.system == "pinyin" }.joinToString("  ") { it.value }
        if (pinyin.isNotEmpty()) Text(pinyin, fontSize = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            p.freqRank?.let { Chip("freq #$it") }
            p.hsk?.let { Chip("HSK $it") }
            Chip(scriptLabel(p.scripts))
            p.strokeCount?.let { Chip("$it strokes") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakdownStrip(p: CharacterPage, onOpenComponent: (String) -> Unit) {
    if (p.breakdown.isEmpty()) return
    SectionTitle("Components")
    p.ids?.let { Text(it, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in p.breakdown) {
            val color = functionColor(c.function)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(if (c.navigable) Modifier.clickable { onOpenComponent(c.componentId) } else Modifier)
                    .padding(vertical = 4.dp),
            ) {
                Text(c.displayChar ?: "◎", fontSize = 40.sp, color = color)
                Text(functionLabel(c.function), fontSize = 12.sp, color = color)
            }
        }
    }
}

@Composable
private fun SoundLine(p: CharacterPage) {
    val phon = p.breakdown.firstOrNull { it.function == "phonetic" || it.function == "both" } ?: return
    val sentence = driftSentence(phon.matchClass) ?: return
    SectionTitle("Sound")
    Text(sentence)
    phon.confidence?.let {
        Text(
            "reliability ${"%.0f".format(it * 100)}%",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Formation(p: CharacterPage) {
    val relevant = p.claims.filter { it.predicate == "component_function" || it.predicate == "etymology_text" }
    if (relevant.isEmpty()) return
    SectionTitle("Formation")
    for (claim in relevant) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(prettyClaim(claim.predicate, claim.value))
                Text(
                    "source: ${claim.source}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScriptMapping(p: CharacterPage, onOpenCharacter: (String) -> Unit) {
    SectionTitle("Simplified / Traditional")
    for (v in p.variants) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(v.fromChar, fontSize = 28.sp, modifier = Modifier.clickable { onOpenCharacter(v.fromGlyph) })
                Text("→")
                Text(v.toChar, fontSize = 28.sp, modifier = Modifier.clickable { onOpenCharacter(v.toGlyph) })
                v.mappingType?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Provenance(p: CharacterPage) {
    SectionTitle("Sources")
    val claimSources = p.claims.map { it.source }.toSortedSet()
    val text = "Readings/variants from Unihan; decomposition from cjkvi-ids/makemeahanzi" +
        if (claimSources.isNotEmpty()) "; claims from ${claimSources.joinToString(", ")}" else ""
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
}

@Composable
private fun Chip(text: String) {
    Card {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
    }
}

private fun scriptLabel(scripts: String): String = when (scripts) {
    "simp" -> "simplified"
    "trad" -> "traditional"
    else -> "both scripts"
}

private fun prettyClaim(predicate: String, value: String): String = try {
    when (predicate) {
        "component_function" -> {
            val o = JSONObject(value)
            val type = o.optString("formation_type", "").ifEmpty { "unknown formation" }
            val hint = o.optString("hint", "")
            val sem = o.optString("semantic_component", "")
            val phon = o.optString("phonetic_component", "")
            buildString {
                append(type)
                if (sem.isNotEmpty()) append("  ·  meaning: $sem")
                if (phon.isNotEmpty()) append("  ·  sound: $phon")
                if (hint.isNotEmpty()) append("\n$hint")
            }
        }
        "etymology_text" -> {
            // value is a JSON-encoded string
            if (value.startsWith("\"")) JSONObject("{\"t\":$value}").getString("t") else value
        }
        else -> value
    }
} catch (e: Exception) {
    value
}
