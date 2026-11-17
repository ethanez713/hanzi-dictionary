package com.hanzidictionary.app

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hanzidictionary.core.HanziRepository
import com.hanzidictionary.core.db.HanziDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import java.io.File

/**
 * Installs the bundled data packs on first run (copying core.db + ids.db out of assets
 * into the app's databases dir, since SQLite needs a real file it can open, not an asset
 * stream) and opens a [HanziRepository] over them.
 *
 * The two packs are opened as separate drivers, never ATTACHed or merged, keeping the
 * BY-SA/LGPL layer (core.db) and the GPL layer (ids.db) physically separate on-device
 * exactly as in the published dump (spec §15).
 */
object PackManager {
    private const val CORE_DB = "core.db"
    private const val IDS_DB = "ids.db"

    // Bump when a shipped pack changes so an installed app re-copies it. This is the
    // in-APK bundled version; the GitHub-Releases delta-update path (spec §9) is later.
    const val BUNDLED_PACK_VERSION = 20
    private const val PREFS = "pack"
    private const val KEY_INSTALLED_VERSION = "installed_version"

    // ids.db carries only the decomposition table, so it has no matching SQLDelight
    // schema; a no-op schema at the same version never fires because the copied file
    // already exists at PRAGMA user_version = 1.
    private val noOpIdsSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver) = QueryResult.Value(Unit)
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: app.cash.sqldelight.db.AfterVersion,
        ) = QueryResult.Value(Unit)
    }

    fun openRepository(context: Context): HanziRepository {
        ensureInstalled(context)
        // Read both prebuilt packs through requery's bundled SQLite rather than the
        // framework engine: core.db's search relies on an FTS5 index the framework
        // SQLite can't open on many devices (see build.gradle dependency comment).
        val factory = RequerySQLiteOpenHelperFactory()
        val coreDriver = AndroidSqliteDriver(HanziDatabase.Schema, context, CORE_DB, factory)
        val idsDriver = AndroidSqliteDriver(noOpIdsSchema, context, IDS_DB, factory)
        return HanziRepository(coreDriver, idsDriver)
    }

    private fun ensureInstalled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = prefs.getInt(KEY_INSTALLED_VERSION, -1)
        // Both files, at their full size: a pref saying "installed" beside a truncated or
        // empty file (power lost just after a copy) opened as an empty dictionary for good.
        if (installed == BUNDLED_PACK_VERSION && isIntact(context, CORE_DB) && isIntact(context, IDS_DB)) return

        copyAsset(context, CORE_DB)
        copyAsset(context, IDS_DB)
        prefs.edit().putInt(KEY_INSTALLED_VERSION, BUNDLED_PACK_VERSION).commit()
    }

    private fun isIntact(context: Context, name: String): Boolean {
        val file = context.getDatabasePath(name)
        if (!file.exists()) return false
        // The assets are stored uncompressed, so their length is known without reading them.
        // If that ever stops being true, fall back to "present and non-empty".
        val expected = try {
            context.assets.openFd(name).use { it.length }
        } catch (e: Exception) {
            -1L
        }
        return if (expected >= 0) file.length() == expected else file.length() > 0
    }

    private fun copyAsset(context: Context, name: String) {
        val dest = context.getDatabasePath(name)
        dest.parentFile?.mkdirs()
        // Copy beside the target, flush it to disk, then rename over it: the installed pack
        // is only ever replaced whole, and a missing asset can't destroy the working copy
        // (the old code deleted it before finding out the asset wasn't there).
        val tmp = File(dest.path + ".tmp")
        context.assets.open(name).use { input ->
            java.io.FileOutputStream(tmp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        // A stale WAL/journal sidecar left by a previous install would be replayed
        // against the freshly copied file and read as corruption — drop them first.
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            File(dest.path + suffix).delete()
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            check(tmp.renameTo(dest)) { "could not install $name" }
        }
    }
}
