package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.*
import com.kimo.music.data.Track
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors

internal object SearchStyle {
    val Inset = 12.dp
    val SectionGap = 26.dp
    val Kinds = listOf(SearchKind.Song, SearchKind.Artist, SearchKind.Album, SearchKind.Playlist)
}

internal class SearchNavigation(queryState: MutableState<String>, submittedState: MutableState<String>,
    kindState: MutableState<SearchKind>, hotState: MutableState<Boolean>) {
    var query by queryState
    var submitted by submittedState
    var kind by kindState
    var allHot by hotState

    // 实际提交时保存历史并请求真实曲库，分类切换也沿用同一入口。
    fun submit(model: MusicViewModel, keyword: String = query, type: SearchKind = kind) {
        val word = keyword.trim().takeIf(String::isNotBlank) ?: return
        query = word; submitted = word; kind = type
        // 记录已提交的搜索词，而不是用户尚未完成的输入。
        model.rememberSearch(word)
        // 请求对应分类，分页和取消交由现有在线曲库管理。
        model.online.search(word, type)
    }

    // 清空输入后回到最近搜索和热门推荐。
    fun clear() { query = ""; submitted = "" }
}

internal data class SearchActions(val close: () -> Unit, val play: (Track, List<Track>) -> Unit,
    val openPlayer: () -> Unit, val queue: () -> Unit)

// 页面字段在旋转后恢复，实际搜索历史由本机曲库偏好持久化。
@Composable
private fun rememberSearchNavigation(): SearchNavigation {
    val query = rememberSaveable { mutableStateOf("") }
    val submitted = rememberSaveable { mutableStateOf("") }
    val kind = rememberSaveable { mutableStateOf(SearchKind.Song) }
    val hot = rememberSaveable { mutableStateOf(false) }
    return remember { SearchNavigation(query, submitted, kind, hot) }
}

// 独立搜索页面保留自己的音符落点，点歌后仍停留在搜索结果中。
@Composable
fun SearchScreen(model: MusicViewModel, onClose: () -> Unit, onPlay: (Track, List<Track>) -> Unit,
                 onOpenPlayer: () -> Unit) {
    val navigation = rememberSearchNavigation()
    val feedback = remember { SongFlightState() }
    var queueVisible by rememberSaveable { mutableStateOf(false) }
    val actions = SearchActions(onClose, onPlay, onOpenPlayer) { queueVisible = true }
    SideEffect { feedback.setEnabled(navigation.submitted.isNotBlank()) }
    LaunchedEffect(Unit) { model.online.hotSearches.ensureLoaded() }
    CompositionLocalProvider(LocalSongFlight provides feedback) {
        Box(Modifier.fillMaxSize().background(KimoColors.Paper)) {
            SearchPageBody(model, navigation, actions)
            SongFlightLayer()
            if (queueVisible) QueueSheet(model) { queueVisible = false }
        }
    }
}

// 顶部标题和输入框固定，下方推荐或结果独立滚动。
@Composable
private fun SearchPageBody(model: MusicViewModel, navigation: SearchNavigation, actions: SearchActions) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        SearchHeader(actions.close)
        SearchQueryBar(model, navigation)
        if (navigation.submitted.isBlank()) SearchSuggestions(model, navigation, Modifier.weight(1f))
        else {
            SearchKinds(model, navigation)
            SearchResultFeed(model, navigation, actions, Modifier.weight(1f))
            MiniPlayer(model, actions.openPlayer, actions.queue)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// 标题采用参考图的返回箭头、大字和暖米白背景。
@Composable
private fun SearchHeader(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SearchStyle.Inset).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ActionIcon("返回上一页", Icons.Outlined.ChevronLeft, onClick = onClose)
        Text("搜索", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
    }
}

