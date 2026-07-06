package com.hanzidictionary.core

/**
 * Photo-library scanning workflow (the app's primary photo interaction, replacing live
 * camera OCR). Enumerate the most recent photos, OCR the ones newer than the last scan,
 * pull out Chinese characters, and record them — advancing a watermark so photos taken
 * before the last scan are never re-OCR'd.
 *
 * The two device-bound pieces — enumerating the camera roll and running OCR — are behind
 * [PhotoSource] and [ImageTextRecognizer] interfaces, so this orchestration (dedup,
 * watermark, hanzi extraction) is fully unit-testable with fakes and no device.
 */

data class PhotoRef(val id: String, val timestampMillis: Long)

interface PhotoSource {
    /** Most recent photos first, up to [limit]. */
    fun recentPhotos(limit: Int): List<PhotoRef>
}

interface ImageTextRecognizer {
    /** All text recognized in the photo, or "" if none/unreadable. Must not throw for a
     * merely-unreadable image; the scanner treats "" as "no characters found". */
    fun recognize(photo: PhotoRef): String
}

data class PhotoFind(val glyphId: String, val char: String, val photoId: String, val timestampMillis: Long)

data class ScanResult(val photosScanned: Int, val finds: List<PhotoFind>)

data class PhotoFindRow(val glyphId: String, val foundAt: Long)

class PhotoScanner(
    private val source: PhotoSource,
    private val recognizer: ImageTextRecognizer,
    private val user: UserRepository,
    private val maxPhotos: Int = 10,
) {
    /**
     * Scan photos newer than the stored watermark (bounded to the most recent [maxPhotos]),
     * oldest-new first so the watermark advances monotonically even if scanning is
     * interrupted. Idempotent: with no new photos it does nothing.
     */
    fun scanNewPhotos(): ScanResult {
        val watermark = user.lastScanTimestamp()
        val toScan = source.recentPhotos(maxPhotos)
            .filter { it.timestampMillis > watermark }
            .sortedBy { it.timestampMillis }

        val finds = mutableListOf<PhotoFind>()
        var newWatermark = watermark
        for (photo in toScan) {
            val text = recognizer.recognize(photo)
            for (ch in extractHanzi(text)) {
                val gid = glyphIdOfChar(ch)
                user.recordPhotoFind(gid, photo.id, photo.timestampMillis)
                finds += PhotoFind(gid, ch, photo.id, photo.timestampMillis)
            }
            if (photo.timestampMillis > newWatermark) newWatermark = photo.timestampMillis
        }
        if (newWatermark > watermark) user.setLastScanTimestamp(newWatermark)
        return ScanResult(photosScanned = toScan.size, finds = finds)
    }
}

/** Distinct CJK unified ideographs in [text], preserving first-seen order. Iterates by
 * code point so supplementary-plane (Extension B+) characters are handled correctly. */
fun extractHanzi(text: String): List<String> {
    val seen = LinkedHashSet<String>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (isCjkIdeograph(cp)) seen.add(String(Character.toChars(cp)))
    }
    return seen.toList()
}

private fun isCjkIdeograph(cp: Int): Boolean =
    cp in 0x4E00..0x9FFF ||    // CJK Unified Ideographs
        cp in 0x3400..0x4DBF ||   // Extension A
        cp in 0x20000..0x2A6DF || // Extension B
        cp in 0x2A700..0x2EBEF || // Extensions C-F
        cp in 0xF900..0xFAFF      // CJK Compatibility Ideographs
