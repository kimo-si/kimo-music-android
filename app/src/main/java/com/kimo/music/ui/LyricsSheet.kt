package com.kimo.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.core.LyricLine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

private object LyricMotionStyle {
    const val ScrollMs = 420
    const val SeekOutMs = 90
    const val SeekInMs = 180
    const val NextScale = 0.875f
    val LineHeight = 28.sp
}

internal class LyricMotion(initial: Int) {
    val position = Animatable(initial.toFloat())
    val opacity = Animatable(1f)

    // 正常换行连续滑动；大幅跳转先淡出再定位，快速连续操作从当前动画位置接续。
    suspend fun moveTo(index: Int, visibleLines: Int) {
        val target = index.toFloat()
        if (target == position.value && opacity.value == 1f) return
        if (abs(target - position.value) > visibleLines.coerceAtLeast(2)) {
            opacity.animateTo(0f, tween(LyricMotionStyle.SeekOutMs))
            position.snapTo(target)
            opacity.animateTo(1f, tween(LyricMotionStyle.SeekInMs))
            return
        }
        coroutineScope {
            launch { opacity.animateTo(1f, tween(LyricMotionStyle.SeekOutMs)) }
            position.animateTo(target, tween(LyricMotionStyle.ScrollMs, easing = FastOutSlowInEasing))
        }
    }
}

// 真正歌词按播放器进度高亮并自动滚动，点选一行可跳转。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(model: MusicViewModel, onDismiss: () -> Unit) {
    val lines = model.lyrics
    val current = lines.indexOfLast { it.timeMs <= model.playback.positionMs }.coerceAtLeast(0)
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (lines.isNotEmpty()) list.animateScrollToItem(current) }
    ModalBottomSheet(onDismiss, containerColor = KimoColors.Paper) {
        KimoTipWindow()
        Column(Modifier.padding(horizontal = 24.dp)) {
            model.playback.track?.let { Text(it.title, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
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
        Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.Center) {
            if (model.lyrics.isEmpty()) Text(if (model.lyricsLoading) "正在加载歌词…" else "暂无歌词", color = colors.next)
            else key(model.playback.track?.id) {
                AnimatedLyricLines(model.lyrics, current, if (compact) 1 else visibleLines, colors)
            }
        }
        if (showChevron) Icon(Icons.Outlined.ChevronRight, "展开歌词", Modifier.padding(start = 6.dp).size(16.dp), tint = colors.next)
    }
}

// 只组合视口附近的歌词，固定行高防止换行、长句或末尾歌词引发页面跳动。
@Composable
internal fun AnimatedLyricLines(lines: List<LyricLine>, current: Int, visibleLines: Int,
    colors: LyricPreviewColors, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val count = visibleLines.coerceIn(1, 5)
    val target = current.coerceIn(lines.indices)
    val motion = remember(lines) { LyricMotion(target) }
    val first by remember(motion) { derivedStateOf { floor(motion.position.value).toInt() } }
    val rowHeight = with(LocalDensity.current) { LyricMotionStyle.LineHeight.toDp() }
    // 动画帧仅改变图层和文字绘制颜色，不逐帧重排整个播放页面。
    LaunchedEffect(target, motion) { motion.moveTo(target, count) }
    Box(modifier.fillMaxWidth().height(rowHeight * count).clipToBounds().testTag("animated-lyrics")
        .graphicsLayer { alpha = motion.opacity.value }) {
        for (index in (first - 1).coerceAtLeast(0)..(first + count).coerceAtMost(lines.lastIndex)) key(index) {
            AnimatedLyricLine(lines[index], index, count, rowHeight, motion, colors)
        }
    }
}

// 同一行文字从预告位置连续上移到当前行，字号、颜色与边缘透明度沿同一进度变化。
@Composable
private fun AnimatedLyricLine(line: LyricLine, index: Int, count: Int, height: androidx.compose.ui.unit.Dp,
    motion: LyricMotion, colors: LyricPreviewColors) {
    val rowPixels = with(LocalDensity.current) { height.toPx() }
    Box(Modifier.fillMaxWidth().height(height).testTag("lyric-line-$index").graphicsLayer {
        val distance = index - motion.position.value
        translationY = distance * rowPixels
        val emphasis = (1f - abs(distance)).coerceIn(0f, 1f)
        scaleX = LyricMotionStyle.NextScale + (1f - LyricMotionStyle.NextScale) * emphasis
        scaleY = scaleX
        alpha = (1f + distance).coerceIn(0f, 1f) * (count - distance).coerceIn(0f, 1f)
    }, contentAlignment = Alignment.Center) {
        BasicText(line.text, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            color = { lerp(colors.next, colors.current, (1f - abs(index - motion.position.value)).coerceIn(0f, 1f)) })
    }
}
