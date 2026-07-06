plugins {
    id("org.jetbrains.kotlin.jvm")
    id("app.cash.sqldelight")
}

// Pure-Kotlin (no Android imports) so it can be reused from a KMP/iOS target later
// per spec §9. SQLDelight generates against the same schema the pipeline ships.
sqldelight {
    databases {
        create("HanziDatabase") {
            packageName.set("com.hanzidictionary.core.db")
            // We ship a prebuilt DB from the pipeline; SQLDelight only reads it, so
            // verifyMigrations stays off and the .sq schema must match the pipeline's.
            verifyMigrations.set(false)
            deriveSchemaFromMigrations.set(false)
        }
    }
}

dependencies {
    api("app.cash.sqldelight:runtime:2.0.2")
    // JVM SQLite driver for unit tests running the real pack; the Android app supplies
    // its own android-driver at runtime.
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
