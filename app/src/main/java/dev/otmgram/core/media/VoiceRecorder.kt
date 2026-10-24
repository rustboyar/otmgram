package dev.otmgram.core.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.data.OutgoingFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A finished voice message, ready to send. */
class VoiceRecording(val file: File, val duration: Int, val waveform: ByteArray)

/** Recording in progress: elapsed time and the current input level (0..1). */
data class RecorderState(val elapsedMs: Long, val level: Float)

/** Records voice messages as OGG/Opus, sampling the input level for the waveform. */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outgoing: OutgoingFiles,
) {
    private val _state = MutableStateFlow<RecorderState?>(null)

    /** Null when not recording. */
    val state: StateFlow<RecorderState?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val amplitudes = mutableListOf<Int>()
    private var sampling: Job? = null

    /** Starts recording; needs the RECORD_AUDIO permission. */
    fun start() {
        if (recorder != null) return
        val output = outgoing.newFile("ogg")
        val started = newRecorder()
        try {
            started.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(output.path)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // E.g. the microphone is in use: free the recorder, or it holds on to it.
            started.release()
            output.delete()
            throw e
        }
        recorder = started
        file = output
        startedAt = SystemClock.elapsedRealtime()
        amplitudes.clear()
        sampling = scope.launch {
            while (isActive) {
                val amplitude = recorder?.maxAmplitude ?: 0
                amplitudes += amplitude
                _state.value = RecorderState(SystemClock.elapsedRealtime() - startedAt, amplitude / MAX_AMPLITUDE)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /** Stops recording; returns null if it was too short to send. */
    fun stop(): VoiceRecording? {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val output = file
        release()
        if (output == null || elapsed < MIN_DURATION_MS) {
            output?.delete()
            return null
        }
        return VoiceRecording(output, duration = ((elapsed + 500) / 1000).toInt(), waveform = waveform())
    }

    fun cancel() {
        file?.delete()
        release()
    }

    private fun release() {
        sampling?.cancel()
        // stop() throws if nothing was recorded yet; the file is discarded then anyway.
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        file = null
        _state.value = null
    }

    /**
     * Amplitudes scaled to the loudest sample and resampled to [WAVEFORM_SIZE] levels. The
     * floor keeps silence and steady background noise from being stretched to full height.
     */
    private fun waveform(): ByteArray {
        if (amplitudes.isEmpty()) return Waveform.encode(emptyList())
        val peak = amplitudes.max().coerceAtLeast(PEAK_FLOOR)
        val levels = List(WAVEFORM_SIZE) { i ->
            amplitudes[i * amplitudes.size / WAVEFORM_SIZE] * Waveform.MAX_LEVEL / peak
        }
        return Waveform.encode(levels)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 32_000
        const val SAMPLE_INTERVAL_MS = 50L
        const val MIN_DURATION_MS = 700L
        const val MAX_AMPLITUDE = 32_767f
        const val WAVEFORM_SIZE = 100
        const val PEAK_FLOOR = 4_000
    }
}
