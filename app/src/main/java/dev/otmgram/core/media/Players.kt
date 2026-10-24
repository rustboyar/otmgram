@file:OptIn(UnstableApi::class)

package dev.otmgram.core.media

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Prefers software decoders. Used where hardware decoders are a poor fit (many small
 * videos at once) or as a fallback when one fails, as the emulator's always does.
 */
val SoftwareFirstCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedBy { it.hardwareAccelerated }
}

/**
 * Plays through the media stream. With [focus], other sound (music, a voice message) pauses
 * while this plays, and this pauses for a call or when headphones are unplugged.
 */
fun ExoPlayer.mediaAudio(focus: Boolean, content: Int = C.AUDIO_CONTENT_TYPE_MOVIE) {
    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(content).build(), focus)
    setHandleAudioBecomingNoisy(focus)
}
