package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kimo.music.core.HotSearch
import com.kimo.music.core.SearchKind
import com.kimo.music.data.ListeningMood
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors

private data class SearchDiscovery(val title: String, val keyword: String, val mood: ListeningMood)
private val SearchDiscoveries = listOf(
    SearchDiscovery("日落漫游", "日落", ListeningMood.Sunset),
    SearchDiscovery("安静独处", "独处", ListeningMood.Slow),
    SearchDiscovery("山野来信", "山野", ListeningMood.Forest),
)

// 最近搜索、三列灵感图卡和实时热搜按参考图顺序排列。
@Composable
internal fun SearchSuggestions(model: MusicViewModel, navigation: SearchNavigation, modifier: Modifier) {
    val feed = model.online.hotSearches
    val hot = if (navigation.allHot) feed.state.items else feed.state.items.take(4)
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = SearchStyle.Inset, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SearchHistory(model) { navigation.submit(model, it) } }
        item { Spacer(Modifier.height(SearchStyle.SectionGap)) }
        item { SearchDiscoveryCards { navigation.submit(model, it, SearchKind.Playlist) } }
        item { Spacer(Modifier.height(SearchStyle.SectionGap)) }
        item { HotSearchHeading(navigation, feed.state.items.isNotEmpty()) }
        itemsIndexed(hot, key = { _, term -> term.keyword }, contentType = { _, _ -> "hot-search" }) { index, term ->
            HotSearchRow(term, index) { navigation.submit(model, term.keyword, SearchKind.Song) }
        }
        item { FeedFooter(feed, "暂时没有热门搜索") }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// 搜索历史只展示真实记录，垃圾桶仅清除本机搜索关键词。
@Composable
private fun SearchHistory(model: MusicViewModel, onSearch: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近搜索", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            ActionIcon("清空最近搜索", Icons.Outlined.DeleteOutline, tint = KimoColors.Secondary,
                enabled = model.searchHistory.isNotEmpty(), onClick = model::clearSearchHistory)
        }
        if (model.searchHistory.isEmpty()) Text("暂无搜索记录", Modifier.padding(vertical = 8.dp), fontSize = 13.sp, color = KimoColors.Secondary)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.searchHistory.forEach { keyword ->
                Text(keyword, Modifier.clip(CircleShape).background(KimoColors.Surface).clickable { onSearch(keyword) }
                    .padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// 灵感图卡复用已有原创图片，每个入口实际搜索对应主题的歌单。
@Composable
private fun SearchDiscoveryCards(onSearch: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("听点不一样", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchDiscoveries.forEach { item ->
                Column(Modifier.weight(1f).clickable { onSearch(item.keyword) }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    AsyncImage(item.mood.artwork, null, Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp)).background(KimoColors.Surface), contentScale = ContentScale.Crop)
                    Text(item.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// 查看全部保留服务端完整热搜顺序，再次点击可回到首屏四项。
@Composable
private fun HotSearchHeading(navigation: SearchNavigation, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.TrendingUp, null, Modifier.size(24.dp), tint = KimoColors.Orange)
        Text("热门搜索", Modifier.weight(1f).padding(start = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        TextButton({ navigation.allHot = !navigation.allHot }, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (navigation.allHot) "收起" else "查看全部", fontSize = 13.sp, color = KimoColors.Secondary)
            Icon(if (navigation.allHot) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight, null,
                Modifier.size(18.dp), tint = KimoColors.Secondary)
        }
    }
}

// 排名和关键词来自实时热搜，封面与歌手来自对应歌曲的真实搜索结果。
@Composable
private fun HotSearchRow(term: HotSearch, index: Int, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onSearch)
        .semantics { contentDescription = "热搜：${term.keyword}" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(index + 1), Modifier.width(32.dp), fontSize = 15.sp,
            color = if (index == 0) KimoColors.Orange else KimoColors.Secondary)
        if (term.track != null) TrackArtwork(term.track, Modifier.size(50.dp))
        else Box(Modifier.size(50.dp).background(KimoColors.Surface, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MusicNote, null, Modifier.size(22.dp), tint = KimoColors.Secondary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(term.keyword, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(term.track?.artist ?: term.reason.takeUnless { it == term.keyword }.orEmpty().ifBlank { "热门搜索" },
                fontSize = 12.sp, color = KimoColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.NorthEast, null, Modifier.size(22.dp), tint = KimoColors.Secondary)
    }
}
