package dev.otmgram.core.log

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {

    @Test
    fun `file paths and content URIs are left out`() {
        assertEquals(
            "java.io.FileNotFoundException: <path> (No such file or directory)",
            AppLog.redact("java.io.FileNotFoundException: /data/user/0/dev.otmgram/files/tdlib/documents/Tax return.pdf (No such file or directory)"),
        )
        assertEquals("Failed to build unique file: <path>", AppLog.redact("Failed to build unique file: /storage/emulated/0/Download/photo.jpg"))
        assertEquals("No content provider: <uri>", AppLog.redact("No content provider: content://media/external/file/42"))
    }

    @Test
    fun `stack frames and plain messages stay`() {
        val frame = "\tat dev.otmgram.core.td.TdClient\$send\$2\$1.onResult(TdClient.kt:118)"
        assertEquals(frame, AppLog.redact(frame))
        assertEquals("TDLib error 400: CHAT_NOT_FOUND", AppLog.redact("TDLib error 400: CHAT_NOT_FOUND"))
    }
}
