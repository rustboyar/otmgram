package dev.otmgram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.data.AuthState
import dev.otmgram.ui.auth.AuthScreen
import dev.otmgram.ui.auth.AuthViewModel
import dev.otmgram.ui.home.HomeScreen

@Composable
fun AppRoot(authViewModel: AuthViewModel = hiltViewModel()) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    if (authState == AuthState.Ready) {
        HomeScreen()
    } else {
        AuthScreen(authState, authViewModel)
    }
}
