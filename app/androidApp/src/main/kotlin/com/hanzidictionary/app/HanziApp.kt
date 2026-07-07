package com.hanzidictionary.app

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hanzidictionary.app.audio.MandarinTts
import com.hanzidictionary.app.photo.MediaStorePhotoSource
import com.hanzidictionary.app.photo.MlKitTextRecognizer
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.PhotoScanner
import com.hanzidictionary.core.UserRepository
import org.json.JSONObject

class HanziApp : Application() {
    val repository: HanziRepository by lazy { PackManager.openRepository(this) }

    val tts: MandarinTts by lazy { MandarinTts(this) }

    /** Writable, app-owned user state (known characters, history). SQLDelight creates
     * user.db from its schema on first open. */
    val userRepository: UserRepository by lazy {
        UserRepository(AndroidSqliteDriver(UserRepository.Schema, this, "user.db"))
    }

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
