package dev.otmgram.ui.theme

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.drinkless.tdlib.TdApi
import dev.otmgram.ui.media.TdImage

/** Dialogs and sheets are separate windows; each exposes its test tags for UI Automator. */
val DialogTags = Modifier.semantics { testTagsAsResourceId = true }

/** Warm paper with a faint 3 dp grain: the background of every light screen. */
fun Modifier.paper(): Modifier = drawWithCache {
    val tile = 3.dp.toPx().toInt().coerceAtLeast(2)
    val grain = ImageBitmap(tile, tile).also {
        Canvas(it).drawCircle(Offset(tile / 2f, tile / 2f), 0.5.dp.toPx(), Paint().apply { color = Otm.Ink.copy(alpha = 0.035f) })
    }
    val brush = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind {
        drawRect(Otm.Paper)
        drawRect(brush)
    }
}

// The sunrise: SVG paths of the design, in a 390-wide viewport.
private val SKY_COLORS = arrayOf(0f to Color(0xFFE6E9EA), 0.5f to Color(0xFFF3DDC9), 0.78f to Color(0xFFF2C6A9), 1f to Color(0xFFEDC3B7))
private val BAND_RIDGES = listOf(
    "M0 70L28 65L52 68L84 60L112 64L142 57L170 63L202 59L236 65L262 61L300 66L332 60L362 65L390 62V96H0Z" to Color(0xFFBCC8CF),
    "M0 79C40 71 72 75 104 70C134 65 166 76 204 73C244 70 274 78 312 72C342 68 366 74 390 71V96H0Z" to Color(0xFF91A8B6),
    "M0 88C50 84 92 90 142 85C192 80 232 90 282 87C322 85 362 81 390 85V96H0Z" to Color(0xFF62808F),
)
private val HERO_RIDGES = listOf(
    "M0 196L30 186L58 192L92 176L122 184L156 170L186 182L220 174L254 186L284 178L320 188L352 176L390 184V300H0Z" to Color(0xFFC3CED4),
    "M0 222C40 206 76 214 112 204C146 194 178 214 220 208C262 202 292 218 332 206C356 200 374 208 390 204V300H0Z" to Color(0xFF9BB0BD),
    "M0 252C46 240 90 252 140 240C190 228 236 250 286 244C330 238 364 230 390 238V300H0Z" to Color(0xFF7893A1),
)

private fun ridgePaths(ridges: List<Pair<String, Color>>) = ridges.map { (d, color) -> PathParser().parsePathString(d).toPath() to color }

private fun DrawScope.drawSunrise(paths: List<Pair<Path, Color>>, viewHeight: Float, sunX: Float, sunY: Float, sunR: Float, mist: Boolean) {
    drawRect(Brush.verticalGradient(*SKY_COLORS))
    val sx = size.width / 390f
    val sy = size.height / viewHeight
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFFFF4E4), 0.5f to Color(0xCCFBDDBE), 1f to Color(0x00FBDDBE),
            center = Offset(sunX * sx, sunY * sy), radius = sunR * sx,
        ),
        radius = sunR * sx, center = Offset(sunX * sx, sunY * sy),
    )
    val scale = Matrix().apply { scale(sx, sy) }
    paths.forEach { (path, color) ->
        val scaled = Path().apply { addPath(path) }.apply { transform(scale) }
        drawPath(scaled, color)
    }
    if (mist) {
        val top = size.height * 0.75f
        drawRect(
            Brush.verticalGradient(0f to Otm.Paper.copy(alpha = 0f), 1f to Otm.Paper, startY = top, endY = size.height),
            topLeft = Offset(0f, top),
        )
    }
}

/**
 * The chat list header: a sunrise with misty ridges behind the status bar, the folder
 * name in Fraunces and [actions] on the right. [content] replaces the name, e.g. with a
 * search field.
 */
@Composable
fun SkyBand(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val paths = remember { ridgePaths(BAND_RIDGES) }
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) { drawSunrise(paths, 96f, 292f, 66f, 30f, mist = false) }
        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(start = 20.dp, end = 8.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (content != null) {
                    content()
                } else {
                    // Names are short (see MAX_HEADER_NAME); longer ones shrink to fit on one line.
                    BasicText(
                        title,
                        style = OtmType.FolderName.copy(color = Otm.Ink),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = OtmType.FolderName.fontSize, stepSize = 0.5.sp),
                        modifier = Modifier.weight(1f).testTag("headerTitle"),
                    )
                }
                actions()
            }
        }
    }
}

/** A tall sunrise for sign-in screens; the last ridge fades into the paper like mist. */
@Composable
fun Hero(height: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val paths = remember { ridgePaths(HERO_RIDGES) }
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize()) { drawSunrise(paths, 300f, 262f, 196f, 72f, mist = true) }
        Box(Modifier.matchParentSize().statusBarsPadding(), content = content)
    }
}

