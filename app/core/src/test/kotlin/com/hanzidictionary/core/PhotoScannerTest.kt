package com.hanzidictionary.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoScannerTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var user: UserRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UserRepository.Schema.create(driver)
        user = UserRepository(driver)
    }

    @AfterTest
    fun teardown() = driver.close()

    private class FakePhotoSource(private val photos: List<PhotoRef>) : PhotoSource {
        override fun recentPhotos(limit: Int): List<PhotoRef> =
            photos.sortedByDescending { it.timestampMillis }.take(limit)
    }

    private class FakeRecognizer(private val texts: Map<String, String>) : ImageTextRecognizer {
        override fun recognize(photo: PhotoRef): String = texts[photo.id] ?: ""
    }

    @Test
    fun firstScanRecordsFindsAndAdvancesWatermark() {
        val source = FakePhotoSource(listOf(PhotoRef("p1", 1000), PhotoRef("p2", 2000)))
        val rec = FakeRecognizer(mapOf("p1" to "请坐", "p2" to "谢谢"))
        val result = PhotoScanner(source, rec, user).scanNewPhotos()

        assertEquals(2, result.photosScanned)
        assertTrue(result.finds.any { it.char == "请" })
        assertTrue(result.finds.any { it.char == "谢" })
        assertEquals(2000, user.lastScanTimestamp(), "watermark should advance to newest photo")
        assertTrue(user.recentPhotoFinds().any { it.glyphId == glyphIdOfChar("请") })
    }

    @Test
    fun noNewPhotosMeansNoRescan() {
        val source = FakePhotoSource(listOf(PhotoRef("p1", 1000)))
        val rec = FakeRecognizer(mapOf("p1" to "好"))
        val scanner = PhotoScanner(source, rec, user)

        scanner.scanNewPhotos()
        val second = scanner.scanNewPhotos()

        assertEquals(0, second.photosScanned, "already-scanned photos must not be re-scanned")
        assertTrue(second.finds.isEmpty())
    }

    @Test
    fun onlyPhotosNewerThanWatermarkAreScanned() {
        val rec = FakeRecognizer(mapOf("p1" to "一", "p2" to "二", "p3" to "三"))
        // first scan sees p1, p2
        PhotoScanner(FakePhotoSource(listOf(PhotoRef("p1", 1000), PhotoRef("p2", 2000))), rec, user)
            .scanNewPhotos()
        // a new photo p3 arrives later; p1/p2 are still in the roll but already scanned
        val second = PhotoScanner(
            FakePhotoSource(listOf(PhotoRef("p1", 1000), PhotoRef("p2", 2000), PhotoRef("p3", 3000))),
            rec, user,
        ).scanNewPhotos()

        assertEquals(1, second.photosScanned)
        assertEquals(listOf("三"), second.finds.map { it.char })
        assertEquals(3000, user.lastScanTimestamp())
    }

    @Test
    fun scanIsBoundedToMaxPhotos() {
        val photos = (1..20).map { PhotoRef("p$it", it * 1000L) }
        val texts = (1..20).associate { "p$it" to "字" }
        val result = PhotoScanner(FakePhotoSource(photos), FakeRecognizer(texts), user, maxPhotos = 5)
            .scanNewPhotos()
        assertEquals(5, result.photosScanned, "should only look at the most recent maxPhotos")
        assertEquals(20000, user.lastScanTimestamp(), "watermark = newest of the scanned window")
    }

    @Test
    fun extractHanziKeepsOnlyDistinctCjkInOrder() {
        assertEquals(listOf("你", "好"), extractHanzi("你好, hello! 你"))
        assertEquals(emptyList(), extractHanzi("hello 123 !?"))
        // supplementary-plane (Extension B) chars survive intact, Hangul is excluded
        assertEquals(listOf("𠀀", "好"), extractHanzi("𠀀한好"))
    }
}
