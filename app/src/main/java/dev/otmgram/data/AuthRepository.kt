package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** The login step the user is at, simplified from TDLib's authorization states. */
sealed interface AuthState {
    data object Loading : AuthState
    data object WaitPhoneNumber : AuthState
    data class WaitCode(val phoneNumber: String, val delivery: CodeDelivery, val length: Int?) : AuthState
    data class WaitPassword(val hint: String) : AuthState

    /** New account: Telegram needs a name. */
    data object WaitRegistration : AuthState
    data object Ready : AuthState

    /** A step Otmgram doesn't handle (e.g. email verification); finish it in the official app. */
    data class Unsupported(val step: String) : AuthState
}

enum class CodeDelivery { TelegramApp, Sms, Call, Other }

@Singleton
class AuthRepository @Inject constructor(
    private val td: TdClient,
) {
    val state: Flow<AuthState> = td.authorizationState.map { it.toAuthState() }

    suspend fun setPhoneNumber(phoneNumber: String) {
        td.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null))
    }

    suspend fun checkCode(code: String) {
        td.send(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun resendCode() {
        td.send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    suspend fun checkPassword(password: String) {
        td.send(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun register(firstName: String, lastName: String) {
        td.send(TdApi.RegisterUser(firstName, lastName, false))
    }

    /** Logs out, or abandons a login in progress. TDLib deletes all local data. */
    suspend fun logOut() {
        td.send(TdApi.LogOut())
    }
}

private fun TdApi.AuthorizationState?.toAuthState(): AuthState = when (this) {
    is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhoneNumber
    is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode(
        phoneNumber = codeInfo.phoneNumber,
        delivery = when (codeInfo.type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> CodeDelivery.TelegramApp
            is TdApi.AuthenticationCodeTypeSms -> CodeDelivery.Sms
            is TdApi.AuthenticationCodeTypeCall -> CodeDelivery.Call
            else -> CodeDelivery.Other
        },
        length = when (val type = codeInfo.type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
            is TdApi.AuthenticationCodeTypeSms -> type.length
            is TdApi.AuthenticationCodeTypeCall -> type.length
            else -> null
        },
    )
    is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword(passwordHint)
    is TdApi.AuthorizationStateWaitRegistration -> AuthState.WaitRegistration
    is TdApi.AuthorizationStateReady -> AuthState.Ready
    is TdApi.AuthorizationStateWaitEmailAddress,
    is TdApi.AuthorizationStateWaitEmailCode -> AuthState.Unsupported("email verification")
    is TdApi.AuthorizationStateWaitPremiumPurchase -> AuthState.Unsupported("Telegram Premium purchase")
    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.Unsupported("confirmation on another device")
    // Starting up, or closing after logout until the next instance is ready.
    else -> AuthState.Loading
}
