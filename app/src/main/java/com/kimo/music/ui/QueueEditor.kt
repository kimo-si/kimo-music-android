package com.kimo.music.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.data.Track
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import kotlinx.coroutines.launch

private data class QueueEditorState(val tracks: List<Track>, val currentId: String?, val favorites: Set<String>)

// 队列编辑读取独立快照，播放进度刷新不会重新排列整张列表。
@Composable
internal fun EditableQueue(model: MusicViewModel, onDismiss: () -> Unit) {
    val state by remember(model) { derivedStateOf { QueueEditorState(model.playback.queue, model.playback.track?.id, model.favorites) } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.tracks.indexOfFirst { it.id == state.currentId }.coerceAtLeast(0))
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表 · ${state.tracks.size}", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton({ confirming = true }, enabled = state.tracks.isNotEmpty()) { Text("清空") }
        }
        Text("长按左侧手柄拖动排序", color = KimoColors.Secondary, fontSize = 12.sp)
        if (state.tracks.isEmpty()) Text("播放列表为空", Modifier.padding(vertical = 24.dp), color = KimoColors.Secondary)
        LazyColumn(Modifier.testTag("queue-song-list").heightIn(max = 430.dp), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.tracks, key = Track::id, contentType = { "queue-song" }) { track ->
                EditableQueueRow(track, state, list, model, Modifier.animateItem()) { model.selectQueued(track); onDismiss() }
            }
        }
    }
    if (confirming) AlertDialog({ confirming = false }, title = { Text("清空播放队列？") },
        text = { KimoTipWindow(); Text("将停止播放并清空临时队列，歌单与收藏会保留。") },
        confirmButton = { TextButton({ model.clearQueue(); confirming = false }) { Text("清空队列") } },
        dismissButton = { TextButton({ confirming = false }) { Text("取消") } })
}

// 每行保留播放、喜欢与移除入口，拖动仅作用于专用手柄。
@Composable
private fun EditableQueueRow(track: Track, state: QueueEditorState, list: LazyListState, model: MusicViewModel,
    modifier: Modifier, onPlay: () -> Unit) {
    var translation by remember { mutableFloatStateOf(0f) }
    val currentTracks by rememberUpdatedState(state.tracks)
    val scope = rememberCoroutineScope()
    val handle = Modifier.pointerInput(track.id) {
        detectDragGesturesAfterLongPress(onDragStart = { translation = 0f }, onDragEnd = { translation = 0f },
            onDragCancel = { translation = 0f }) { change, amount ->
            change.consume(); translation += amount.y
            val source = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.id } ?: return@detectDragGesturesAfterLongPress
            val center = source.offset + source.size / 2 + translation
            val target = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != track.id && center in it.offset.toFloat()..(it.offset + it.size).toFloat() }
            if (target != null && currentTracks.any { it.id == target.key }) {
                translation -= target.offset - source.offset; model.moveQueued(track.id, target.key as String)
            }
            if (center < list.layoutInfo.viewportStartOffset + source.size || center > list.layoutInfo.viewportEndOffset - source.size)
                scope.launch { list.scrollBy(amount.y) }
        }
    }
    Row(modifier.graphicsLayer { translationY = translation }.semantics {
        customActions = listOf(CustomAccessibilityAction("上移") { moveQueueNeighbour(track, currentTracks, -1, model) },
            CustomAccessibilityAction("下移") { moveQueueNeighbour(track, currentTracks, 1, model) })
    }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.DragHandle, "拖动排序：${track.title}", Modifier.size(36.dp).then(handle).testTag("queue-drag-${track.id}"), tint = KimoColors.Secondary)
        TrackRow(track, onPlay, { model.toggleFavorite(track) }, Modifier.weight(1f), track.id == state.currentId,
            if (track.id in state.favorites) TrackRowAction.Unlike else TrackRowAction.Like)
        ActionIcon("移出队列：${track.title}", Icons.Outlined.Close, tint = KimoColors.Secondary) { model.removeQueued(track.id) }
    }
}

// 无障碍上下移与拖动使用相同的队列操作，边界处保持原顺序。
private fun moveQueueNeighbour(track: Track, tracks: List<Track>, delta: Int, model: MusicViewModel): Boolean {
    val index = tracks.indexOfFirst { it.id == track.id }
    val target = tracks.getOrNull(index + delta) ?: return false
    model.moveQueued(track.id, target.id); return true
}
