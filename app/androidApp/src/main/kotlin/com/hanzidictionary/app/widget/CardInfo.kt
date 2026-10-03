package com.hanzidictionary.app.widget

import com.hanzidictionary.app.HanziApp
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.WidgetCardInfo

/**
 * Resolves an item to the two lines a glanceable card draws: the front glyph/word, and a
 * "pinyin · gloss" answer. Shared by the home-screen widget and the study notification so the
 * two surfaces can't drift apart on what a card says.
 */
internal fun cardInfo(app: HanziApp, item: StudyItem): WidgetCardInfo? {
    val summary = app.repository.itemSummaries(listOf(item)).firstOrNull() ?: return null
    val answer = listOfNotNull(summary.pinyin, summary.gloss?.take(48))
        .joinToString(" · ")
        .ifBlank { summary.text }
    return WidgetCardInfo(item, summary.text, answer)
}
