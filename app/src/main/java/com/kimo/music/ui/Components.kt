package com.kimo.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.data.ListeningMood
import com.kimo.music.R
import com.kimo.music.data.Track
import com.kimo.music.data.online
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout
import coil3.compose.AsyncImage

enum class TrackRowAction(val label: String, val icon: ImageVector) {
    Enqueue("加入播放列表", Icons.Outlined.AddCircleOutline),
    Like("加入我喜欢", Icons.Outlined.FavoriteBorder),
    Unlike("取消喜欢", Icons.Outlined.Favorite),
}

// 装饰性封面由父级歌曲标题描述，避免读屏重复朗读图片名称。
@Composable
fun TrackArtwork(track: Track, modifier: Modifier = Modifier) {
    val bundled = !track.online && !track.local
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(KimoColors.Surface), contentAlignment = Alignment.Center) {
        if (!bundled) Icon(Icons.Outlined.MusicNote, null, Modifier.fillMaxSize(0.3f), tint = KimoColors.Secondary)
        AsyncImage(track.coverUrl ?: track.artwork.takeIf { bundled }, null, Modifier.matchParentSize(),
            placeholder = if (bundled) painterResource(track.artwork) else null,
            error = if (bundled) painterResource(track.artwork) else null, contentScale = ContentScale.Crop)
    }
}

// 所有纯图标按钮提供操作名称与统一的 48dp 触控区域。
@Composable
fun ActionIcon(label: String, icon: ImageVector, modifier: Modifier = Modifier,
               tint: Color = KimoColors.Ink, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier.size(KimoLayout.TouchTarget), enabled = enabled) {
        Icon(icon, label, Modifier.size(25.dp), tint = tint.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

// 章节标题保持同一基线，右侧按钮同时支持图标和辅助功能名称。
@Composable
fun SectionHeading(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (onMore != null) ActionIcon("查看$title", Icons.Outlined.ChevronRight, onClick = onMore)
    }
}

// 歌曲行将播放入口与加入队列入口分开，避免嵌套点击事件。
@Composable
fun TrackRow(track: Track, onPlay: () -> Unit, onAdd: () -> Unit,
             modifier: Modifier = Modifier, active: Boolean = false, action: TrackRowAction = TrackRowAction.Enqueue) {
    val selection = rememberSongSelection(onPlay)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = selection.play)
            .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(track, Modifier.size(54.dp).onGloballyPositioned(selection.anchor::update))
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(track.title, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = if (active) KimoColors.Orange else KimoColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} · ${track.album}", fontSize = 12.sp, color = KimoColors.Secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ActionIcon("${action.label}：${track.title}", action.icon,
            tint = if (action == TrackRowAction.Unlike) KimoColors.Orange else KimoColors.Ink, onClick = onAdd)
    }
}

// 精选和灵感卡片共用固定的底部遮罩，文字始终压在照片暗部。
@Composable
fun MoodCard(mood: ListeningMood, modifier: Modifier = Modifier, featured: Boolean = false, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(KimoLayout.CardRadius)).clickable(onClick = onClick)) {
        Image(painterResource(mood.artwork), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.78f)))))
        MoodCaption(mood, featured, Modifier.align(Alignment.BottomStart))
        if (featured) Icon(Icons.Rounded.PlayArrow, "播放日落电台", tint = KimoColors.Ink,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(44.dp)
                .background(KimoColors.Paper, CircleShape).padding(10.dp))
    }
}

// 主卡片使用两级文字，双列卡片保留一句短说明。
@Composable
private fun MoodCaption(mood: ListeningMood, featured: Boolean, modifier: Modifier) {
    Column(modifier.padding(if (featured) 18.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (featured) Text("今日精选", color = Color.White, fontSize = 12.sp)
        Text(if (featured) mood.subtitle else mood.title, color = Color.White,
            fontSize = if (featured) 23.sp else 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = if (featured) 42.dp else 0.dp))
        if (!featured) Text(mood.subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp,
            maxLines = 2, lineHeight = 16.sp)
    }
}

// 收藏卡使用实际歌曲封面叠放黑胶，空收藏只显示装饰封面而不伪造曲目。
@Composable
fun CollectionRecordArtwork(track: Track?, modifier: Modifier = Modifier) {
    Box(modifier) {
        VinylArtwork(Modifier.align(Alignment.CenterEnd).size(96.dp))
        val cover = Modifier.align(Alignment.CenterStart).size(88.dp)
            .shadow(6.dp, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp))
        if (track != null) TrackArtwork(track, cover)
        else Image(painterResource(R.drawable.sunset_coast), null, cover, contentScale = ContentScale.Crop)
    }
}

// 黑胶纹理由矢量绘制，随着屏幕密度保持清晰，不引入额外位图资源。
@Composable
private fun VinylArtwork(modifier: Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color(0xFF484741), Color(0xFF151612), Color(0xFF34352F))))
        for (ring in 5..14) drawCircle(Color.White.copy(alpha = 0.055f), radius * ring / 15f,
            style = Stroke(width = radius * 0.01f))
        drawCircle(KimoColors.Orange, radius * 0.34f)
        drawCircle(KimoColors.Paper, radius * 0.045f)
    }
}
