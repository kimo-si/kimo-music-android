package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.CatalogEntry
import com.kimo.music.data.Track
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout

private enum class HomeTab(val title: String) { Featured("推荐"), New("新歌"), Radio("电台") }

// 首页保留参考布局，每个栏目独立请求真实酷狗数据。
@Composable
fun HomeScreen(model: MusicViewModel, onSearch: () -> Unit, onDiscover: () -> Unit,
               onPlay: (Track, List<Track>) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Featured) }
    HomeFeedEffect(model, tab)
    Column(Modifier.fillMaxSize().padding(horizontal = KimoLayout.PageInset)) {
        // 顶部保持原有字号、配色与间距，内容滚动时仍固定在原位置。
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeHeader(model, onSearch) { model.loginRequested = true }
            Greeting()
            HomeTabs(tab) { tab = it }
        }
        HomeScrollableFeed(model, tab, onDiscover, onPlay, Modifier.weight(1f))
    }
}

// 栏目变化只加载对应的真实数据，固定页头不参与列表滚动。
@Composable
private fun HomeFeedEffect(model: MusicViewModel, tab: HomeTab) {
    LaunchedEffect(tab) {
        when (tab) {
            HomeTab.Featured -> { model.online.playlists.ensureLoaded(); model.online.recommendations.ensureLoaded() }
            HomeTab.New -> model.online.newSongs.ensureLoaded()
            HomeTab.Radio -> model.online.radios.ensureLoaded()
        }
    }
}

// 仅下方的卡片和歌曲列表拥有滚动状态，保留原有内容间距。
@Composable
private fun HomeScrollableFeed(model: MusicViewModel, tab: HomeTab, onDiscover: () -> Unit,
                               onPlay: (Track, List<Track>) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tab) {
            HomeTab.Featured -> featuredItems(model, onDiscover, onPlay)
            HomeTab.New -> newSongItems(model, onPlay)
            HomeTab.Radio -> radioItems(model)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// 推荐区按层次展示精选、灵感、歌曲与个人历史。
private fun androidx.compose.foundation.lazy.LazyListScope.featuredItems(model: MusicViewModel, onDiscover: () -> Unit,
                                                                        onPlay: (Track, List<Track>) -> Unit) {
    model.online.recommendations.state.items.firstOrNull()?.let { track ->
        item { FeaturedCard(track) { onPlay(track, model.online.recommendations.state.items) } }
    }
    item { InspirationSection(model, onDiscover) }
    item { SectionHeading("为你推荐", onDiscover) }
    items(model.online.recommendations.state.items, key = Track::id) { track ->
        TrackRow(track, { onPlay(track, model.online.recommendations.state.items) }, { model.enqueue(track) })
    }
    item { FeedFooter(model.online.recommendations, "登录后获取为你推荐的音乐") }
    if (model.recentTracks.isNotEmpty()) {
        item { SectionHeading("最近常听") }
        items(model.recentTracks.take(5), key = { "recent-${it.id}" }) { track ->
            TrackRow(track, { onPlay(track, model.recentTracks) }, { model.enqueue(track) })
        }
    }
}

// 新歌分页复用真实加载队列。
private fun androidx.compose.foundation.lazy.LazyListScope.newSongItems(model: MusicViewModel, onPlay: (Track, List<Track>) -> Unit) {
    items(model.online.newSongs.state.items, key = Track::id) { track ->
        TrackRow(track, { onPlay(track, model.online.newSongs.state.items) }, { model.enqueue(track) })
    }
    item { FeedFooter(model.online.newSongs, "暂时没有新歌") }
}

// 电台条目导航到对应的完整曲目目录。
private fun androidx.compose.foundation.lazy.LazyListScope.radioItems(model: MusicViewModel) {
    items(model.online.radios.state.items, key = CatalogEntry::id) { entry -> CatalogRow(entry, { model.online.open(entry) }) }
    item { FeedFooter(model.online.radios, "暂时没有电台") }
}

// 账号头像来自已登录用户，未登录时保留统一的暖色入口。
@Composable
private fun HomeHeader(model: MusicViewModel, onSearch: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Kimo", Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        ActionIcon("搜索音乐", Icons.Outlined.Search, onClick = onSearch)
        IconButton(onProfile, Modifier.size(48.dp)) {
            Box(Modifier.semantics { contentDescription = "账号信息" }) {
                AccountAvatar(model.account.state.profile?.avatar, Modifier.size(34.dp))
            }
        }
    }
}

// 维持参考图的标题字号层次。
@Composable
private fun Greeting() {
    Column(Modifier.padding(top = 1.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("为此刻，选一首。", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
        Text("今天也有好音乐。", fontSize = 16.sp, color = KimoColors.Secondary)
    }
}

// 标签选中态由细橙色下划线表达。
@Composable
private fun HomeTabs(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        HomeTab.entries.forEach { tab ->
            TextButton({ onSelect(tab) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tab.title, fontSize = 15.sp, fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (tab == selected) KimoColors.Ink else KimoColors.Secondary)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(27.dp).height(3.dp).clip(CircleShape)
                        .background(if (tab == selected) KimoColors.Orange else KimoColors.Paper))
                }
            }
        }
    }
}

// 精选卡只展示真实推荐歌曲，并以该歌曲建立播放队列。
@Composable
private fun FeaturedCard(track: Track, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1.54f).clip(androidx.compose.foundation.shape.RoundedCornerShape(KimoLayout.CardRadius))
        .clickable(onClick = onPlay)) {
        TrackArtwork(track, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            TrackTitle(track, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (track.displayArtist.isNotBlank()) Text(track.displayArtist, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        Icon(Icons.Rounded.PlayArrow, "播放${track.title}", Modifier.align(Alignment.BottomEnd).padding(18.dp).size(32.dp), tint = Color.White)
    }
}

// 双列卡片展示真实推荐歌单，完整曲库通过发现入口浏览。
@Composable
private fun InspirationSection(model: MusicViewModel, onDiscover: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("你的听觉灵感", onDiscover)
        val entries = model.online.playlists.state.items.take(2)
        if (entries.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            entries.forEach { entry ->
                CatalogCard(entry, Modifier.weight(1f).aspectRatio(1.02f)) { model.online.open(entry) }
            }
        } else FeedFooter(model.online.playlists, "暂时没有推荐歌单")
    }
}
