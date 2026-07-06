package com.hanzidictionary.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hanzidictionary.app.ui.AppRoot
import com.hanzidictionary.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as HanziApp).repository
        setContent {
            AppTheme {
                AppRoot(repository)
            }
        }
    }
}
