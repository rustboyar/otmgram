package dev.otmgram.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Things to tap and type in: buttons, fields, switches, chips, counts. Those drawn smaller than
 * 48 dp keep 48 dp of room (minimumInteractiveComponentSize): Compose widens their touch area to
 * that anyway, and the room keeps neighbours' areas from overlapping.
 */

/** A 44 dp icon button; [background] makes it a filled circle, [shadow] lifts it. */
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
    shadow: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .shadow(shadow, CircleShape)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
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
            .clickable(enabled = enabled && !busy, onClick = onClick, role = Role.Button),
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
            .minimumInteractiveComponentSize()
            .heightIn(min = height)
            .clip(RoundedCornerShape(height / 2))
            .background(background)
            .clickable(onClick = onClick, role = Role.Button)
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
            .minimumInteractiveComponentSize()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
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

/** "@": unread messages mention you or reply to you; shown strong even in muted chats. */
@Composable
fun MentionPill(description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(22.dp).clip(CircleShape).background(Otm.Haze).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("@", style = OtmType.Time.copy(fontWeight = FontWeight.SemiBold), color = Otm.SlateDeep)
    }
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
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Otm.Haze) else Modifier.border(1.dp, Otm.Outline, shape))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
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

/** The rounder, lower field and bar of bars and sheets: the input bar, find in chat, a caption. */
val PillShape = RoundedCornerShape(22.dp)
val PillHeight = 44.dp

/**
 * A field in [PillShape]: the input bar, find in chat, a caption, finding a chat. [bordered]
 * gives it a hairline, where it sits on a bar of its own colour.
 */
@Composable
fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    textStyle: TextStyle = OtmType.Body,
    maxLines: Int = 1,
    background: Color = Otm.Card,
    bordered: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    OtmField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(if (bordered) Modifier.border(1.dp, Otm.Line, PillShape) else Modifier),
        placeholder = placeholder,
        textStyle = textStyle,
        singleLine = maxLines == 1,
        maxLines = maxLines,
        shape = PillShape,
        height = PillHeight,
        background = background,
        outlined = false,
        leading = leading,
        trailing = trailing,
    )
}
