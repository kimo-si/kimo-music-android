package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import com.kimo.music.data.Track
import com.kimo.music.data.formatDuration
import com.kimo.music.data.online
import com.kimo.music.core.*
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.SleepTimerRequest
import com.kimo.music.ui.theme.KimoColors

// 队列来自媒体会话，选择曲目时保留当前随机和循环设置。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(model: MusicViewModel, onDismiss: () -> Unit) {
    val content by remember(model) { derivedStateOf {
        QueueContent(model.playback.queue, model.playback.track?.id, model.favorites)
    } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = content.tracks.indexOfFirst { it.id == content.currentId }.coerceAtLeast(0))
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = KimoColors.Paper) {
        KimoTipWindow()
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("播放列表 · ${content.tracks.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (content.tracks.isEmpty()) Text("播放列表为空", color = KimoColors.Secondary)
            LazyColumn(Modifier.testTag("queue-song-list").heightIn(max = 430.dp), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(content.tracks, key = Track::id, contentType = { "queue-song" }) { track ->
                    TrackRow(track, { model.selectQueued(track); onDismiss() }, { model.toggleFavorite(track) },
                        active = track.id == content.currentId, action = if (track.id in content.favorites)
                            TrackRowAction.Unlike else TrackRowAction.Like)
                }
            }
        }
    }
}

private data class QueueContent(val tracks: List<Track>, val currentId: String?, val favorites: Set<String>)

data class PlayerMenuActions(val quality: () -> Unit, val playlist: () -> Unit,
    val lyrics: () -> Unit, val volume: () -> Unit, val download: () -> Unit, val timer: () -> Unit)

// 播放选项使用与曲库一致的暖色底部面板，保留所有真实播放操作。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerOptionsDialog(model: MusicViewModel, onDismiss: () -> Unit, actions: PlayerMenuActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = KimoColors.Paper,
        scrimColor = Color.Black.copy(alpha = 0.58f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        KimoTipWindow()
        ThemedSheetNavigationBar()
        PlayerSheetStatusBar()
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("player-options-sheet").verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            PlayerOptionsHeader(model.playback.track, onDismiss)
            PlayerOptionsList(model, actions)
        }
    }
}

// 播放页上方的深色区域使用浅色状态栏图标，底部导航栏沿用面板暖色。
@Composable
private fun PlayerSheetStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller?.isAppearanceLightStatusBars = previous }
    }
}

// 标题下展示当前真实歌曲，切歌后面板内容与播放器保持一致。
@Composable
private fun PlayerOptionsHeader(track: Track?, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("播放选项", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = KimoColors.Ink)
        IconButton(onDismiss, Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "关闭播放选项", tint = KimoColors.Secondary) }
    }
    if (track != null) CurrentMenuTrack(track)
    Spacer(Modifier.height(8.dp))
}

