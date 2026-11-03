package com.kimo.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.data.*
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import kotlinx.coroutines.launch

// 本地曲库提供分类、搜索与多选，所有删除操作仅移除导入记录。
@Composable
internal fun LocalLibraryScreen(model: MusicViewModel, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var grouping by rememberSaveable { mutableStateOf(LocalGrouping.Songs) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removing by remember { mutableStateOf<List<String>?>(null) }
    val tracks = model.localTracks + model.downloadedTracks
    val filtered = remember(tracks, query) { LocalLibraryIndex.filter(tracks, query) }
    val groups = remember(filtered, grouping) { LocalLibraryIndex.groups(filtered, grouping) }
    KimoFullScreen("本地曲库", onClose, actions = { TextButton({ selecting = !selecting; selected = emptyList() }) { Text(if (selecting) "完成" else "多选") } }) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text("搜索歌曲、歌手、专辑或文件夹") }, singleLine = true)
        LocalFilters(grouping, { grouping = it }, model) { removing = it }
        if (selecting) LocalBatchActions(filtered, selected, { selected = it }, model) { removing = it }
        if (filtered.isEmpty()) Text("没有匹配的本地音乐。", Modifier.padding(24.dp), color = KimoColors.Secondary)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                item(key = "group-${group.title}") { Text("${group.title} · ${group.tracks.size}", fontSize = 14.sp, color = KimoColors.Secondary) }
                items(group.tracks, key = Track::id) { track ->
                    LocalManagerRow(track, selecting, track.id in selected, { selected = if (track.id in selected) selected - track.id else selected + track.id }, model, filtered)
                }
            }
        }
    }
    removing?.let { ids -> ConfirmLocalRemoval(ids, model) { removing = null; selected = emptyList() } }
}

// 扫描仅核对现有导入记录的可读性，结果交给用户确认后才移除。
@Composable
private fun LocalFilters(grouping: LocalGrouping, onGrouping: (LocalGrouping) -> Unit, model: MusicViewModel, onInvalid: (List<String>) -> Unit) {
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LocalGrouping.entries.forEach { mode -> FilterChip(grouping == mode, { onGrouping(mode) }, label = { Text(mode.title) }) }
        TextButton({ scope.launch {
            checking = true
            try {
                val invalid = model.unreadableLocalTracks()
                if (invalid.isEmpty()) model.notice("导入的本地文件均可读取") else onInvalid(invalid.map(Track::id))
            } finally { checking = false }
        } }, enabled = !checking) { Text(if (checking) "正在检查…" else "检查失效文件") }
    }
}

// 全选仅作用于当前搜索结果，批量收藏不会触及云端账号。
@Composable
private fun LocalBatchActions(tracks: List<Track>, selected: List<String>, onSelect: (List<String>) -> Unit,
    model: MusicViewModel, onRemove: (List<String>) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ onSelect(if (tracks.isNotEmpty() && tracks.all { it.id in selected }) emptyList() else tracks.map(Track::id)) }) { Text("全选") }
        TextButton({ model.favoriteLocal(selected.toSet()) }, enabled = selected.isNotEmpty()) { Text("收藏 ${selected.size} 首") }
        TextButton({
            val imported = selected.filter { id -> model.localTracks.any { it.id == id } }
            if (imported.isEmpty()) model.notice("已下载文件请在下载管理中删除") else onRemove(imported)
        }, enabled = selected.isNotEmpty()) { Text("移除导入记录") }
    }
}

// 多选模式使用复选框，普通模式保留播放与长按歌曲操作。
@Composable
private fun LocalManagerRow(track: Track, selecting: Boolean, selected: Boolean, onToggle: () -> Unit,
    model: MusicViewModel, collection: List<Track>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (selecting) Checkbox(selected, { onToggle() })
        TrackRow(track, { if (selecting) onToggle() else model.play(track, collection) }, { model.enqueue(track) },
            Modifier.weight(1f), showAction = !selecting)
    }
}

// 确认框明确保留原文件，避免将曲库整理误解为文件系统删除。
@Composable
private fun ConfirmLocalRemoval(ids: List<String>, model: MusicViewModel, onClose: () -> Unit) {
    AlertDialog(onClose, title = { Text("移除 ${ids.size} 条导入记录？") },
        text = { KimoTipWindow(); Text("原始音频文件、云端收藏和歌单都会保留。") },
        confirmButton = { TextButton({ model.removeLocal(ids.toSet()); onClose() }) { Text("移除记录") } },
        dismissButton = { TextButton(onClose) { Text("取消") } })
}
