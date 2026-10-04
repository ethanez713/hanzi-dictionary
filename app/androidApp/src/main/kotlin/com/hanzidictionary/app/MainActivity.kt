package com.hanzidictionary.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.hanzidictionary.app.nudge.NudgeScheduler
import com.hanzidictionary.app.ui.AppRoot
import com.hanzidictionary.app.ui.AppTheme
import com.hanzidictionary.app.ui.isDark
import com.hanzidictionary.app.widget.PracticeWidgetProvider
import com.hanzidictionary.core.StudyItem
import com.hanzidictionary.core.isStaleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Text arriving via the SEND share target; consumed (nulled) once routed.
    private val sharedText = mutableStateOf<String?>(null)

    // The card the home-screen widget was showing when its middle was tapped.
    private val widgetItem = mutableStateOf<StudyItem?>(null)

    // Bumped when the app is reopened after a long enough gap that the browse trail it left
    // behind is stale; AppRoot watches this and starts the user back at home.
    private val staleSessionKey = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let the window run under the system bars, then hand the *bottom* one to the app so
        // the navigation bar's background reaches the screen edge instead of stopping above a
        // black strip holding the gesture pill. The status bar deliberately keeps the theme's
        // opaque colour: drawing the app under it too is what makes the top of every screen
        // read as one tall band of chrome.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        sharedText.value = extractSharedText(intent)
        widgetItem.value = extractWidgetItem(intent)
        val app = application as HanziApp
        setContent {
            // Reading the mode (Compose state) here makes a Settings change flip the theme
            // live. Keep the system bars' icon contrast in step with the resolved choice.
            val dark = app.settings.themeMode.isDark()
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            AppTheme(darkTheme = dark) {
                AppRoot(
                    repository = app.repository,
                    userRepository = app.userRepository,
                    hskLevels = app.hskLevels,
                    tts = app.tts,
                    sharedText = sharedText.value,
                    onSharedTextConsumed = { sharedText.value = null },
                    widgetItem = widgetItem.value,
                    onWidgetItemConsumed = { widgetItem.value = null },
                    staleSessionKey = staleSessionKey.intValue,
                )
            }
        }
    }

    // singleTask: a share (or a widget tap) while the app is already open lands here,
    // not in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedText(intent)?.let { sharedText.value = it }
        extractWidgetItem(intent)?.let { widgetItem.value = it }
    }

    /**
     * Decide, once per return to the foreground, whether the app is resuming the same errand
     * or starting a new one.
     *
     * An intent that is *already* pending routes itself onto a clean stack (widget and share
     * both rebuild it), so there is nothing here to decide and forcing a reset would only race
     * them. Which of [onNewIntent] and [onStart] runs first isn't contractual across versions,
     * so AppRoot's effect ordering covers the other direction.
     */
    override fun onStart() {
        super.onStart()
        val app = application as HanziApp
        app.inForeground = true
        NudgeScheduler.hideForOpenApp(this)
        val arriving = sharedText.value != null || widgetItem.value != null
        if (!arriving && isStaleSession(app.lastActiveAt, System.currentTimeMillis())) {
            staleSessionKey.intValue++
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as HanziApp
        app.lastActiveAt = System.currentTimeMillis()
        app.inForeground = false
        // A rotation stops and restarts the activity; that isn't leaving the app.
        if (!isChangingConfigurations) {
            CoroutineScope(Dispatchers.IO).launch { NudgeScheduler.reshowOnLeave(app) }
        }
    }

    private fun extractSharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }

    private fun extractWidgetItem(intent: Intent?): StudyItem? {
        val itemId = intent?.getStringExtra(PracticeWidgetProvider.EXTRA_PRACTICE_ITEM) ?: return null
        val kind = intent.getStringExtra(PracticeWidgetProvider.EXTRA_PRACTICE_KIND)
            ?: StudyItem.KIND_CHARACTER
        return StudyItem(itemId, kind)
    }
}
