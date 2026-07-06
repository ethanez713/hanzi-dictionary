package com.hanzidictionary.app

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.UserRepository
import org.json.JSONObject

class HanziApp : Application() {
    val repository: HanziRepository by lazy { PackManager.openRepository(this) }

    /** Writable, app-owned user state (known characters, history). SQLDelight creates
     * user.db from its schema on first open. */
    val userRepository: UserRepository by lazy {
        UserRepository(AndroidSqliteDriver(UserRepository.Schema, this, "user.db"))
    }

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
