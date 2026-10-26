package dev.otmgram.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import dev.otmgram.R

/** One line for media without a caption, e.g. in the menu. */
@Composable
@ReadOnlyComposable
fun MessageMedia.label(): String = when (this) {
    is MessageMedia.Photo -> stringResource(R.string.summary_photo)
    is MessageMedia.Video -> stringResource(R.string.summary_video)
    is MessageMedia.Animation -> stringResource(R.string.summary_gif)
    is MessageMedia.VoiceNote -> stringResource(R.string.summary_voice)
    is MessageMedia.VideoNote -> stringResource(R.string.summary_video_note)
    is MessageMedia.Sticker -> stringResource(R.string.summary_sticker)
    is MessageMedia.Document -> name
    is MessageMedia.Location -> stringResource(if (live != null) R.string.summary_live_location else R.string.summary_location)
}
