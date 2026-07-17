package com.hanzidictionary.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

@Composable
fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

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
