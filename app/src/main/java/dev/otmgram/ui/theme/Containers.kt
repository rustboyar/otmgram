package dev.otmgram.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* What holds content: the top bar, cards, banners, dialogs. */

/** Dialogs and sheets are separate windows; each exposes its test tags for UI Automator. */
val DialogTags = Modifier.semantics { testTagsAsResourceId = true }

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

/** A white card on paper. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Otm.Card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
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
