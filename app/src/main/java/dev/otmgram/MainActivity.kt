package dev.otmgram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dagger.hilt.android.AndroidEntryPoint
import dev.otmgram.ui.AppRoot
import dev.otmgram.ui.theme.OtmgramTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OtmgramTheme {
                // Exposes test tags as view ids to UI Automator, for driving the app via adb.
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    AppRoot()
                }
            }
        }
    }
}
