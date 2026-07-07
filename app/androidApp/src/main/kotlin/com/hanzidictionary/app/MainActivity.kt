package com.hanzidictionary.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hanzidictionary.app.ui.AppRoot
import com.hanzidictionary.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HanziApp
        setContent {
            AppTheme {
                AppRoot(app.repository, app.userRepository, app.hskLevels, app.tts)
            }
        }
    }
}
