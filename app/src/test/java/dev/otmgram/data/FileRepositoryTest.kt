package dev.otmgram.data

import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.error
import dev.otmgram.testing.file
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileRepositoryTest {

    private val td = FakeTelegram().apply {
        on<TdApi.GetFile> { file(it.fileId) }
    }

    private fun progress(id: Int, downloaded: Long, done: Boolean = false) = TdApi.UpdateFile(
        file(id).apply {
            local.downloadedSize = downloaded
            local.isDownloadingActive = !done
            local.isDownloadingCompleted = done
            if (done) local.path = "/files/$id"
        },
    )

    @Test
    fun `each file hears only its own updates`() = runTest {
        val files = FileRepository(td, backgroundScope)
        val seen1 = mutableListOf<Long>()
        val seen2 = mutableListOf<Long>()
        backgroundScope.launch { files.observe(1).collect { seen1 += it.downloaded } }
        backgroundScope.launch { files.observe(2).collect { seen2 += it.downloaded } }
        runCurrent()
        td.emit(progress(1, 100))
        runCurrent()
        td.emit(progress(2, 200))
        runCurrent()
        assertEquals(listOf(0L, 100L), seen1)
        assertEquals(listOf(0L, 200L), seen2)
    }

    @Test
    fun `a finished download never goes back`() = runTest {
        val files = FileRepository(td, backgroundScope)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { files.observe(1).collect { seen += it.isDownloaded } }
        runCurrent()
        td.emit(progress(1, 1000, done = true))
        runCurrent()
        td.emit(progress(1, 500))
        runCurrent()
        assertTrue(seen.last())
    }

    @Test
    fun `a file TDLib can't get doesn't fail the flow`() = runTest {
        val files = FileRepository(td, backgroundScope)
        td.on<TdApi.DownloadFile> { error(400, "Can't download or generate the file") }
        val seen = mutableListOf<Boolean>()
        var failed: Throwable? = null
        backgroundScope.launch {
            try {
                files.download(4).collect { seen += it.isDownloaded }
            } catch (e: Exception) {
                failed = e
            }
        }
        runCurrent()
        td.emit(progress(4, 1000, done = true))
        runCurrent()
        assertEquals(null, failed)
        assertEquals(listOf(true), seen)
    }

    @Test
    fun `an update during the first request isn't lost`() = runTest {
        val files = FileRepository(td, backgroundScope)
        // The download finishes while TDLib is still answering the first request.
        td.on<TdApi.GetFile> { request ->
            backgroundScope.launch { td.emit(progress(request.fileId, 1000, done = true)) }
            file(request.fileId)
        }
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { files.observe(3).collect { seen += it.isDownloaded } }
        runCurrent()
        assertTrue(seen.last())
    }

    @Test
    fun `nobody listens once collection stops`() = runTest {
        val files = FileRepository(td, backgroundScope)
        val job = backgroundScope.launch { files.observe(4).collect {} }
        runCurrent()
        job.cancel()
        runCurrent()
        val listeners = FileRepository::class.java.getDeclaredField("listeners").apply { isAccessible = true }.get(files) as Map<*, *>
        assertTrue(listeners.isEmpty())
    }
}
