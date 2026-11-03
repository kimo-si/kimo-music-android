package com.kimo.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout

private object MiniPlayerStyle {
    val Shape = RoundedCornerShape(KimoLayout.MiniPlayerRadius)
    val Background: Color @Composable get() = KimoColors.Surface
    val Border: Color @Composable get() = KimoColors.Rail.copy(alpha = 0.8f)
    val Title: Color @Composable get() = KimoColors.Ink.copy(alpha = 0.92f)
    val Secondary: Color @Composable get() = KimoColors.Ink.copy(alpha = 0.68f)
    val Control: Color @Composable get() = KimoColors.Ink.copy(alpha = 0.8f)
}

// 暖米色播放条以浅边框融入页面，与完整播放页共享媒体服务状态。
@Composable
fun MiniPlayer(model: MusicViewModel, onExpand: () -> Unit, onQueue: () -> Unit, showProgress: Boolean = false,
               onBoundsChanged: (Rect) -> Unit = {}) {
    val preview = LocalSongFlight.current?.previewTrack ?: model.preparingTrack
    val track = preview ?: model.playback.track ?: return
    Box(Modifier.padding(horizontal = KimoLayout.MiniPlayerInset)) {
        Box(Modifier.fillMaxWidth().height(KimoLayout.MiniPlayerHeight).onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) }.clip(MiniPlayerStyle.Shape)
            .background(MiniPlayerStyle.Background).border(1.dp, MiniPlayerStyle.Border, MiniPlayerStyle.Shape)) {
            MiniPlayerContent(model, track, onExpand, onQueue, preview != null)
            if (showProgress) MiniPlayerProgress(model, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 12.dp).height(2.dp), preview != null)
        }
    }
}

// 播放条内容共用实际媒体会话，个人页仅额外显示进度细线。
@Composable
private fun MiniPlayerContent(model: MusicViewModel, track: com.kimo.music.data.Track, onExpand: () -> Unit,
    onQueue: () -> Unit, waiting: Boolean) {
    val state = model.playback
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = !waiting, onClick = onExpand).semantics { contentDescription = "打开播放页" },
            verticalAlignment = Alignment.CenterVertically) {
            MiniPlayerArtwork(track)
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TrackTitle(track, color = MiniPlayerStyle.Title, fontSize = 14.sp)
                if (track.displayArtist.isNotBlank()) Text(track.displayArtist, color = MiniPlayerStyle.Secondary, fontSize = 11.sp, maxLines = 1)
            }
        }
        WaveMark(state.playing && !waiting)
        IconButton(model::togglePlaying, Modifier.size(48.dp), enabled = state.ready && !waiting) {
            Icon(if (state.playing && !waiting) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing && !waiting) "暂停" else "播放", Modifier.size(37.dp).background(KimoColors.Paper, CircleShape)
                    .border(1.dp, KimoColors.Rail, CircleShape).padding(7.dp), tint = MiniPlayerStyle.Control)
        }
        ActionIcon("播放列表", Icons.Outlined.QueueMusic, tint = MiniPlayerStyle.Control, enabled = !waiting, onClick = onQueue)
    }
}

// 进度按真实时长绘制，暂停或未开始播放时不填充虚构进度。
@Composable
private fun MiniPlayerProgress(model: MusicViewModel, modifier: Modifier, waiting: Boolean) {
    val state = model.playback
    val progress = if (!waiting && state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    val rail = KimoColors.Rail
    val accent = KimoColors.Orange
    Canvas(modifier) {
        drawRoundRect(rail, cornerRadius = CornerRadius(size.height))
        drawRoundRect(accent, size = Size(size.width * progress, size.height), cornerRadius = CornerRadius(size.height))
    }
}

// 波形作为播放状态标识，暂停时收平，不引入常驻动画或额外音频采样。
@Composable
private fun WaveMark(playing: Boolean) {
    val accent = KimoColors.Orange
    Canvas(Modifier.size(width = 30.dp, height = 26.dp).padding(horizontal = 2.dp)) {
        val bars = if (playing) listOf(0.25f, 0.6f, 1f, 0.65f, 0.35f) else List(5) { 0.15f }
        val step = size.width / bars.size
        bars.forEachIndexed { index, height ->
            drawRoundRect(accent, Offset(index * step, size.height * (1f - height) / 2),
                Size(step * 0.48f, size.height * height), CornerRadius(3f))
        }
    }
}
