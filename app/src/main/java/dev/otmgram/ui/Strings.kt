package dev.otmgram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.otmgram.core.AndroidStrings
import dev.otmgram.core.Strings

/** [Strings] for helpers that composables call; renewed when the configuration changes. */
@Composable
fun rememberStrings(): Strings {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { AndroidStrings(context) }
}