// 圆角搜索框与右侧橙色按钮沿参考图排列，键盘搜索和按钮执行同一操作。
@Composable
private fun SearchQueryBar(model: MusicViewModel, navigation: SearchNavigation) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit: () -> Unit = {
        if (navigation.query.isBlank()) focus.requestFocus()
        else { navigation.submit(model); keyboard?.hide() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = SearchStyle.Inset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchInput(navigation, focus, submit, Modifier.weight(1f))
        TextButton(submit, Modifier.semantics { contentDescription = "搜索酷狗曲库" }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("搜索", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = KimoColors.Orange)
        }
    }
}

// 输入框保留真实文本输入和清空功能，不在输入时自动向远端提交未完成内容。
@Composable
private fun SearchInput(navigation: SearchNavigation, focus: FocusRequester, submit: () -> Unit, modifier: Modifier) {
    Row(modifier.heightIn(min = 50.dp).background(KimoColors.Surface, CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = KimoColors.Secondary)
        BasicTextField(navigation.query, { navigation.query = it }, singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "搜索关键词" },
            textStyle = TextStyle(color = KimoColors.Ink, fontSize = 15.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(KimoColors.Orange), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }), decorationBox = { field ->
                Box { if (navigation.query.isEmpty()) Text("歌曲、歌手、专辑、歌单", fontSize = 14.sp, color = KimoColors.Secondary); field() }
            })
        if (navigation.query.isNotEmpty()) IconButton(navigation::clear, Modifier.size(24.dp)) {
            Icon(Icons.Outlined.Close, "清空搜索词", Modifier.size(17.dp), tint = KimoColors.Secondary)
        }
    }
}

// 搜索后仍完整保留歌曲、歌手、专辑和歌单四类入口。
@Composable
private fun SearchKinds(model: MusicViewModel, navigation: SearchNavigation) {
    Row(Modifier.fillMaxWidth().padding(horizontal = SearchStyle.Inset), horizontalArrangement = Arrangement.SpaceBetween) {
        SearchStyle.Kinds.forEach { kind ->
            TextButton({ navigation.submit(model, type = kind) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(kind.title, fontSize = 15.sp, fontWeight = if (kind == navigation.kind) FontWeight.Bold else FontWeight.Normal,
                        color = if (kind == navigation.kind) KimoColors.Ink else KimoColors.Secondary)
                    Box(Modifier.size(width = 24.dp, height = 3.dp).background(
                        if (kind == navigation.kind) KimoColors.Orange else KimoColors.Paper, CircleShape))
                }
            }
        }
    }
}

// 结果使用真实分页和可播放队列，目录结果继续进入原有详情页。
@Composable
private fun SearchResultFeed(model: MusicViewModel, navigation: SearchNavigation, actions: SearchActions, modifier: Modifier) {
    val result = model.online.search
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = SearchStyle.Inset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.online.searchLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        model.online.searchError?.let { error -> item { SearchFailure(error) { navigation.submit(model) } } }
        items(result.tracks, key = { "song:${it.id}" }, contentType = { "song" }) { track ->
            TrackRow(track, { actions.play(track, result.tracks) }, { model.enqueue(track) })
        }
        items(result.entries, key = { "entry:${it.id}" }, contentType = { "catalog" }) { entry ->
            CatalogRow(entry, { model.online.open(entry); actions.close() })
        }
        if (!model.online.searchLoading && model.online.searchError == null && result.tracks.isEmpty() && result.entries.isEmpty())
            item { Text("没有找到相关结果，试试其他关键词。", color = KimoColors.Secondary, fontSize = 14.sp) }
        if (result.nextPage != null) item {
            TextButton({ model.online.search(navigation.submitted, navigation.kind, true) }, enabled = !model.online.searchLoading) { Text("加载更多结果") }
        }
    }
}

// 请求失败保留查询词，明确提供重试。
@Composable
private fun SearchFailure(message: String, retry: () -> Unit) {
    Column {
        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        TextButton(retry) { Text("重试搜索") }
    }
}
