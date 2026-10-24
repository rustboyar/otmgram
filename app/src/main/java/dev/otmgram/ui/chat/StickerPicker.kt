package dev.otmgram.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.data.Sticker
import dev.otmgram.data.StickerTab
import dev.otmgram.ui.auth.LoadingScreen
import dev.otmgram.ui.theme.Hairline
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.SectionLabel

/** Panel in the keyboard's place: recent stickers and installed sets, a tab each. */
@Composable
fun StickerPicker(
    tabs: List<StickerTab>,
    selected: StickerTab?,
    stickers: List<Sticker>?,
    onSelectTab: (StickerTab) -> Unit,
    onSend: (Sticker) -> Unit,
) {
    val sendLabel = stringResource(R.string.send)
    Column(Modifier.fillMaxWidth().height(PICKER_HEIGHT).background(Otm.Sheet).navigationBarsPadding()) {
        Hairline()
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tabs, key = { it.setId ?: 0 }) { tab ->
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (tab == selected) Otm.Haze else Color.Transparent)
                        .selectable(selected = tab == selected, onClick = { onSelectTab(tab) }, role = Role.Tab)
                        .semantics { contentDescription = tab.title }
                        .testTag("stickerTab-${tab.title}"),
                    contentAlignment = Alignment.Center,
                ) {
                    val cover = tab.cover
                    when {
                        tab.setId == null -> Icon(OtmIcons.Clock, tab.title, tint = Otm.SlateDeep, modifier = Modifier.size(22.dp))
                        cover != null -> StickerView(cover.toMedia(), Modifier.size(30.dp))
                        else -> Text(tab.title.take(2), style = OtmType.Secondary, color = Otm.SlateDeep, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
        }
        Hairline()
        when {
            stickers == null -> LoadingScreen()
            stickers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.stickers_none), style = OtmType.Body, color = Otm.InkSoft)
            }
            else -> LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.fillMaxSize().testTag("stickerGrid"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel(selected?.title ?: "", Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp))
                }
                items(stickers, key = { it.fileId }) { sticker ->
                    Box(
                        Modifier.height(80.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = sendLabel, role = Role.Button) { onSend(sticker) }.semantics { contentDescription = sticker.emoji }.testTag("pick-${sticker.fileId}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        StickerView(sticker.toMedia(), Modifier.size(STICKER_CELL))
                    }
                }
            }
        }
    }
}

private val PICKER_HEIGHT = 330.dp
private val STICKER_CELL = 68.dp