/** The wordmark: "otmgram" over "One folder. Nothing else." */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("otmgram", style = OtmType.FolderName.copy(fontSize = 38.sp), color = Otm.Ink)
        Text("One folder. Nothing else.", style = OtmType.Body, color = Color(0xFF4A5560))
    }
}

/** A top bar on paper: back, optional avatar, title and subtitle, actions; a hairline below. */
@Composable
fun ScreenBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backIcon: ImageVector = OtmIcons.Back,
    backTag: String = "back",
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onBack != null) IconBtn(backIcon, "Back", onBack, tag = backTag)
            leading?.invoke()
            Column(Modifier.weight(1f).padding(start = if (leading != null) 6.dp else 4.dp)) {
                Text(title, style = OtmType.BarTitle, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = OtmType.Secondary, color = Otm.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            actions()
        }
        Hairline()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Otm.Line))
}

/** A 44 dp icon button; [background] makes it a filled circle. */
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Otm.Ink,
    background: Color = Color.Transparent,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = description)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) tint else Otm.Outline, modifier = Modifier.size(iconSize))
    }
}

/** The main action: a slate pill. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (enabled) Otm.Slate else Otm.RidgeLight)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(text, style = OtmType.Button, color = Color.White)
        }
    }
}

/** A secondary action: haze background, slate text. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    background: Color = Otm.Haze,
    color: Color = Otm.SlateDeep,
    height: Dp = 44.dp,
) {
    Row(
        modifier = modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(height / 2))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(20.dp)) }
        Text(text, style = OtmType.Button.copy(fontSize = 15.sp), color = color)
    }
}

/** A text action, e.g. in dialogs. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Otm.SlateDeep, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = OtmType.Button.copy(fontSize = 15.sp), color = if (enabled) color else Otm.InkFaint)
    }
}

/** Unread count: a soft pill; [quiet] for muted chats, just an outline. */
@Composable
fun CountPill(count: Int, quiet: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .height(22.dp)
            .widthIn(min = 24.dp)
            .clip(shape)
            .then(if (quiet) Modifier.border(1.dp, Otm.Outline, shape) else Modifier.background(Otm.Haze))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 999) "999+" else count.toString(),
            style = OtmType.Time.copy(fontWeight = if (quiet) FontWeight.Medium else FontWeight.SemiBold),
            color = if (quiet) Color(0xFF6E767E) else Otm.SlateDeep,
        )
    }
}

/** A white card on paper. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Otm.Card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A text field on a white rounded surface; slate outline while focused. */
@Composable
fun OtmField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = OtmType.Body.copy(fontSize = 17.sp),
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 5,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shape: Shape = RoundedCornerShape(14.dp),
    height: Dp = 52.dp,
    background: Color = Otm.Card,
    outlined: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.copy(color = Otm.Ink),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(Otm.Slate),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = height)
                    .clip(shape)
                    .background(background)
                    .then(
                        when {
                            !outlined -> Modifier
                            focused -> Modifier.border(2.dp, Otm.Slate, shape)
                            else -> Modifier.border(1.dp, Otm.Ink.copy(alpha = 0.14f), shape)
                        },
                    )
                    .padding(start = if (leading != null) 12.dp else 16.dp, end = if (trailing != null) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                leading?.invoke()
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = Otm.InkFaint, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
fun OtmSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Otm.Slate,
            checkedThumbColor = Color.White,
            checkedBorderColor = Otm.Slate,
            uncheckedTrackColor = Otm.Haze,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Otm.Outline,
        ),
    )
}

/** A selectable chip, e.g. "15 min"; the chosen one is filled. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Otm.Haze) else Modifier.border(1.dp, Otm.Outline, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = OtmType.Preview.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) Otm.SlateDeep else Otm.InkBody,
        )
    }
}

/** A calm line under a bar: offline, live location being shared. */
@Composable
fun Banner(icon: ImageVector, text: String, modifier: Modifier = Modifier, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().background(Otm.Banner).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = Otm.Slate, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = OtmType.Preview.copy(fontWeight = if (subtitle != null) FontWeight.SemiBold else FontWeight.Normal), color = Color(0xFF4A5560))
            subtitle?.let { Text(it, style = OtmType.Secondary, color = Otm.InkSoft) }
        }
        action?.invoke()
    }
}

