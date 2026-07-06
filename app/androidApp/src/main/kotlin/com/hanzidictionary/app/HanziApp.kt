package com.hanzidictionary.app

import android.app.Application
import com.hanzidictionary.core.HanziRepository

/** Holds the process-wide repository, opened lazily off the bundled packs. */
class HanziApp : Application() {
    val repository: HanziRepository by lazy { PackManager.openRepository(this) }
}
