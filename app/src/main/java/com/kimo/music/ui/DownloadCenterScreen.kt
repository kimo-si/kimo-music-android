package com.kimo.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.downloads.*
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import kotlin.math.roundToInt

// 下载管理按真实任务状态显示，离线曲目直接使用已经完成的本机文件。
@Composable
internal fun DownloadCenterScreen(model: MusicViewModel, onClose: () -> Unit) {
    val items by model.downloads.items.collectAsState()
    var completedOnly by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DownloadItem?>(null) }
    KimoFullScreen("下载管理", onClose) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!completedOnly, { completedOnly = false }, label = { Text("全部 ${items.size}") })
            FilterChip(completedOnly, { completedOnly = true }, label = { Text("已下载 ${items.count { it.status == DownloadStatus.Complete }}") })
        }
        val visible = items.filter { !completedOnly || it.status == DownloadStatus.Complete }
        if (visible.isEmpty()) Text("还没有下载，长按在线歌曲即可加入下载。", Modifier.padding(24.dp), color = KimoColors.Secondary)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(visible, key = DownloadItem::id) { item -> DownloadCard(item, model, { deleting = item }, onClose) }
        }
    }
    deleting?.let { item -> ConfirmDownloadRemoval(item, model) { deleting = null } }
}

// 歌曲信息、字节进度和失败原因来自同一条下载记录。
@Composable
private fun DownloadCard(item: DownloadItem, model: MusicViewModel, onRemove: () -> Unit, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackArtwork(item.track, Modifier.size(50.dp))
            Column(Modifier.weight(1f)) {
                TrackTitle(item.track, fontWeight = FontWeight.SemiBold)
                Text("${item.quality.title} · ${item.status.title}", color = KimoColors.Secondary, fontSize = 12.sp)
            }
            ActionIcon("删除下载：${item.track.title}", Icons.Outlined.DeleteOutline, onClick = onRemove)
        }
        if (item.status != DownloadStatus.Complete) {
            if (item.total > 0) LinearProgressIndicator(progress = { (item.received.toFloat() / item.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            else if (item.status == DownloadStatus.Downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text("${formatDownloadBytes(item.received)}${if (item.total > 0) " / ${formatDownloadBytes(item.total)}" else ""}",
            color = KimoColors.Secondary, fontSize = 12.sp)
        if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        DownloadActions(item, model, onClose)
        HorizontalDivider(color = KimoColors.Rail)
    }
}

// 暂停、继续和重试都调用实际下载服务，完成项才提供播放按钮。
@Composable
private fun DownloadActions(item: DownloadItem, model: MusicViewModel, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (item.status) {
            DownloadStatus.Complete -> TextButton({
                model.play(model.downloads.localTrack(item), model.downloadedTracks); onClose()
            }) { Text("离线播放") }
            DownloadStatus.Downloading, DownloadStatus.Queued -> TextButton({ model.downloadCommand(item.id, AudioDownloadService.PAUSE) }) { Text("暂停") }
            DownloadStatus.Paused, DownloadStatus.Failed -> TextButton({ model.downloadCommand(item.id, AudioDownloadService.RESUME) }) {
                Text(if (item.status == DownloadStatus.Failed) "重试下载" else "继续下载")
            }
        }
        if (item.error.contains("登录")) TextButton({ model.loginRequested = true }) { Text("去登录") }
    }
}

// 删除前明确说明作用范围，正在播放的本机下载项也从临时队列移除。
@Composable
private fun ConfirmDownloadRemoval(item: DownloadItem, model: MusicViewModel, onClose: () -> Unit) {
    AlertDialog(onClose, title = { Text("删除「${item.track.title}」的下载？") },
        text = { KimoTipWindow(); Text("将删除这首歌的下载文件与任务记录，原始导入文件和云端收藏不受影响。") },
        confirmButton = { TextButton({
            if (model.playback.queue.any { it.id == item.track.id && it.local }) model.removeQueued(item.track.id)
            model.downloadCommand(item.id, AudioDownloadService.REMOVE); onClose()
        }) { Text("删除下载") } }, dismissButton = { TextButton(onClose) { Text("取消") } })
}

// 用统一单位展示下载量，未知大小保留实际已收到的字节。
internal fun formatDownloadBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${(bytes / (1024f * 1024 * 1024) * 10).roundToInt() / 10f} GB"
    bytes >= 1024L * 1024 -> "${(bytes / (1024f * 1024) * 10).roundToInt() / 10f} MB"
    else -> "${bytes.coerceAtLeast(0) / 1024} KB"
}
