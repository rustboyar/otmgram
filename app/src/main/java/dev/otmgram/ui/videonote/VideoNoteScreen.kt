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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.otmgram.R
import dev.otmgram.ui.chat.formatDuration
import dev.otmgram.ui.theme.DarkSystemBars
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import java.io.File
import java.util.concurrent.TimeUnit

/** Records a round video message and sends it; dark, the circle is the camera. */
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
    DarkSystemBars()

    Box(Modifier.fillMaxSize().background(Otm.Night).safeDrawingPadding()) {
        when {
            !granted -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.video_note_permission),
                    style = OtmType.Body,
                    color = Otm.NightText,
                    textAlign = TextAlign.Center,
                )
                IconBtn(OtmIcons.Close, stringResource(R.string.close), onDone, Modifier.padding(top = 16.dp), tint = Otm.NightText, tag = "cancelVideoNote")
            }
            viewModel.state == VideoNoteState.Processing -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Otm.NightText, strokeWidth = 2.5.dp)
                Text(stringResource(R.string.sending), style = OtmType.Body, color = Otm.NightSoft)
            }
            else -> Recorder(viewModel, onCancel = onDone)
        }
        viewModel.error?.let {
            Text(it, style = OtmType.Secondary, color = Otm.Rose, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 24.dp, end = 24.dp))
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
    var front by remember { mutableStateOf(true) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(front) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .build()
        val capture = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
        val camera = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, camera, preview, capture)
        videoCapture = capture
    }
    // Leaving the screen discards a recording in progress.
    DisposableEffect(Unit) { onDispose { recording?.close() } }

    val active = recording
    val record = stringResource(R.string.video_note_record)
    val stopAndSend = stringResource(R.string.video_note_stop_send)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(
                OtmIcons.Close, stringResource(R.string.cancel),
                {
                    recording?.close()
                    onCancel()
                },
                tint = Otm.NightText,
                tag = "cancelVideoNote",
            )
            Text(
                if (viewModel.chatTitle.isEmpty()) "" else stringResource(R.string.send_to, viewModel.chatTitle),
                style = OtmType.Preview,
                color = Otm.NightSoft,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconBtn(
                OtmIcons.SwitchCamera, stringResource(R.string.switch_camera),
                { front = !front },
                tint = Otm.NightText,
                enabled = active == null,
                tag = "switchCamera",
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
                AndroidView({ previewView }, Modifier.size(PREVIEW_SIZE).clip(CircleShape).testTag("cameraPreview"))
                val progress = (elapsedMs / (MAX_SECONDS * 1000f)).coerceIn(0f, 1f)
                Canvas(Modifier.matchParentSize()) {
                    val stroke = 5.dp.toPx()
                    val inset = stroke / 2
                    drawCircle(Otm.NightText.copy(alpha = 0.14f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                    drawArc(
                        Otm.Peach,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active != null) Box(Modifier.size(10.dp).clip(CircleShape).background(Otm.Rose))
                Text(formatDuration(TimeUnit.MILLISECONDS.toSeconds(elapsedMs).toInt()), style = OtmType.BarTitle, color = Otm.NightText)
                Text("/ ${formatDuration(MAX_SECONDS)}", style = OtmType.BarTitle, color = Otm.NightFaint)
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 48.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(6.dp, Otm.NightText.copy(alpha = 0.18f), CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Otm.NightText)
                    .clickable(enabled = videoCapture != null) {
                        if (active != null) {
                            active.stop()
                            return@clickable
                        }
                        val capture = videoCapture ?: return@clickable
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
                    }
                    .semantics { contentDescription = if (active == null) record else stopAndSend }
                    .testTag(if (active == null) "startVideoNote" else "stopVideoNote"),
                contentAlignment = Alignment.Center,
            ) {
                // A red dot to start, a red square to stop and send.
                if (active == null) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Otm.Rose))
                } else {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Otm.Rose))
                }
            }
        }
    }
}

private fun onRecorded(viewModel: VideoNoteViewModel, file: File, event: VideoRecordEvent.Finalize) {
    // Hitting the duration limit still produces a complete file.
    val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
    if (ok) viewModel.send(file) else viewModel.discard(file, event.cause?.message)
}

private val RING_SIZE = 300.dp
private val PREVIEW_SIZE = 280.dp
private const val MAX_SECONDS = 60
