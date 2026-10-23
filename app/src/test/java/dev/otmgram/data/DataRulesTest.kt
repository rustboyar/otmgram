package dev.otmgram.data

import dev.otmgram.R
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Small rules of the data layer: login steps, the name limit, storage kinds. */
class DataRulesTest {

    @Test
    fun `a code sent to the Telegram app, with another way to come`() {
        val info = TdApi.AuthenticationCodeInfo("+49151", TdApi.AuthenticationCodeTypeTelegramMessage(5), TdApi.AuthenticationCodeTypeSms(5), 30)
        assertEquals(
            AuthState.WaitCode("+49151", CodeDelivery.TelegramApp, length = 5, canResend = true, resendAfter = 30),
            TdApi.AuthorizationStateWaitCode(info).toAuthState(),
        )
    }

    @Test
    fun `steps Otmgram can't do send you to the official app`() {
        assertEquals(AuthState.Unsupported(R.string.unsupported_registration), TdApi.AuthorizationStateWaitRegistration().toAuthState())
        assertEquals(AuthState.WaitPassword("first mountain"), TdApi.AuthorizationStateWaitPassword().apply { passwordHint = "first mountain" }.toAuthState())
        assertEquals(AuthState.Loading, null.toAuthState())
    }

    @Test
    fun `names are cut without breaking emoji`() {
        assertEquals("morning post", "morning post".limitTo(20))
        assertEquals("abcde", "abcdefgh".limitTo(5))
        // The emoji is two chars but one character for the limit.
        assertEquals("a😀", "a😀b".limitTo(2))
    }

    @Test
    fun `storage kinds`() {
        assertEquals(StorageKind.Videos, kindOf(TdApi.FileTypeVideoNote()))
        assertEquals(StorageKind.Photos, kindOf(TdApi.FileTypeProfilePhoto()))
        assertEquals(StorageKind.Voice, kindOf(TdApi.FileTypeAudio()))
        assertEquals(StorageKind.Files, kindOf(TdApi.FileTypeDocument()))
        assertEquals(StorageKind.Other, kindOf(TdApi.FileTypeSticker()))
    }
}
