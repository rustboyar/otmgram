package dev.otmgram.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.AuthState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    val state: StateFlow<AuthState> =
        auth.state.stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    /** True while a request is in flight. */
    var busy by mutableStateOf(false)
        private set

    /** Error message of the last failed request, cleared by the next one. */
    var error by mutableStateOf<String?>(null)
        private set

    fun submitPhoneNumber(phoneNumber: String) = request { auth.setPhoneNumber(phoneNumber.trim()) }

    fun submitCode(code: String) = request { auth.checkCode(code.trim()) }

    fun resendCode() = request { auth.resendCode() }

    fun submitPassword(password: String) = request { auth.checkPassword(password) }

    fun register(firstName: String, lastName: String) =
        request { auth.register(firstName.trim(), lastName.trim()) }

    fun startOver() = request { auth.logOut() }

    private fun request(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: TdException) {
                error = e.error.message
            } finally {
                busy = false
            }
        }
    }
}
