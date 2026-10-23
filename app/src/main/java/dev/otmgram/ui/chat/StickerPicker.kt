package dev.otmgram.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.otmgram.data.StickerTab
import dev.otmgram.ui.auth.LoadingScreen
import org.drinkless.tdlib.TdApi

/** Panel below the input: recent stickers and installed sets. */
@Composable
fun StickerPicker(
    tabs: List<StickerTab>,
    selected: StickerTab?,
    stickers: List<TdApi.Sticker>?,
    onSelectTab: (StickerTab) -> Unit,
    onSend: (TdApi.Sticker) -> Unit,
) {
    Column(Modifier.fillMaxWidth().height(PICKER_HEIGHT).background(MaterialTheme.colorScheme.surfaceContainer)) {
        LazyRow(Modifier.fillMaxWidth().padding(4.dp)) {
            items(tabs, key = { it.setId ?: 0 }) { tab ->
                Text(
                    tab.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tab == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(4.dp)
                        .background(
                            if (tab == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelectTab(tab) }
                        .testTag("stickerTab-${tab.title}")
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        when {
            stickers == null -> LoadingScreen()
            stickers.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No stickers here yet")
            }
            else -> LazyVerticalGrid(GridCells.Adaptive(STICKER_CELL), Modifier.fillMaxSize().testTag("stickerGrid")) {
                items(stickers, key = { it.sticker.id }) { sticker ->
                    StickerView(
                        sticker.toMedia(),
                        Modifier
                            .padding(4.dp)
                            .size(STICKER_CELL)
                            .clickable { onSend(sticker) }
                            .testTag("pick-${sticker.sticker.id}"),
                    )
                }
            }
        }
    }
}

private val PICKER_HEIGHT = 300.dp
private val STICKER_CELL = 72.dp