/** Uppercase section label, e.g. "RECENT". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = OtmType.Label, color = Otm.InkFaint, modifier = modifier)
}

/** A dialog on the sheet colour with the design's type; [confirm] is the action on the right. */
@Composable
fun OtmDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    text: String? = null,
    dismissLabel: String = "Cancel",
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = DialogTags,
        containerColor = Otm.Sheet,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink) },
        text = if (text == null && content == null) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    text?.let { Text(it, style = OtmType.Body, color = Otm.InkSoft) }
                    content?.invoke(this)
                }
            }
        },
        confirmButton = confirm,
        dismissButton = { TextAction(dismissLabel, onDismiss, Modifier.testTag("dismiss")) },
    )
}

/**
 * For full-screen dialogs that draw their own backdrop: no extra dimming from the window,
 * and dark status bar icons like the screens behind.
 */
@Composable
fun PlainDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        window?.setDimAmount(0f)
        window?.let { WindowCompat.getInsetsController(it, view) }?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        onDispose {}
    }
}

/**
 * Light status bar icons while a dark screen (photo viewer, circle camera) is shown; dark
 * ones again when it leaves.
 */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = true
        }
    }
}

/**
 * A chat's picture: its photo, or for chats without one, a pattern derived from the id
 * (stripes, dots, arcs, zigzag) with the initial in the middle. Saved Messages gets a
 * bookmark.
 */
@Composable
fun ChatAvatar(
    chatId: Long,
    title: String,
    photoFileId: Int?,
    minithumbnail: TdApi.Minithumbnail?,
    size: Dp = 52.dp,
    savedMessages: Boolean = false,
) {
    when {
        savedMessages -> Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFDCE4E9)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.58f).clip(CircleShape).background(Otm.Sheet), contentAlignment = Alignment.Center) {
                Icon(BOOKMARK, null, tint = Otm.Slate, modifier = Modifier.size(size * 0.28f))
            }
        }
        photoFileId != null -> TdImage(photoFileId, minithumbnail, Modifier.size(size).clip(CircleShape))
        else -> PatternAvatar(chatId, title, size)
    }
}

@Composable
private fun PatternAvatar(chatId: Long, title: String, size: Dp) {
    val pattern = Math.floorMod(chatId, PATTERNS)
    val (light, dark) = AVATAR_TONES[Math.floorMod(chatId / PATTERNS, AVATAR_TONES.size.toLong()).toInt()]
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(light)
            val unit = this.size.width / 52f
            when (pattern) {
                0L -> { // Diagonal stripes.
                    val step = 10 * unit
                    var x = -this.size.height
                    while (x < this.size.width) {
                        drawLine(dark, Offset(x, this.size.height), Offset(x + this.size.height, 0f), strokeWidth = 5 * unit)
                        x += step
                    }
                }
                1L -> { // Dots.
                    val step = 9 * unit
                    var y = step / 2
                    while (y < this.size.height) {
                        var x = step / 2
                        while (x < this.size.width) {
                            drawCircle(dark, 2.4f * unit, Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                2L -> { // Arcs from a corner.
                    var r = 70 * unit
                    var i = 0
                    while (r > 0) {
                        drawCircle(if (i % 2 == 0) dark else light, r, Offset(0f, this.size.height))
                        r -= 5 * unit
                        i++
                    }
                }
                3L -> { // Zigzag.
                    val step = 12 * unit
                    var y = 0f
                    while (y < this.size.height + step) {
                        val path = Path()
                        var x = 0f
                        path.moveTo(0f, y)
                        while (x < this.size.width) {
                            path.lineTo(x + step / 2, y - step / 2)
                            path.lineTo(x + step, y)
                            x += step
                        }
                        drawPath(path, dark, style = Stroke(width = 3 * unit))
                        y += step / 1.5f
                    }
                }
                else -> { // Horizontal stripes.
                    var y = 0f
                    while (y < this.size.height) {
                        drawRect(dark, Offset(0f, y + 4 * unit), Size(this.size.width, 4 * unit))
                        y += 8 * unit
                    }
                }
            }
        }
        Box(Modifier.size(size * 0.58f).clip(CircleShape).background(Otm.Sheet), contentAlignment = Alignment.Center) {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "?",
                style = OtmType.Name.copy(fontSize = (size.value * 0.29f).sp),
                color = Otm.Ink,
            )
        }
    }
}

private const val PATTERNS = 5L

/** Light and dark tone pairs, from the sky and the ridges. */
private val AVATAR_TONES = listOf(
    Color(0xFFDFE5E9) to Color(0xFFA9BAC6),
    Color(0xFFF1E1D2) to Color(0xFFDDBFA6),
    Color(0xFFE7DCEB) to Color(0xFFB9A3C4),
    Color(0xFFDDE6D6) to Color(0xFFA9BC9E),
    Color(0xFFF3DDC9) to Color(0xFFE4B79A),
)

private val BOOKMARK = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        addPathNodes("M6 3h12v18l-6-4-6 4z"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.4f,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
