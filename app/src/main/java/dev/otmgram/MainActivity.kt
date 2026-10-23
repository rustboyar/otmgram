package dev.otmgram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dagger.hilt.android.AndroidEntryPoint
import dev.otmgram.data.FileRepository
import dev.otmgram.ui.AppRoot
import dev.otmgram.ui.media.LocalFileRepository
import dev.otmgram.ui.theme.OtmgramTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var files: FileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always light: dark icons over the paper and the sky.
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            CompositionLocalProvider(LocalFileRepository provides files) {
                OtmgramTheme {
                    // Exposes test tags as view ids to UI Automator, for driving the app via adb.
                    Box(Modifier.semantics { testTagsAsResourceId = true }) {
                        AppRoot()
                    }
                }
            }
        }
    }
}
