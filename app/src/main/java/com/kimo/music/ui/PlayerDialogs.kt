package com.kimo.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.kimo.music.data.Track
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
        QueueContent(model.playback.queue, model.playback.track.id, model.favorites)
    } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = content.tracks.indexOfFirst { it.id == content.currentId }.coerceAtLeast(0))
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = KimoColors.Paper) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("播放列表 · ${content.tracks.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
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

private data class QueueContent(val tracks: List<Track>, val currentId: String, val favorites: Set<String>)

data class PlayerMenuActions(val quality: () -> Unit, val playlist: () -> Unit,
    val lyrics: () -> Unit, val volume: () -> Unit, val download: () -> Unit, val timer: () -> Unit)

// 右上角菜单集中承载下载、音量、音质与循环模式，底部只保留主播放操作。
@Composable
fun PlayerOptionsDialog(model: MusicViewModel, onDismiss: () -> Unit, actions: PlayerMenuActions) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("播放选项") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            TextButton(actions.download) { Text("下载音频") }
            TextButton(actions.volume) { Text("音量") }
            TextButton(actions.quality) { Text("音质 · ${model.quality.title}") }
            TextButton(model::cycleRepeat) { Text("播放模式 · ${repeatLabel(model.playback.repeatMode)}") }
            TextButton(actions.lyrics) { Text("查看歌词") }
            TextButton(actions.timer, Modifier.semantics { contentDescription = "睡眠定时" }, enabled = model.playback.ready) {
                val remaining = model.playback.sleepTimerRemainingMs
                Text(if (remaining > 0) "定时停止 · ${com.kimo.music.data.formatDuration(remaining)}" else "定时停止播放")
            }
            TextButton(actions.playlist, enabled = model.playback.track.online) { Text("加入云歌单") }
            TextButton({ model.loginRequested = true; onDismiss() }) { Text("账号与同步") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}

// 循环模式在菜单中展示当前值，每次点按继续调用媒体会话的实际切换。
private fun repeatLabel(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ONE -> "单曲循环"
    Player.REPEAT_MODE_OFF -> "顺序播放"
    else -> "列表循环"
}

// 音质只使用官方接口返回的账号可用地址，不能获得的档位由取流层降级。
@Composable
fun QualityDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("在线音质") }, text = {
        Column {
            AudioQuality.entries.forEach { quality ->
                TextButton({ model.changeQuality(quality); onDismiss() }) {
                    Text(if (model.quality == quality) "✓ ${quality.title}" else quality.title)
                }
            }
            Text("按照当前账号权益播放，必要时降低音质。", color = KimoColors.Secondary, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}

// 添加歌曲必须选定具体云歌单，未登录时给出真实登录入口。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(model: MusicViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, containerColor = KimoColors.Paper) {
        Column(Modifier.padding(20.dp)) {
            Text("加入云歌单", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (model.account.credentials == null) TextButton({ model.loginRequested = true; onDismiss() }) { Text("登录酷狗") }
            else LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(model.online.cloud.playlists, key = CatalogEntry::id) { entry ->
                    CatalogRow(entry, { model.online.addToPlaylist(entry, model.playback.track, model::notice); onDismiss() })
                }
            }
        }
    }
}

// 音量弹层直接绑定当前会话，不修改设备的全局音量。
@Composable
fun VolumeDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = KimoColors.Paper,
        title = { Text("播放音量") }, text = {
            Column {
                Text("${(model.playback.volume * 100).toInt()}%")
                Slider(model.playback.volume, model::setVolume)
            }
        }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}

private val SleepTimerMinutes = listOf(15, 30, 45, 60, 90)

// 每个选项设置真实后台停止任务，倒计时中也可以明确取消。
@Composable
fun SleepTimerDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("定时停止播放") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (model.playback.sleepTimerRemainingMs > 0) Text("剩余 ${com.kimo.music.data.formatDuration(model.playback.sleepTimerRemainingMs)}",
                color = KimoColors.Secondary)
            SleepTimerMinutes.forEach { minutes ->
                TextButton({ model.setSleepTimer(SleepTimerRequest(minutes * 60_000L)); onDismiss() }) { Text("$minutes 分钟后") }
            }
            if (model.playback.sleepTimerRemainingMs > 0) TextButton({
                model.setSleepTimer(SleepTimerRequest(0)); onDismiss()
            }) { Text("取消定时") }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}