// 歌曲摘要只使用当前音频的封面、曲名和歌手。
@Composable
private fun CurrentMenuTrack(track: Track) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).clip(RoundedCornerShape(14.dp))
        .background(KimoColors.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(track, Modifier.size(46.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TrackTitle(track, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (track.displayArtist.isNotBlank()) Text(track.displayArtist, maxLines = 1, fontSize = 12.sp, color = KimoColors.Secondary)
        }
    }
}

// 当前值与具体操作放在同一行，不改变原有音质、定时和队列逻辑。
@Composable
private fun PlayerOptionsList(model: MusicViewModel, actions: PlayerMenuActions) {
    PlayerOption(Icons.Outlined.Download, "下载音频", onClick = actions.download)
    PlayerOption(Icons.AutoMirrored.Outlined.VolumeUp, "音量", "${(model.playback.volume * 100).toInt()}%", onClick = actions.volume)
    PlayerOption(Icons.Outlined.Equalizer, "音质", model.quality.title, onClick = actions.quality)
    PlayerOption(Icons.Outlined.Repeat, "播放模式", repeatLabel(model.playback.repeatMode), showChevron = false, onClick = model::cycleRepeat)
    PlayerOption(Icons.Outlined.Lyrics, "查看歌词", onClick = actions.lyrics)
    val remaining = model.playback.sleepTimerRemainingMs
    PlayerOption(Icons.Outlined.Timer, "定时停止播放", remaining.takeIf { it > 0 }?.let(::formatDuration),
        enabled = model.playback.ready, label = "睡眠定时", onClick = actions.timer)
    PlayerOption(Icons.AutoMirrored.Outlined.PlaylistAdd, "加入云歌单", enabled = model.playback.track?.online == true, onClick = actions.playlist)
}

// 每一行提供图标、明确名称和可选状态，整行都是触控区域。
@Composable
private fun PlayerOption(icon: ImageVector, title: String, value: String? = null, enabled: Boolean = true,
    showChevron: Boolean = true, label: String = title, onClick: () -> Unit) {
    val tint = if (enabled) KimoColors.Ink else KimoColors.Secondary.copy(alpha = 0.5f)
    val secondary = if (enabled) KimoColors.Secondary else KimoColors.Secondary.copy(alpha = 0.5f)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(KimoColors.Surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
        }
        Text(title, Modifier.weight(1f).padding(start = 13.dp), fontSize = 15.sp, color = tint)
        if (value != null) Text(value, fontSize = 13.sp, color = secondary)
        if (showChevron) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(22.dp), tint = secondary)
    }
}

// 循环模式在菜单中展示当前值，每次点按继续调用媒体会话的实际切换。
private fun repeatLabel(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ONE -> "单曲循环"
    Player.REPEAT_MODE_OFF -> "顺序播放"
    else -> "列表循环"
}

// 音质选项展示当前选择，仍由取流层根据账号权益处理降级。
@Composable
fun QualityDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    KimoActionSheet("在线音质", onDismiss, "根据当前账号权益播放，必要时降低音质") {
        AudioQuality.entries.forEach { quality ->
            KimoSheetOption(quality.title, Icons.Outlined.Equalizer, {
                model.changeQuality(quality); onDismiss()
            }, selected = model.quality == quality, showChevron = false)
        }
    }
}

// 添加歌曲必须选定具体云歌单，未登录时给出真实登录入口。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(model: MusicViewModel, onDismiss: () -> Unit) {
    val track = model.playback.track ?: return
    ModalBottomSheet(onDismiss, containerColor = KimoColors.Paper) {
        KimoTipWindow()
        Column(Modifier.padding(20.dp)) {
            Text("加入云歌单", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (model.account.credentials == null) TextButton({ model.loginRequested = true; onDismiss() }) { Text("登录酷狗") }
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(model.online.cloud.playlists, key = CatalogEntry::id) { entry ->
                    CatalogRow(entry, { model.online.addToPlaylist(entry, track, model::notice); onDismiss() })
                }
            }
        }
    }
}

// 音量面板直接绑定当前会话，不修改设备的全局音量。
@Composable
fun VolumeDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    KimoActionSheet("播放音量", onDismiss, "仅调整当前播放音量") {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = KimoColors.Ink)
            Text("${(model.playback.volume * 100).toInt()}%", Modifier.weight(1f),
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = KimoColors.Ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Slider(model.playback.volume, model::setVolume, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

private val SleepTimerMinutes = listOf(15, 30, 45, 60, 90)

// 每个选项设置真实后台停止任务，倒计时中也可以明确取消。
@Composable
fun SleepTimerDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    val remaining = model.playback.sleepTimerRemainingMs
    val subtitle = if (remaining > 0) "剩余 ${formatDuration(remaining)}" else "选择多久后停止播放"
    KimoActionSheet("定时停止播放", onDismiss, subtitle) {
        SleepTimerMinutes.forEach { minutes ->
            KimoSheetOption("$minutes 分钟后", Icons.Outlined.Timer, {
                model.setSleepTimer(SleepTimerRequest(minutes * 60_000L)); onDismiss()
            }, showChevron = false)
        }
        if (remaining > 0) KimoSheetOption("取消定时", Icons.Outlined.Close, {
            model.setSleepTimer(SleepTimerRequest(0)); onDismiss()
        }, showChevron = false)
    }
}
