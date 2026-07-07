package com.hanzidictionary.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
        else -> null
    }
}
