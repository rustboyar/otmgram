package dev.otmgram.ui.profile

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.otmgram.R
import dev.otmgram.ui.chat.ForwardSheet
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.theme.Card
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.DarkSystemBars
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.ScreenBar
import dev.otmgram.ui.theme.SectionLabel
import dev.otmgram.ui.theme.SoftButton
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.launch

/**
 * A person's profile (picture, name, status, phone, username, bio, birthday) or a group's
 * (picture, title, members count, description, and its members). The picture pages through
 * all of theirs; details copy on a tap.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenProfile: (userId: Long) -> Unit,
    onOpenChat: (chatId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profile = viewModel.profile
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    viewModel.notice?.let { notice ->
        LaunchedEffect(notice) {
            snackbar.showSnackbar(notice)
            viewModel.noticeShown()
        }
    }
    Box(modifier.fillMaxSize().paper()) {
        Column(Modifier.fillMaxSize()) {
            ScreenBar(stringResource(R.string.profile_title), onBack)
            if (profile == null) return@Column
            LazyColumn(
                Modifier.fillMaxSize().testTag("profile"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "head") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChatAvatar(
                            profile.id,
                            profile.name,
                            profile.photoFileId,
                            profile.minithumbnail,
                            Modifier
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(56.dp))
                                .clickable(enabled = profile.photoFileId != null, role = Role.Image, onClickLabel = stringResource(R.string.profile_photos), onClick = viewModel::openPhotos)
                                .testTag("profilePhoto"),
                            size = PHOTO_SIZE,
                        )
                        Text(profile.name, style = OtmType.Headline, color = Otm.Ink, textAlign = TextAlign.Center, modifier = Modifier.testTag("profileName"))
                        profile.subtitle?.let { Text(it, style = OtmType.Secondary, color = Otm.InkSoft) }
                    }
                }
                if (profile.chatId != null) {
                    item(key = "actions") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                            if (profile.canOpenChat) {
                                SoftButton(stringResource(R.string.profile_open_chat), { onOpenChat(profile.chatId) }, Modifier.testTag("openChat"), icon = OtmIcons.Send)
                            }
                            if (profile.canShare) {
                                SoftButton(stringResource(R.string.profile_share), viewModel::chooseShareTarget, Modifier.testTag("shareContact"), icon = OtmIcons.Forward)
                            }
                            SoftButton(
                                stringResource(if (profile.isMuted) R.string.chat_unmute else R.string.chat_mute),
                                viewModel::toggleMuted,
                                Modifier.testTag(if (profile.isMuted) "unmuteProfile" else "muteProfile"),
                                icon = if (profile.isMuted) OtmIcons.Unmuted else OtmIcons.Muted,
                            )
                        }
                    }
                }
                if (profile.details.isNotEmpty()) {
                    item(key = "details") {
                        Card(padding = PaddingValues(vertical = 6.dp)) {
                            profile.details.forEach { detail ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(role = Role.Button) {
                                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, detail.value))) }
                                            viewModel.showNotice(copied)
                                        }
                                        .testTag("detail-${detail.label}")
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(stringResource(detail.label), style = OtmType.Secondary, color = Otm.InkSoft)
                                    Text(detail.value, style = OtmType.Body, color = Otm.Ink)
                                }
                            }
                        }
                    }
                }
                if (viewModel.members.isNotEmpty()) {
                    item(key = "membersLabel") { SectionLabel(stringResource(R.string.profile_members), Modifier.padding(start = 4.dp, top = 6.dp)) }
                    items(viewModel.members, key = { it.userId }) { member ->
                        MemberRow(member) { onOpenProfile(member.userId) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Snackbar(it, containerColor = Otm.Ink, contentColor = Otm.Paper, shape = RoundedCornerShape(14.dp))
        }
        viewModel.photos?.let { photos -> Photos(photos, viewModel::closePhotos) }
        viewModel.shareTargets?.let { targets ->
            ForwardSheet(targets, onForward = viewModel::share, onDismiss = viewModel::cancelShare, title = stringResource(R.string.profile_share_to))
        }
    }
}

@Composable
private fun MemberRow(member: Member, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("member-${member.userId}")
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatAvatar(member.userId, member.name, member.photoFileId, member.minithumbnail, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(member.name, style = OtmType.Name, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            member.status?.let { Text(it, style = OtmType.Secondary, color = Otm.InkSoft, maxLines = 1) }
        }
        member.role?.let { Text(it, style = OtmType.Secondary, color = Otm.Slate) }
    }
}

/** Their photos, full screen, to page through. */
@Composable
private fun Photos(photos: List<ProfilePhoto>, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    DarkSystemBars()
    val pager = rememberPagerState { photos.size }
    Box(Modifier.fillMaxSize().background(Otm.Night).safeDrawingPadding().testTag("profilePhotos")) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { index ->
            TdImage(photos[index].fileId, photos[index].minithumbnail, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(OtmIcons.Back, stringResource(R.string.back), onClose, tint = Otm.NightText, tag = "closePhotos")
            if (photos.size > 1) {
                Text(
                    stringResource(R.string.profile_photo_position, pager.currentPage + 1, photos.size),
                    style = OtmType.Secondary,
                    color = Otm.NightSoft,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private val PHOTO_SIZE = 112.dp
