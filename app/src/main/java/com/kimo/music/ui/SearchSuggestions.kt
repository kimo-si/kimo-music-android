package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.CatalogEntry
import com.kimo.music.core.HotSearch
import com.kimo.music.core.SearchKind
import com.kimo.music.core.SearchSuggestion
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private const val SUGGESTION_DEBOUNCE_MS = 220L
private data class SuggestionBatch(val keyword: String = "", val items: List<SearchSuggestion> = emptyList())

// 输入停止后读取联想词；取消、切词或离开页面时旧结果不能覆盖当前内容。
@Composable
internal fun rememberKeywordSuggestions(query: String, editing: Boolean,
    fetch: suspend (String) -> List<SearchSuggestion>): List<SearchSuggestion> {
    val keyword = query.trim()
    val currentKeyword by rememberUpdatedState(keyword)
    val currentEditing by rememberUpdatedState(editing)
    val currentFetch by rememberUpdatedState(fetch)
    var batch by remember { mutableStateOf(SuggestionBatch()) }
    LaunchedEffect(keyword, editing) {
        batch = SuggestionBatch()
        if (!editing || keyword.isEmpty()) return@LaunchedEffect
        delay(SUGGESTION_DEBOUNCE_MS)
        try {
            // 调用真实联想接口，并在结果落地前再次检查取消状态与当前输入。
            val items = currentFetch(keyword)
            coroutineContext.ensureActive()
            if (currentEditing && currentKeyword == keyword) batch = SuggestionBatch(keyword, items)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 联想不可用时保留普通搜索和原页面。 */ }
    }
    return batch.items.takeIf { editing && batch.keyword == keyword }.orEmpty()
}

// 联想词浮在搜索内容上方，小屏和键盘打开时可独立滚动。
@Composable
internal fun KeywordSuggestionPanel(items: List<SearchSuggestion>, onSelect: (SearchSuggestion) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = SearchStyle.Inset).heightIn(max = 320.dp),
        shape = RoundedCornerShape(16.dp), color = KimoColors.Surface, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            items.forEach { suggestion -> KeywordSuggestionRow(suggestion) { onSelect(suggestion) } }
        }
    }
}

// 每条建议作为完整触控区域，并向辅助功能提供明确操作名称。
@Composable
private fun KeywordSuggestionRow(suggestion: SearchSuggestion, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSelect)
        .semantics { contentDescription = "搜索建议：${suggestion.keyword}" }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = KimoColors.Secondary)
        Text(suggestion.keyword, Modifier.weight(1f), fontSize = 14.sp, color = KimoColors.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = KimoColors.Secondary)
    }
}

// 最近搜索、真实推荐歌单和实时热搜分别由各自数据源提供。
@Composable
internal fun SearchSuggestions(model: MusicViewModel, navigation: SearchNavigation, modifier: Modifier,
    onPlaylist: (CatalogEntry) -> Unit) {
    val feed = model.online.hotSearches
    val hot = if (navigation.allHot) feed.state.items else feed.state.items.take(4)
    val playlists = model.online.playlists.state.items.take(3)
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = SearchStyle.Inset, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SearchHistory(model) { navigation.submit(model, it) } }
        item { Spacer(Modifier.height(SearchStyle.SectionGap)) }
        if (playlists.isNotEmpty()) {
            item { SearchDiscoveryCards(playlists, onPlaylist) }
            item { Spacer(Modifier.height(SearchStyle.SectionGap)) }
        }
        item { HotSearchHeading(navigation, feed.state.items.isNotEmpty()) }
        itemsIndexed(hot, key = { _, term -> term.keyword }, contentType = { _, _ -> "hot-search" }) { index, term ->
            HotSearchRow(term, index, model.online::hotSearchArtwork) { navigation.submit(model, term.keyword, SearchKind.Song) }
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

// 灵感图卡展示实际推荐歌单，点击进入对应真实目录。
@Composable
private fun SearchDiscoveryCards(entries: List<CatalogEntry>, onOpen: (CatalogEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("听点不一样", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEach { entry ->
                Column(Modifier.weight(1f).clickable { onOpen(entry) }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    CatalogArtwork(entry, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(entry.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

// 排名和文字来自热搜，图片独立补齐，不改变热词的点击搜索行为。
@Composable
private fun HotSearchRow(term: HotSearch, index: Int, artwork: suspend (HotSearch) -> String?, onSearch: () -> Unit) {
    val cover by produceState<String?>(null, term) { value = artwork(term) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onSearch)
        .semantics { contentDescription = "热搜：${term.keyword}" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(index + 1), Modifier.width(32.dp), fontSize = 15.sp,
            color = if (index == 0) KimoColors.Orange else KimoColors.Secondary)
        HotSearchArtwork(term, cover)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(term.keyword, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = term.reason.takeUnless { it == term.keyword }?.takeIf(String::isNotBlank)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = KimoColors.Secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.NorthEast, null, Modifier.size(22.dp), tint = KimoColors.Secondary)
    }
}

// 加载、断网与无匹配时保留音符占位；成功后由真实图片完整覆盖。
@Composable
private fun HotSearchArtwork(term: HotSearch, cover: String?) {
    var loaded by remember(cover) { mutableStateOf(false) }
    Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(KimoColors.Surface)
        .semantics {
            contentDescription = "热搜相关歌曲封面：${term.keyword}"
            stateDescription = if (loaded) "封面已加载" else "暂无封面"
        }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MusicNote, null, Modifier.size(22.dp), tint = KimoColors.Secondary)
        if (cover != null) AsyncImage(cover, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
