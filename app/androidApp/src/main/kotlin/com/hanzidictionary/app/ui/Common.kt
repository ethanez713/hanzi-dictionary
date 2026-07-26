package com.hanzidictionary.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanzidictionary.core.radicalNameOf

/** The runtime permission the photo-library features need on this OS version (shared by
 * the search screen's photo panel and the full Photos screen). */
val photoPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

/** Standard top-bar back affordance (replaces the old "←" text buttons; also gives
 * TalkBack a real label, spec §15 a11y). */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/**
 * Jump straight back to the dictionary home, from anywhere.
 *
 * Back walks the hierarchy one step at a time, and this app's browsing is genuinely deep —
 * a character leads to a component, which leads to another character, which leads to its
 * words — so "get me out of here" was costing four or five taps. Every screen but home
 * carries this, always as the **last action before an overflow menu**, so it lands in the
 * same corner everywhere and never moves when a screen has extra actions.
 *
 * It's a top-bar action rather than a bottom nav bar on purpose: bottom nav would cost a
 * permanent strip of vertical space on screens (practice above all) that are budgeted to
 * fit without scrolling, and only two of this app's destinations are top-level at all.
 */
@Composable
fun HomeButton(onHome: () -> Unit) {
    IconButton(onClick = onHome) {
        Icon(Icons.Filled.Home, contentDescription = "Home")
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

/** Corpus-frequency rank at/under which an item earns a "Common" badge on its page (rank
 * 1 = most frequent). Roughly the everyday-vocabulary band a beginner should prioritize. */
const val COMMON_FREQ_RANK = 1000L

fun isCommon(freqRank: Long?): Boolean = freqRank != null && freqRank <= COMMON_FREQ_RANK

/**
 * The dense "what is this character/word" row used by search results, the photo list and
 * list screens: the glyph, then its sound and meaning. One definition of the row keeps
 * those three surfaces identical, which matters because the same item shows up on all of
 * them.
 *
 * [trailing] is where a screen adds its own state (a ✓ for archived, a due badge).
 */
@Composable
fun ItemRow(
    text: String,
    pinyin: String?,
    gloss: String?,
    onClick: () -> Unit,
    secondaryText: String? = null,
    glossLines: Int = 2,
    dimmed: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val alpha = if (dimmed) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, fontSize = 27.sp, color = LocalContentColor.current.copy(alpha = alpha))
        secondaryText?.let {
            Text(
                it,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Column(Modifier.weight(1f)) {
            pinyin?.let {
                Text(it, fontSize = 15.sp, color = LocalContentColor.current.copy(alpha = alpha))
            }
            gloss?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = glossLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** First sense only — a component chip or card wants a hint ("words, speech"), not the
 * full semicolon-separated Unihan gloss. */
fun shortGloss(definition: String): String = definition.split(";").first().trim()

/** Component function color-coding used across breakdown strips and component pages
 * (spec §9): semantic red-ish, phonetic blue-ish, both purple, else muted. Tuned to
 * stay legible on both light and dark Material 3 surfaces. */
@Composable
fun functionColor(function: String): Color = when (function) {
    "semantic" -> Color(0xFFC62828)
    "phonetic" -> Color(0xFF1565C0)
    "both" -> Color(0xFF6A1B9A)
    "empty" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** True when this component is the one lending its character the sound.
 *
 * A component's reading is worth showing whatever its role — a part you can *say* is a part
 * you can remember — but only here is the reading the point, so this is what decides whether
 * it's set at full size or as a footnote under the gloss. */
fun isSoundBearing(function: String): Boolean = function == "phonetic" || function == "both"

/** True when this component is the one lending its character the meaning. */
fun isMeaningBearing(function: String): Boolean = function == "semantic" || function == "both"

/**
 * What goes on a component's sound line: either a reading or a radical's name, never both.
 *
 * [isReading] is what callers style on — only a real reading is ever set at full size or
 * given a speaker, because only a reading is a claim about how something is pronounced. A
 * radical's name is a label, so it always reads as a quiet aside.
 */
data class ComponentLabel(val text: String, val isReading: Boolean)

/** A combining form is *called* something (氵 = 三点水) but isn't pronounced on its own, so
 * its name displaces the reading Unihan lists for it; anything that stands as a character in
 * its own right keeps its reading. */
fun componentLabel(displayChar: String?, pinyin: String?): ComponentLabel? {
    displayChar?.let { radicalNameOf(it) }?.let { return ComponentLabel(it, isReading = false) }
    return pinyin?.let { ComponentLabel(it, isReading = true) }
}

/** Empty string means "no useful classification" (the pack stores "unknown" for
 * associative compounds like 好 = 女+子 where neither part is meaning- or sound-bearing).
 * Callers skip rendering a label in that case rather than showing a distracting "unknown". */
fun functionLabel(function: String): String = when (function) {
    "semantic" -> "meaning"
    "phonetic" -> "sound"
    "both" -> "sound + meaning"
    else -> ""
}

/** A pinyin syllable carrying no tone diacritic is the neutral tone — real Unihan data
 * (kHanyuPinlu lists 情 as "qíng(1700) qing(491)": neutral in 事情), not a parse failure.
 * Shown bare next to a toned reading it just looks broken, so callers label it. */
fun isNeutralTone(pinyin: String): Boolean =
    pinyin.isNotEmpty() && pinyin.none { it in "āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜêềếể" }

/** Human-readable drift sentence from a match_class (spec §9 "sound line"). The
 * match_class already encodes the stratum it held at — "oc_only" means the link
 * survives only in Old Chinese — so the stratum isn't needed separately here. */
fun driftSentence(matchClass: String?): String? {
    if (matchClass == null) return null
    return when (matchClass) {
        "exact" -> "Sound component: still an exact match in modern Mandarin."
        "tone" -> "Sound component: matches in modern Mandarin except for tone."
        "initial" -> "Sound component: shares the modern initial; the rest has drifted."
        "rime" -> "Sound component: shares the modern rime; the initial has drifted."
        "oc_only" -> "Sound component: the link survives only in Old Chinese — it has drifted apart since."
        "opaque" -> "Sound component by origin, but the pronunciations have drifted apart beyond recognition."
        else -> null
    }
}
