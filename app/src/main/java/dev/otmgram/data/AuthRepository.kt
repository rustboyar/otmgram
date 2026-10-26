package dev.otmgram.data

import androidx.annotation.StringRes
import dev.otmgram.R
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.td.TelegramClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** The login step the user is at, simplified from TDLib's authorization states. */
sealed interface AuthState {
    data object Loading : AuthState
    data object WaitPhoneNumber : AuthState

    /**
     * [canResend]: another way to send the code exists; it may be asked for [resendAfter]
     * seconds after this state arrived.
     */
    data class WaitCode(
        val phoneNumber: String,
        val delivery: CodeDelivery,
        val length: Int?,
        val canResend: Boolean = false,
        val resendAfter: Int = 0,
    ) : AuthState
    data class WaitPassword(val hint: String) : AuthState
    data object Ready : AuthState

    /** A step Otmgram doesn't handle (e.g. new account, email verification); use the official app. */
    data class Unsupported(@StringRes val step: Int) : AuthState
}

enum class CodeDelivery { TelegramApp, Sms, Call, Other }

@Singleton
class AuthRepository @Inject constructor(
    private val td: TelegramClient,
    private val liveLocation: LiveLocationSharing,
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

    /** Logs out, or abandons a login in progress. TDLib deletes all local data. */
    suspend fun logOut() {
        // Once logged out, a live location can't be ended any more: other people would see
        // it until it expires.
        liveLocation.stop()
        liveLocation.endAll()
        td.send(TdApi.LogOut())
    }
}

internal fun TdApi.AuthorizationState?.toAuthState(): AuthState = when (this) {
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
        canResend = codeInfo.nextType != null,
        resendAfter = codeInfo.timeout,
    )
    is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword(passwordHint)
    is TdApi.AuthorizationStateWaitRegistration -> AuthState.Unsupported(R.string.unsupported_registration)
    is TdApi.AuthorizationStateReady -> AuthState.Ready
    is TdApi.AuthorizationStateWaitEmailAddress,
    is TdApi.AuthorizationStateWaitEmailCode,
    -> AuthState.Unsupported(R.string.unsupported_email)
    is TdApi.AuthorizationStateWaitPremiumPurchase -> AuthState.Unsupported(R.string.unsupported_premium)
    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.Unsupported(R.string.unsupported_other_device)
    // Starting up, or closing after logout until the next instance is ready.
    else -> AuthState.Loading
}
