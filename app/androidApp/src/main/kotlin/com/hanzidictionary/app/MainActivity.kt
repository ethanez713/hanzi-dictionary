package com.hanzidictionary.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.hanzidictionary.app.ui.AppRoot
import com.hanzidictionary.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    // Text arriving via the SEND share target; consumed (nulled) once routed.
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = extractSharedText(intent)
        val app = application as HanziApp
        setContent {
            AppTheme {
                AppRoot(
                    repository = app.repository,
                    userRepository = app.userRepository,
                    hskLevels = app.hskLevels,
                    tts = app.tts,
                    sharedText = sharedText.value,
                    onSharedTextConsumed = { sharedText.value = null },
                )
            }
        }
    }

    // singleTask: a share while the app is already open lands here, not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedText(intent)?.let { sharedText.value = it }
    }

    private fun extractSharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
}
