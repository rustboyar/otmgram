package dev.otmgram.ui.videonote

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.otmgram.ui.chat.formatDuration
import java.io.File
import java.util.concurrent.TimeUnit

/** Records a round video message with the front camera and sends it. */
@Composable
fun VideoNoteScreen(onDone: () -> Unit, viewModel: VideoNoteViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    var granted by remember {
        mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(permissions) }
    LaunchedEffect(viewModel.state) { if (viewModel.state == VideoNoteState.Sent) onDone() }

    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        when {
            !granted -> Text("Otmgram needs the camera and microphone to record video messages.", color = Color.White)
            viewModel.state == VideoNoteState.Processing -> CircularProgressIndicator()
            else -> Recorder(viewModel, onCancel = onDone)
        }
        viewModel.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
        }
    }
}

@SuppressLint("MissingPermission") // Only shown once camera and microphone are granted.
@Composable
private fun Recorder(viewModel: VideoNoteViewModel, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // A texture view, so the preview can be clipped to a circle.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .build()
        val capture = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture)
        videoCapture = capture
    }
    // Leaving the screen discards a recording in progress.
    DisposableEffect(Unit) { onDispose { recording?.close() } }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        AndroidView({ previewView }, Modifier.size(PREVIEW_SIZE).clip(CircleShape).testTag("cameraPreview"))
        Text(
            formatDuration(TimeUnit.MILLISECONDS.toSeconds(elapsedMs).toInt()) + " / " + formatDuration(MAX_SECONDS),
            color = Color.White,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(
                onClick = {
                    recording?.close()
                    onCancel()
                },
                modifier = Modifier.testTag("cancelVideoNote"),
            ) { Text("Cancel", color = Color.White) }
            val active = recording
            Button(
                onClick = {
                    if (active != null) {
                        active.stop()
                        return@Button
                    }
                    val capture = videoCapture ?: return@Button
                    val file = viewModel.newRecordingFile()
                    val output = FileOutputOptions.Builder(file).setDurationLimitMillis(MAX_SECONDS * 1000L).build()
                    recording = capture.output.prepareRecording(context, output)
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(context)) { event ->
                            when (event) {
                                is VideoRecordEvent.Status -> elapsedMs =
                                    TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)
                                is VideoRecordEvent.Finalize -> {
                                    recording = null
                                    onRecorded(viewModel, file, event)
                                }
                            }
                        }
                },
                enabled = videoCapture != null,
                modifier = Modifier.testTag(if (active == null) "startVideoNote" else "stopVideoNote"),
            ) {
                Text(if (active == null) "Record" else "Stop and send")
            }
        }
    }
}

private fun onRecorded(viewModel: VideoNoteViewModel, file: File, event: VideoRecordEvent.Finalize) {
    // Hitting the duration limit still produces a complete file.
    val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
    if (ok) viewModel.send(file) else viewModel.discard(file, event.cause?.message)
}

private val PREVIEW_SIZE = 300.dp
private const val MAX_SECONDS = 60
