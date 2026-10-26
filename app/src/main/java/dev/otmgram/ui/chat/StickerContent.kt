package dev.otmgram.ui.chat

import dev.otmgram.core.log.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import dev.otmgram.data.StickerKind
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.media.rememberDownloadedPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

@Composable
fun StickerView(media: MessageMedia.Sticker, modifier: Modifier) {
    when (media.kind) {
        StickerKind.Image -> TdImage(media.fileId, null, modifier, contentScale = ContentScale.Fit)
        StickerKind.Lottie -> {
            val path = rememberDownloadedPath(media.fileId)
            if (path != null) LottieSticker(path, modifier) else Box(modifier)
        }
        // Android's decoders don't support transparent video: show the still thumbnail.
        StickerKind.Video -> TdImage(media.thumbnailFileId, null, modifier, contentScale = ContentScale.Fit)
    }
}

/** TGS stickers are gzipped Lottie animations; a broken or oversized one stays blank. */
@Composable
private fun LottieSticker(path: String, modifier: Modifier) {
    val json by produceState<String?>(null, path) {
        value = withContext(Dispatchers.IO) { readTgs(File(path)) }
    }
    val loaded = json ?: return Box(modifier)
    // Only create the composition once loaded: an empty placeholder one breaks the animation.
    val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(loaded))
    // Telegram stickers use merge paths, which Lottie disables by default.
    LottieAnimation(composition, iterations = LottieConstants.IterateForever, enableMergePaths = true, modifier = modifier)
}

/** The animation's JSON, or null if the file is broken or unpacks to more than [TGS_MAX_BYTES]. */
private fun readTgs(file: File): String? = try {
    GZIPInputStream(file.inputStream()).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() <= TGS_MAX_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() > TGS_MAX_BYTES) null else out.toString(Charsets.UTF_8.name())
    }
} catch (e: IOException) {
    AppLog.w("LottieSticker", "Can't read a sticker", e)
    null
}

/** Telegram allows 64 KB packed; real ones unpack to a few hundred KB. */
private const val TGS_MAX_BYTES = 2 * 1024 * 1024
