package dev.otmgram.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.core.media.AudioState
import dev.otmgram.ui.format.formatDuration
import dev.otmgram.ui.media.rememberOnDemandFile
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType

@Composable
internal fun VoiceNote(
    media: MessageMedia.VoiceNote,
    audio: AudioState?,
    outgoing: Boolean,
    onPlay: (Int, String) -> Unit,
    onLongClick: () -> Unit,
) {
    // Downloads on the first tap, then plays as soon as it's in.
    val voice = rememberOnDemandFile(media.fileId)
    val path = voice.path
    var playWhenReady by remember { mutableStateOf(false) }
    LaunchedEffect(path, playWhenReady) {
        if (playWhenReady && path != null) {
            playWhenReady = false
            onPlay(media.fileId, path)
        }
    }
    val current = audio?.takeIf { it.fileId == media.fileId }
    val progress = if (current != null && current.durationMs > 0) current.positionMs.toFloat() / current.durationMs else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (outgoing) Otm.ApricotLight else Otm.Haze)
                .combinedClickable(onLongClick = onLongClick) {
                    when {
                        path != null -> onPlay(media.fileId, path)
                        else -> {
                            playWhenReady = !voice.downloading
                            voice.toggle()
                        }
                    }
                }
                .testTag("playVoice"),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (outgoing) Otm.Umber else Otm.SlateDeep
            when {
                voice.downloading -> {
                    CircularProgressIndicator(
                        progress = { voice.progress },
                        modifier = Modifier.matchParentSize().padding(2.dp),
                        color = tint,
                        trackColor = tint.copy(alpha = 0.18f),
                        strokeWidth = 2.5.dp,
                    )
                    Icon(OtmIcons.Close, stringResource(R.string.download_stop), tint = tint, modifier = Modifier.size(14.dp))
                }
                current?.isPlaying == true -> Icon(OtmIcons.Pause, stringResource(R.string.pause), tint = tint, modifier = Modifier.size(16.dp))
                else -> Icon(OtmIcons.Play, stringResource(R.string.play_voice), tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        Column {
            Waveform(
                media.waveform,
                progress,
                played = if (outgoing) Otm.Umber else Otm.Slate,
                rest = if (outgoing) Otm.ApricotDeep else Otm.RidgeLight,
                modifier = Modifier.width(140.dp).height(24.dp),
            )
            Text(
                formatDuration(if (current != null) (current.positionMs / 1000).toInt() else media.duration),
                style = OtmType.Time,
                color = Otm.Meta,
            )
        }
    }
}

@Composable
fun Waveform(levels: List<Int>, progress: Float, played: Color, rest: Color, modifier: Modifier = Modifier, bars: Int = WAVEFORM_BARS) {
    val samples = remember(levels, bars) { resample(levels.ifEmpty { listOf(0) }, bars) }
    Canvas(modifier) {
        val step = size.width / samples.size
        val barWidth = step * 0.6f
        samples.forEachIndexed { i, level ->
            val height = size.height * (0.15f + 0.85f * level / 31f)
            drawRoundRect(
                color = if ((i + 0.5f) / samples.size <= progress) played else rest,
                topLeft = Offset(i * step, (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private fun resample(levels: List<Int>, count: Int): List<Int> =
    List(count) { i -> levels[i * levels.size / count] }

private const val WAVEFORM_BARS = 32
