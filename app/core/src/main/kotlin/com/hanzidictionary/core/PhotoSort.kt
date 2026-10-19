package com.hanzidictionary.core

/** How the Dictionary photo feed is ordered. Stored by [name]. */
enum class PhotoSort { RECENT, HSK, FREQUENCY }

/**
 * [PhotoSort.RECENT] keeps [finds] as given — the photo query is already newest first.
 * HSK and frequency put the lowest number first; null means unranked and sorts last,
 * because an unranked character has no place on that scale and must not jump ahead as
 * zero. A tie keeps the incoming order.
 */
fun sortFinds(finds: List<SearchResult>, sort: PhotoSort): List<SearchResult> = when (sort) {
    PhotoSort.RECENT -> finds
    PhotoSort.HSK -> finds.sortedWith(
        compareBy<SearchResult, Long?>(nullsLast()) { it.hsk }
            .thenBy(nullsLast()) { it.freqRank },
    )
    PhotoSort.FREQUENCY -> finds.sortedWith(
        compareBy<SearchResult, Long?>(nullsLast()) { it.freqRank },
    )
}
