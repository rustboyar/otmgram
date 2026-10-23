package dev.otmgram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import javax.inject.Inject

/** Temporary M0 screen: shows TDLib's authorization state to prove the native library works. */
@Composable
fun StatusScreen(viewModel: StatusViewModel = hiltViewModel()) {
    val state by viewModel.authorizationState.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Otmgram", style = MaterialTheme.typography.headlineMedium)
            Text(state?.javaClass?.simpleName ?: "Starting TDLib…")
        }
    }
}

@HiltViewModel
class StatusViewModel @Inject constructor(td: TdClient) : ViewModel() {
    val authorizationState = td.authorizationState
}
