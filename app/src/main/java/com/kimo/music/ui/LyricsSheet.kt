package com.kimo.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors

// 真正歌词按播放器进度高亮并自动滚动，点选一行可跳转。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(model: MusicViewModel, onDismiss: () -> Unit) {
    val lines = model.lyrics
    val current = lines.indexOfLast { it.timeMs <= model.playback.positionMs }.coerceAtLeast(0)
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (lines.isNotEmpty()) list.animateScrollToItem(current) }
    ModalBottomSheet(onDismiss, containerColor = KimoColors.Paper) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(model.playback.track.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            LyricStatus(model)
            LazyColumn(Modifier.heightIn(max = 500.dp), state = list, contentPadding = PaddingValues(vertical = 24.dp)) {
                itemsIndexed(lines) { index, line ->
                    Text(line.text, Modifier.fillMaxWidth().clickable { model.seek(line.timeMs) }.padding(vertical = 12.dp),
                        color = if (index == current) KimoColors.Orange else KimoColors.Secondary,
                        fontSize = if (index == current) 21.sp else 18.sp,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

// 歌词加载失败与无歌词分别表达，不以静态诗句替代在线歌词。
@Composable
fun LyricStatus(model: MusicViewModel) {
    if (model.lyricsLoading) CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
    else if (model.lyricsError != null) Column {
        Text(model.lyricsError.orEmpty(), color = KimoColors.Secondary)
        TextButton(model::retryLyrics) { Text("重试歌词") }
    } else if (model.lyrics.isEmpty()) Text("暂无歌词", Modifier.padding(vertical = 24.dp), color = KimoColors.Secondary)
}

data class LyricPreviewColors(val current: Color, val next: Color)

// 歌词预览允许浅色和深色播放页共用真实同步逻辑，横屏仅显示当前句。
@Composable
fun SyncedLyricPreview(model: MusicViewModel, onExpand: () -> Unit, compact: Boolean = false,
                      colors: LyricPreviewColors = LyricPreviewColors(KimoColors.Ink, KimoColors.Secondary),
                      showChevron: Boolean = true, visibleLines: Int = 2) {
    val current = model.lyrics.indexOfLast { it.timeMs <= model.playback.positionMs }.coerceAtLeast(0)
    TextButton(onExpand, Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (model.lyrics.isEmpty()) Text(if (model.lyricsLoading) "正在加载歌词…" else "查看歌词", color = colors.next)
            else {
                Text(model.lyrics[current].text, color = colors.current, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 1,
                    fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                for (offset in 1 until (if (compact) 1 else visibleLines.coerceIn(1, 5))) model.lyrics.getOrNull(current + offset)?.let {
                    Text(it.text, color = colors.next, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        if (showChevron) Icon(Icons.Outlined.ChevronRight, "展开歌词", Modifier.padding(start = 6.dp).size(16.dp), tint = colors.next)
    }
}
