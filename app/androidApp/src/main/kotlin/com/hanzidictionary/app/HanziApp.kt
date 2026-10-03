package com.hanzidictionary.app

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.nudge.NudgeNotifier
import com.hanzidictionary.app.nudge.NudgeScheduler
import com.hanzidictionary.app.photo.MediaStorePhotoSource
import com.hanzidictionary.app.photo.MlKitTextRecognizer
import com.hanzidictionary.core.CardPin
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.PhotoScanner
import com.hanzidictionary.core.StudyRepository
import com.hanzidictionary.core.UserRepository
import org.json.JSONObject

class HanziApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The channel must exist before the first post, and re-arming the hourly job here
        // (KEEP, so a healthy one isn't reset) revives it after a force-stop wiped the queue.
        NudgeNotifier.ensureChannel(this)
        if (settings.nudgeEnabled) NudgeScheduler.sync(this, enabled = true)
    }

    val repository: HanziRepository by lazy { PackManager.openRepository(this) }

    /**
     * The flashcard each surface is currently showing, held for the life of the *process*.
     *
     * Both decks are rebuilt whenever their screen is recomposed — and opening a card's own
     * character page disposes that screen — so without this, looking a card up was enough to
     * lose it. These live on the Application rather than in the composables precisely because
     * navigation must not clear them; see [com.hanzidictionary.core.deckOpenedAt].
     *
     * Deliberately *not* persisted: a full app restart deals a fresh card, which is the one
     * moment where starting over is what you want.
     */
    var homeCardPin: CardPin? = null
    var practiceCardPin: CardPin? = null

    /** When the app last went to the background, so a return can tell "I'm still doing this"
     * from "I'm doing something else now" (see `isStaleSession`). Process-scoped like the
     * pins, and for the same reason: a cold start already has nothing to restore. */
    var lastActiveAt: Long? = null

    val tts: MandarinTts by lazy { MandarinTts(this) }

    /** User-facing preferences (theme, swipe-hint visibility). Observable Compose state. */
    val settings: AppSettings by lazy {
        AppSettings(getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE))
    }

    /** One driver for the whole user database; both repositories are query facades over
     * it, so they see each other's writes immediately (archiving in [userRepository] has
     * to drop the schedule [studyRepository] reads). AndroidSqliteDriver runs the .sqm
     * migrations on open, so an install predating study lists upgrades in place. */
    private val userDriver by lazy {
        AndroidSqliteDriver(UserRepository.Schema, this, "user.db")
    }

    /** Writable, app-owned user state (archive, history). SQLDelight creates user.db from
     * its schema on first open. */
    val userRepository: UserRepository by lazy { UserRepository(userDriver) }

    /** Study lists and FSRS scheduling (spec §17). */
    val studyRepository: StudyRepository by lazy { StudyRepository(userDriver) }

    /** One app-lifetime ML Kit recognizer: each getClient() call allocates a native
     * client that is never closed here, so re-creating one per scan would leak. Lazy so
     * ML Kit is only touched after the user opens the photos screen. */
    private val textRecognizer by lazy { MlKitTextRecognizer(this) }

    /** Photo-library scanner bound to MediaStore + ML Kit. Built on demand (only after
     * the user grants photo permission and opens the photos screen). */
    fun createPhotoScanner(): PhotoScanner =
        PhotoScanner(
            source = MediaStorePhotoSource(this),
            recognizer = textRecognizer,
            user = userRepository,
            // Look back up to 200 photos, so a big backlog since the last open is still
            // caught. The watermark keeps the actual OCR batch small on a normal open —
            // only photos newer than the last scan are recognized — so this ceiling only
            // matters after a long absence.
            maxPhotos = 200,
        )

    /** HSK level -> glyph ids, from the bundled hsk-levels.json asset, for the import
     * picker (spec §7). Loaded once, lazily. */
    val hskLevels: Map<Int, List<String>> by lazy {
        val json = assets.open("hsk-levels.json").bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        buildMap {
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                put(key.toInt(), List(arr.length()) { arr.getString(it) })
            }
        }
    }
}
