@file:OptIn(UnstableApi::class)

package dev.otmgram.core.media

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Prefers software decoders. Used where hardware decoders are a poor fit (many small
 * videos at once) or as a fallback when one fails, as the emulator's always does.
 */
val SoftwareFirstCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedBy { it.hardwareAccelerated }
}
