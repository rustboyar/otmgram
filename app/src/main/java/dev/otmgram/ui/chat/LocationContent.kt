package dev.otmgram.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.ui.rememberStrings
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmType
import kotlinx.coroutines.delay

@Composable
internal fun LocationPreview(media: MessageMedia.Location, outgoing: Boolean, onOpen: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val map by produceState<ImageBitmap?>(null, media.latitude, media.longitude) {
        value = MapSnapshots.get(
            context, media.latitude, media.longitude, MAP_WIDTH_DP, MAP_HEIGHT_DP, density,
        )?.asImageBitmap()
    }
    val description = media.label()
    Column(Modifier.width(MAP_WIDTH_DP.dp).combinedClickable(onClick = onOpen, onLongClick = onLongClick).semantics { contentDescription = description }.testTag("location")) {
        Box(
            modifier = Modifier
                .width(MAP_WIDTH_DP.dp)
                .height(MAP_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Otm.Placeholder),
            contentAlignment = Alignment.Center,
        ) {
            map?.let { Image(it, contentDescription = null, modifier = Modifier.matchParentSize()) }
            MapPin()
        }
        val live = media.live
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (live != null) {
                Text(
                    liveStatus(live, rememberNow(), rememberStrings()),
                    style = OtmType.Time.copy(fontWeight = FontWeight.SemiBold),
                    color = Otm.SlateDeep,
                    modifier = Modifier.testTag("liveStatus"),
                )
            } else {
                Text(stringResource(R.string.summary_location), style = OtmType.Time, color = Otm.Meta)
            }
        }
    }
}

/**
 * Where your location message will be while the phone finds out where you are: a fix can
 * take a while, and an empty chat meanwhile looked like nothing happened.
 */
@Composable
fun LocatingBubble(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    val pulse by rememberInfiniteTransition(label = "locating").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pin",
    )
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .shadow(1.dp, shape, ambientColor = Otm.Ink.copy(alpha = 0.3f), spotColor = Otm.Ink.copy(alpha = 0.3f))
                .clip(shape)
                .background(Otm.Apricot)
                .padding(5.dp)
                .testTag("locating"),
        ) {
            Box(
                Modifier.width(MAP_WIDTH_DP.dp).height(MAP_HEIGHT_DP.dp).clip(RoundedCornerShape(13.dp)).background(Otm.Placeholder),
                contentAlignment = Alignment.Center,
            ) {
                MapPin(Modifier.graphicsLayer { alpha = pulse })
            }
            Text(
                stringResource(R.string.location_finding),
                style = OtmType.Time,
                color = Otm.Meta,
                modifier = Modifier.padding(start = 7.dp, end = 7.dp, top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

/** A slate dot with a soft halo, where the location is. */
@Composable
fun MapPin(modifier: Modifier = Modifier) {
    Canvas(modifier.size(44.dp)) {
        drawCircle(Otm.Slate.copy(alpha = 0.18f))
        drawCircle(Color.White, radius = 12.dp.toPx())
        drawCircle(Otm.Slate, radius = 9.dp.toPx())
    }
}

/** Current Unix time, refreshed every 30 s. */
@Composable
fun rememberNow(): Int {
    val now by produceState((System.currentTimeMillis() / 1000).toInt()) {
        while (true) {
            delay(30_000)
            value = (System.currentTimeMillis() / 1000).toInt()
        }
    }
    return now
}

fun liveStatus(live: MessageMedia.LiveInfo, now: Int, strings: Strings): String {
    if (now >= live.expiresAt) return strings.get(R.string.live_ended)
    val minutes = (now - live.updatedAt).coerceAtLeast(0) / 60
    return if (minutes == 0) strings.get(R.string.live_updated_now) else strings.get(R.string.live_updated_ago, minutes)
}

/** A location's map in a bubble, in dp. */
private const val MAP_WIDTH_DP = 260
private const val MAP_HEIGHT_DP = 140
