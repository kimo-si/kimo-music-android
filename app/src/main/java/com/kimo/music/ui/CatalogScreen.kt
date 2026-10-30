package com.kimo.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kimo.music.R
import com.kimo.music.core.*
import com.kimo.music.data.Track
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout

// 目录封面保留原始比例裁切，网络失败时提供安静的本地占位。
@Composable
fun CatalogArtwork(entry: CatalogEntry, modifier: Modifier) {
    AsyncImage(entry.coverUrl, null, modifier.clip(RoundedCornerShape(12.dp)),
        placeholder = painterResource(R.drawable.sunset_coast), error = painterResource(R.drawable.sunset_coast),
        contentScale = ContentScale.Crop)
}

// 在线歌单卡片与参考图共用底部遮罩和左对齐标题。
@Composable
fun CatalogCard(entry: CatalogEntry, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(KimoLayout.CardRadius)).clickable(onClick = onOpen)) {
        CatalogArtwork(entry, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (entry.count > 0) Text("${entry.count} 首", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        }
    }
}

// 目录行支持歌单、榜单、艺人、专辑与电台的统一导航。
@Composable
fun CatalogRow(entry: CatalogEntry, onOpen: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CatalogArtwork(entry, Modifier.size(56.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.count > 0) Text("${entry.count} 首", fontSize = 12.sp, color = KimoColors.Secondary)
            }
        }
        if (trailing != null) trailing() else ActionIcon("打开${entry.title}", Icons.Outlined.ChevronRight, onClick = onOpen)
    }
}

// 网络错误和正常空内容分别呈现，重试不会清掉已经加载的曲目。
@Composable
fun <T> FeedFooter(feed: PagedFeed<T>, emptyText: String = "暂时没有内容") {
    val state = feed.state
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        if (state.error != null) {
            Text(state.error, color = KimoColors.Secondary, fontSize = 13.sp)
            TextButton({ feed.load() }) { Text("重试") }
        } else if (!state.loading && state.items.isEmpty()) Text(emptyText, color = KimoColors.Secondary, fontSize = 13.sp)
        else if (!state.loading && state.nextPage != null) TextButton({ feed.load() }) { Text("加载更多") }
    }
}

// 歌单、榜单、艺人与专辑详情共享真实分页和播放全部入口。
@Composable
fun CatalogDetailScreen(detail: CatalogDetail, model: MusicViewModel, onClose: () -> Unit,
                        onPlay: (Track, List<Track>) -> Unit) {
    val songs = detail.songs.state.items
    var managing by remember(detail.entry.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(KimoLayout.PageInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            ActionIcon("返回曲库", Icons.AutoMirrored.Outlined.ArrowBack, onClick = onClose)
            Text(detail.entry.title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
            TextButton({ detail.songs.load(true) }) { Text("刷新") }
            if (detail.entry.kind == CatalogKind.CloudPlaylist) ActionIcon("管理云歌单", Icons.Outlined.MoreHoriz) { managing = true }
        } }
        item { DetailHeader(detail.entry, model.online.loadingAll) {
            model.online.playAll(detail.entry, { queue -> queue.firstOrNull()?.let { onPlay(it, queue) } }, model::notice)
        } }
        if (detail.profile != null) item { ArtistInformation(detail, model) }
        items(songs, key = Track::id) { track -> TrackRow(track, { onPlay(track, songs) }, { model.enqueue(track) }) }
        item { FeedFooter(detail.songs, "该目录暂时没有可显示的歌曲") }
        if (detail.albums != null) {
            item { SectionHeading("歌手专辑") }
            items(detail.albums.state.items, key = CatalogEntry::id) { album -> CatalogRow(album, { model.online.open(album) }) }
            item { FeedFooter(detail.albums) }
        }
    }
    if (managing) ManagePlaylistDialog(detail.entry, model) { managing = false }
}

// 歌手详情保留简介、统计与热门/最新单曲切换。
@Composable
private fun ArtistInformation(detail: CatalogDetail, model: MusicViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        detail.profile?.state?.items?.firstOrNull()?.let { profile ->
            Text("${profile.followers} 位关注者 · ${profile.albumCount} 张专辑", color = KimoColors.Secondary, fontSize = 12.sp)
            if (profile.introduction.isNotBlank()) Text(profile.introduction, fontSize = 13.sp, lineHeight = 21.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtistOrder.entries.forEach { order -> FilterChip(detail.order == order,
                { model.online.open(detail.entry, order) }, label = { Text(order.title) }) }
        }
        detail.profile?.let { if (it.state.error != null) FeedFooter(it) }
    }
}

// 详情头显示服务端曲目数，播放全部针对已加载页明确标注。
@Composable
private fun DetailHeader(entry: CatalogEntry, loading: Boolean, onPlay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CatalogArtwork(entry, Modifier.fillMaxWidth().height(180.dp))
        if (entry.description.isNotBlank()) Text(entry.description, color = KimoColors.Secondary, fontSize = 13.sp)
        Button(onPlay, enabled = !loading) { Text(if (loading) "正在加载完整曲库…" else "播放全部") }
    }
}
