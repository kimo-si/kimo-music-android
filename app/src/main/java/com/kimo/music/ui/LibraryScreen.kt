package com.kimo.music.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kimo.music.core.*
import com.kimo.music.data.Track
import com.kimo.music.data.online
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout

private enum class DiscoverTab(val title: String) { Playlists("歌单广场"), Charts("排行榜"), Artists("歌手"), Radio("电台") }

// 在线目录入口完整保留，不把未加载的栏目替换成静态样例。
@Composable
fun DiscoverScreen(model: MusicViewModel, onPlay: (Track, List<Track>) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(DiscoverTab.Playlists) }
    val feed = when (tab) {
        DiscoverTab.Playlists -> model.online.playlists
        DiscoverTab.Charts -> model.online.charts
        DiscoverTab.Artists -> model.online.artists
        DiscoverTab.Radio -> model.online.radios
    }
    LaunchedEffect(tab) { feed.ensureLoaded() }
    LazyColumn(contentPadding = PaddingValues(KimoLayout.PageInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("发现", "找到和此刻合拍的声音。") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiscoverTab.entries.forEach { item -> FilterChip(item == tab, { tab = item }, label = { Text(item.title) }) }
        } }
        item { TextButton({ feed.load(true) }) { Text("刷新${tab.title}") } }
        items(feed.state.items, key = CatalogEntry::id) { entry -> CatalogRow(entry, { model.online.open(entry) }) }
        item { FeedFooter(feed) }
    }
}

internal enum class LibraryTab(val title: String, val shortcutTitle: String) {
    Favorites("喜欢的歌曲", "我喜欢"), Playlists("我的歌单", "我的歌单"), Local("本地音乐", "本地音乐"), Recent("最近播放", "最近播放")
}
internal data class ExpandedLibraryPlaylist(val entry: CatalogEntry, val songs: PagedFeed<Track>)
private enum class LibraryOverlay { Settings, CreatePlaylist, TrackActions }
// 设置页需要跨配置恢复，歌曲操作仍只保留当前界面的临时选中对象。
private val SettingsOverlaySaver = Saver<MutableState<LibraryOverlay?>, Boolean>(
    save = { it.value == LibraryOverlay.Settings },
    restore = { mutableStateOf(if (it) LibraryOverlay.Settings else null) },
)
private object LibraryPalette {
    val PeachLight: Color @Composable get() = if (KimoColors.IsDark) Color(0xFF352D25) else Color(0xFFF8E2D2)
}
data class LibraryActions(val importAudio: () -> Unit, val play: (Track, List<Track>) -> Unit,
    val search: () -> Unit, val queue: () -> Unit, val quality: () -> Unit, val discover: () -> Unit)

private class LibraryNavigation(tabState: MutableState<LibraryTab>, overlayState: MutableState<LibraryOverlay?>,
                                trackState: MutableState<Track?>) {
    var tab by tabState
    var overlay by overlayState
    var selectedTrack by trackState

    // 行菜单始终保存用户选中的曲目，不随播放器切歌改变目标。
    fun showTrack(track: Track) { selectedTrack = track; overlay = LibraryOverlay.TrackActions }

    // 关闭弹层后释放临时选择，歌曲和账号状态继续由模型管理。
    fun dismiss() { overlay = null; selectedTrack = null }
}

// 栏目和设置在配置变化后恢复，歌曲操作只保留当前界面的临时状态。
@Composable
private fun rememberLibraryNavigation(): LibraryNavigation {
    val tab = rememberSaveable { mutableStateOf(LibraryTab.Favorites) }
    val overlay = rememberSaveable(saver = SettingsOverlaySaver) { mutableStateOf<LibraryOverlay?>(null) }
    val track = remember { mutableStateOf<Track?>(null) }
    return remember { LibraryNavigation(tab, overlay, track) }
}

// 账号卡和四个入口固定采用同一结构，选中栏目只替换下方真实内容。
@Composable
fun LibraryScreen(model: MusicViewModel, actions: LibraryActions, active: Boolean = true) {
    val navigation = rememberLibraryNavigation()
    val owner = model.account.credentials?.userId
    var expandedId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val entry = model.online.cloud.playlists.firstOrNull { it.id == expandedId }
    val expanded = rememberExpandedPlaylist(model, owner, entry)
    val tracks = if (navigation.tab == LibraryTab.Playlists) expanded?.songs?.state?.items.orEmpty() else navigation.tab.selectedTracks(model)
    val pages = rememberSaveableStateHolder()
    BackHandler(active && navigation.tab != LibraryTab.Favorites) { navigation.tab = LibraryTab.Favorites }
    pages.SaveableStateProvider(navigation.tab.name) {
        LibraryPageContent(model, navigation, actions, tracks, expanded) { expandedId = if (expandedId == it.id) null else it.id }
    }
    LibraryOverlays(navigation, model, actions, tracks)
}

// 展开状态按账号和目录隔离，收起或切换账号时取消迟到的分页请求。
@Composable
private fun rememberExpandedPlaylist(model: MusicViewModel, owner: String?, entry: CatalogEntry?): ExpandedLibraryPlaylist? {
    val expanded = remember(model.online, owner, entry) { entry?.let { ExpandedLibraryPlaylist(it, model.online.playlistSongs(it)) } }
    DisposableEffect(expanded) { onDispose { expanded?.songs?.reset() } }
    LaunchedEffect(expanded) { expanded?.songs?.ensureLoaded() }
    return expanded
}

// 工具栏、账号卡、四个入口和控制区都在滚动容器外，只有内容列表接收滚动。
@Composable
private fun LibraryPageContent(model: MusicViewModel, navigation: LibraryNavigation, actions: LibraryActions,
    tracks: List<Track>, expanded: ExpandedLibraryPlaylist?, onExpand: (CatalogEntry) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 500.dp && maxWidth > 600.dp
        Column(Modifier.fillMaxSize().padding(horizontal = KimoLayout.PageInset).padding(top = if (compact) 4.dp else 6.dp)) {
            LibraryFixedHeader(model, navigation, actions, tracks, compact)
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("library-content-list"),
                contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (navigation.tab == LibraryTab.Playlists) libraryPlaylistItems(model, navigation, actions, expanded, onExpand)
                else librarySongItems(model, navigation, actions, tracks)
            }
        }
    }
}

// 横屏将账号卡与入口并排，固定上方内容的同时为下方列表保留空间。
@Composable
private fun LibraryFixedHeader(model: MusicViewModel, navigation: LibraryNavigation, actions: LibraryActions,
    tracks: List<Track>, compact: Boolean) {
    val identity = LibraryIdentity.from(model.account.state, model.account.credentials != null)
    Column(Modifier.testTag("library-fixed-header")) {
        LibraryHeader(actions.search, { model.downloadsRequested = true }) { navigation.overlay = LibraryOverlay.Settings }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { LibraryIdentityCard(identity, true) { model.loginRequested = true } }
            Box(Modifier.weight(1f)) { LibraryShortcuts(navigation.tab, true) { navigation.tab = it } }
        } else {
            LibraryIdentityCard(identity) { model.loginRequested = true }
            Spacer(Modifier.height(4.dp))
            LibraryShortcuts(navigation.tab) { navigation.tab = it }
        }
        LibraryFixedControls(model, navigation, actions, tracks, compact)
    }
}

// 标题与导入、播放全部等控制按钮固定展示，不随歌曲滚动消失。
@Composable
private fun LibraryFixedControls(model: MusicViewModel, navigation: LibraryNavigation, actions: LibraryActions,
    tracks: List<Track>, compact: Boolean) {
    val showActions = navigation.tab != LibraryTab.Favorites || tracks.isNotEmpty()
    if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { LibrarySectionHeader(navigation, model, tracks.size) }
        if (showActions) Box(Modifier.weight(1f)) { LibrarySectionActions(navigation, model, actions, tracks, compact = true) }
    } else {
        LibrarySectionHeader(navigation, model, tracks.size)
        if (showActions) LibrarySectionActions(navigation, model, actions, tracks)
    }
}

// 空收藏展示发现入口，其他栏目继续保留导入、播放全部及当前队列操作。
private fun LazyListScope.librarySongItems(model: MusicViewModel, navigation: LibraryNavigation,
    actions: LibraryActions, tracks: List<Track>) {
    if (navigation.tab == LibraryTab.Favorites && tracks.isEmpty()) {
        item(key = "library-favorites-empty") { LibraryOverviewEmpty(model, actions.discover) }
        return
    }
    items(tracks, key = Track::id, contentType = { "library-song" }) { track ->
        LibrarySongRow(track, { actions.play(track, tracks) }, { navigation.showTrack(track) })
    }
    item { LibraryStatus(navigation.tab, model, tracks.isEmpty()) }
}

// 歌单列表和展开曲目共用一张惰性列表，长歌单分页不会一次创建数百行视图。
private fun LazyListScope.libraryPlaylistItems(model: MusicViewModel, navigation: LibraryNavigation,
    actions: LibraryActions, expanded: ExpandedLibraryPlaylist?, onExpand: (CatalogEntry) -> Unit) {
    if (model.account.credentials == null) {
        item { LibraryPlaylistSignIn(model) }
        return
    }
    libraryPlaylistRows(model.online.cloud.playlists, expanded, model.online.loadingAll,
        LibraryPlaylistActions(onExpand, actions.play, navigation::showTrack) { entry ->
            model.online.playAll(entry, { songs -> songs.firstOrNull()?.let { actions.play(it, songs) } }, model::notice)
        })
    item { LibraryPlaylistStatus(model) }
}

internal data class LibraryPlaylistActions(val toggle: (CatalogEntry) -> Unit, val play: (Track, List<Track>) -> Unit,
    val more: (Track) -> Unit, val playAll: (CatalogEntry) -> Unit)

// 一张惰性列表展开歌单和已加载的曲目，收起后保留下一张歌单的原始顺序。
internal fun LazyListScope.libraryPlaylistRows(entries: List<CatalogEntry>, expanded: ExpandedLibraryPlaylist?,
    loadingAll: Boolean, actions: LibraryPlaylistActions) {
    entries.forEach { entry ->
        item(key = "playlist-${entry.id}") { LibraryPlaylistHeader(entry, expanded?.entry?.id == entry.id) { actions.toggle(entry) } }
        if (expanded?.entry?.id == entry.id) expandedPlaylistItems(expanded, loadingAll, actions)
    }
}

// 点击歌曲仍等待原有点歌动画完成，整单播放交由完整分页读取入口。
private fun LazyListScope.expandedPlaylistItems(expanded: ExpandedLibraryPlaylist, loadingAll: Boolean, actions: LibraryPlaylistActions) {
    item(key = "playlist-play-${expanded.entry.id}") {
        TextButton({ actions.playAll(expanded.entry) }, enabled = !loadingAll) { Text(if (loadingAll) "正在读取歌单…" else "播放整个歌单") }
    }
    items(expanded.songs.state.items, key = { "playlist-${expanded.entry.id}-song-${it.id}" }, contentType = { "library-song" }) { track ->
        LibrarySongRow(track, { actions.play(track, expanded.songs.state.items) }, { actions.more(track) })
    }
    item(key = "playlist-footer-${expanded.entry.id}") { FeedFooter(expanded.songs, "这个歌单暂时没有歌曲") }
}

// 未登录时在歌单区域说明需要授权，四个栏目仍可自由切换。
@Composable
private fun LibraryPlaylistSignIn(model: MusicViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(38.dp), tint = KimoColors.Secondary)
        Text("登录后查看你的歌单", fontSize = 15.sp, color = KimoColors.Secondary)
        TextButton({ model.loginRequested = true }) { Text("登录酷狗") }
    }
}

// 云端列表的加载、失败与真实空态分别呈现，失败时允许原位重试。
@Composable
private fun LibraryPlaylistStatus(model: MusicViewModel) {
    val cloud = model.online.cloud
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            cloud.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            cloud.error != null || !cloud.loaded -> {
                Text(cloud.error ?: "暂时未能读取歌单", fontSize = 13.sp, color = KimoColors.Secondary)
                TextButton({ requestLibrarySync(model) }) { Text("重新同步") }
            }
            cloud.playlists.isEmpty() -> Text("还没有歌单，可以新建一个。", color = KimoColors.Secondary)
        }
    }
}

// 正在同步或读取失败时展示真实状态，不把未加载的收藏误画成空库。
@Composable
private fun LibraryOverviewEmpty(model: MusicViewModel, onDiscover: () -> Unit) {
    val authenticated = model.account.credentials != null
    val cloud = model.online.cloud
    when {
        authenticated && (cloud.loading || model.account.state.restoring) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("正在找回你的音乐…", fontSize = 13.sp, color = KimoColors.Secondary)
            }
        authenticated && (cloud.error != null || !cloud.loaded) -> LibraryCollectionRetry(model)
        else -> LibraryCollectionEmpty(authenticated, onDiscover)
    }
}

// 失败重试仍调用账号原有同步链路，不清空可用缓存或建立临时收藏。
@Composable
private fun LibraryCollectionRetry(model: MusicViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(model.online.cloud.error ?: model.account.state.error ?: "暂时未能读取收藏", fontSize = 13.sp, color = KimoColors.Secondary)
        TextButton({ requestLibrarySync(model) }) { Text("重新同步") }
    }
}

// 收藏集合包含完整云收藏及本机收藏，同一音频只在列表中显示一次。
private fun LibraryTab.selectedTracks(model: MusicViewModel): List<Track> = when (this) {
    LibraryTab.Favorites -> model.favoriteTracks
    LibraryTab.Local -> model.localTracks + model.downloadedTracks
    LibraryTab.Recent -> model.recentTracks
    else -> emptyList()
}.distinctBy(Track::id)

// 页面标题和右侧操作与参考图对齐，搜索与设置均有独立触控区域。
@Composable
private fun LibraryHeader(onSearch: () -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("我的音乐", Modifier.weight(1f), fontSize = 29.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
        ActionIcon("搜索音乐", Icons.Outlined.Search, onClick = onSearch)
        ActionIcon("下载管理", Icons.Outlined.FileDownload, onClick = onDownloads)
        ActionIcon("音乐设置", Icons.Outlined.Settings, onClick = onSettings)
    }
}

// 同步按钮只发送现有同步请求，未登录时引导授权，加载期间避免重复请求。
@Composable
private fun LibrarySyncButton(model: MusicViewModel) {
    val loading = model.online.cloud.loading
    TextButton({ requestLibrarySync(model) }, Modifier.height(36.dp), enabled = !loading,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Sync, null, Modifier.size(21.dp), tint = KimoColors.Secondary)
        Spacer(Modifier.width(5.dp))
        Text(if (loading) "同步中" else "同步", fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
    }
}

// 账号验证和完整分页同步沿用原有逻辑，不把本机数据上传为云收藏。
private fun requestLibrarySync(model: MusicViewModel) {
    if (model.account.credentials == null) model.loginRequested = true else model.online.syncAccount()
}

// 下方标题始终对应选中的栏目，收藏同步与真实数量保留原有数据来源。
@Composable
private fun LibrarySectionHeader(navigation: LibraryNavigation, model: MusicViewModel, trackCount: Int) {
    val playlists = navigation.tab == LibraryTab.Playlists
    val countKnown = if (playlists || navigation.tab == LibraryTab.Favorites)
        model.account.credentials == null || model.online.cloud.loaded else true
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(navigation.tab.title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            if (countKnown && (!playlists || model.account.credentials != null)) Text(
                if (playlists) "${model.online.cloud.playlists.size} 个" else "$trackCount 首",
                Modifier.padding(start = 10.dp), fontSize = 12.sp, color = KimoColors.Secondary)
            Spacer(Modifier.weight(1f))
            if (navigation.tab == LibraryTab.Favorites) {
                if (model.account.credentials != null) LibrarySyncButton(model)
                else TextButton({ model.loginRequested = true }) { Text("登录后同步", fontSize = 12.sp, color = KimoColors.Secondary) }
            }
        }
        HorizontalDivider(color = KimoColors.Rail.copy(alpha = 0.55f), thickness = 0.5.dp)
    }
}

// 播放全部、导入与新建分别使用现有能力，不生成影子歌单或演示收藏。
@Composable
private fun LibrarySectionActions(navigation: LibraryNavigation, model: MusicViewModel,
                                  actions: LibraryActions, tracks: List<Track>, compact: Boolean = false) {
    if (compact && navigation.tab == LibraryTab.Local) { LibraryCompactLocalActions(model, actions, tracks); return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (navigation.tab == LibraryTab.Local) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(actions.importAudio) { Text("导入本地音频") }
            TextButton({ model.localLibraryRequested = true }) { Text("管理曲库") }
        }
        if (navigation.tab == LibraryTab.Playlists) {
            if (model.account.credentials != null) OutlinedButton({
                if (model.account.state.verified) navigation.overlay = LibraryOverlay.CreatePlaylist else model.loginRequested = true
            }) { Icon(Icons.Outlined.Add, null); Text("新建云歌单") }
        } else LibraryPlayAll(tracks, actions)
    }
}

// 横屏本地操作压成一行，保留导入、管理和播放队列，同时不挤占列表高度。
@Composable
private fun LibraryCompactLocalActions(model: MusicViewModel, actions: LibraryActions, tracks: List<Track>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(actions.importAudio, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("导入") }
        TextButton({ model.localLibraryRequested = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("管理") }
        Box(Modifier.weight(1f)) { LibraryPlayAll(tracks, actions) }
    }
}

// 播放全部按当前完整集合建立队列，右侧入口展示现有媒体会话队列。
@Composable
private fun LibraryPlayAll(tracks: List<Track>, actions: LibraryActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 44.dp).clickable(enabled = tracks.isNotEmpty(), role = Role.Button) {
            tracks.firstOrNull()?.let { actions.play(it, tracks) }
        }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(28.dp).background(KimoColors.Orange.copy(
                alpha = if (tracks.isEmpty()) 0.35f else 1f), CircleShape).padding(5.dp), tint = Color.White)
            Text("播放全部", Modifier.padding(start = 11.dp), fontSize = 14.sp,
                color = if (tracks.isEmpty()) KimoColors.Secondary else KimoColors.Ink)
        }
        ActionIcon("查看当前播放队列", Icons.AutoMirrored.Outlined.QueueMusic, tint = KimoColors.Secondary, onClick = actions.queue)
    }
}

// 曲目行使用真实封面和单行元数据，更多按钮只针对该行歌曲。
@Composable
private fun LibrarySongRow(track: Track, onPlay: () -> Unit, onMore: () -> Unit) {
    val selection = rememberSongSelection(track, onPlay)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = selection.play)
                .semantics { contentDescription = "播放歌曲：${track.title}" }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track, Modifier.size(52.dp).onGloballyPositioned(selection.anchor::update))
                Column(Modifier.weight(1f).padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    TrackTitle(track)
                    val subtitle = listOf(track.displayArtist, track.displayAlbum).filter(String::isNotBlank).joinToString(" · ")
                    if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 11.sp, lineHeight = 16.sp,
                        color = KimoColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ActionIcon("歌曲操作：${track.title}", Icons.Outlined.MoreHoriz, tint = KimoColors.Secondary, onClick = onMore)
        }
        HorizontalDivider(Modifier.padding(start = 65.dp), thickness = 0.5.dp, color = KimoColors.Rail.copy(alpha = 0.5f))
    }
}

// 错误及缓存状态与正常空库分别展示，失败不清空已有曲目。
@Composable
private fun LibraryStatus(tab: LibraryTab, model: MusicViewModel, empty: Boolean) {
    val cloud = tab in listOf(LibraryTab.Favorites, LibraryTab.Playlists)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cloud) model.online.cloud.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (cloud) model.account.state.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (cloud && model.account.credentials == null) Text("登录酷狗后可查看收藏与歌单。", fontSize = 12.sp, color = KimoColors.Secondary)
        else if (empty && !model.online.cloud.loading && tab != LibraryTab.Playlists &&
            (!cloud || model.online.cloud.loaded)) Text("这里暂时没有歌曲。", color = KimoColors.Secondary)
    }
}

// 弹层只负责编排现有功能，播放与同步状态仍由共享模型持有。
@Composable
private fun LibraryOverlays(navigation: LibraryNavigation, model: MusicViewModel,
                            actions: LibraryActions, tracks: List<Track>) {
    when (navigation.overlay) {
        LibraryOverlay.Settings -> LibrarySettingsDialog(navigation, model, actions)
        LibraryOverlay.CreatePlaylist -> PlaylistNameDialog("新建云歌单", navigation::dismiss) { name ->
            model.online.changePlaylist(null, name, onResult = model::notice); navigation.dismiss()
        }
        LibraryOverlay.TrackActions -> navigation.selectedTrack?.let { track ->
            LibraryTrackSheet(track, navigation.tab, model, navigation::dismiss) { actions.play(track, tracks) }
        }
        null -> Unit
    }
}

// 设置作为独立页面展示，关闭后保留个人页原有栏目与滚动位置。
@Composable
private fun LibrarySettingsDialog(navigation: LibraryNavigation, model: MusicViewModel, actions: LibraryActions) {
    SettingsScreen(model, navigation::dismiss, actions)
}

// 底部操作面板展示真实封面和来源，继续调用原有播放、入队与收藏能力。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTrackSheet(track: Track, tab: LibraryTab, model: MusicViewModel, onDismiss: () -> Unit, onPlay: () -> Unit) {
    val liked = track.id in model.favorites
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = KimoColors.Paper,
        scrimColor = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        KimoTipWindow()
        ThemedSheetNavigationBar()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            LibrarySheetTrackHeader(track, if (tab == LibraryTab.Favorites) "我喜欢" else tab.title)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), thickness = 0.5.dp, color = KimoColors.Rail)
            LibrarySheetAction("播放这首歌", { onDismiss(); onPlay() }, highlighted = true) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(34.dp).background(KimoColors.Orange, CircleShape).padding(7.dp), tint = Color.White)
            }
            LibrarySheetAction("加入播放列表", { model.enqueue(track); onDismiss() }) {
                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, Modifier.size(28.dp), tint = KimoColors.Ink)
            }
            LibrarySheetAction("下一首播放", { model.playNext(track); onDismiss() }) {
                Icon(Icons.Outlined.SkipNext, null, Modifier.size(28.dp), tint = KimoColors.Ink)
            }
            if (track.online) LibrarySheetAction("下载音频", { model.download(track); onDismiss() }) {
                Icon(Icons.Outlined.Download, null, Modifier.size(28.dp), tint = KimoColors.Ink)
            }
            LibrarySheetAction(if (liked) "取消喜欢" else "加入我喜欢", { model.toggleFavorite(track); onDismiss() }) {
                LibraryFavoriteIcon(liked)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(15.dp)).background(KimoColors.Surface)
                .clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
                Text("取消", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// 弹层跟随当前主题更新系统栏，独立页面还同步状态栏图标。
@Suppress("DEPRECATION")
@Composable
internal fun ThemedSheetNavigationBar(includeStatusBar: Boolean = false) {
    val view = LocalView.current
    val paper = KimoColors.Paper.toArgb()
    val dark = KimoColors.IsDark
    DisposableEffect(view, paper, dark, includeStatusBar) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val color = window?.navigationBarColor
        val contrast = if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val appearance = controller?.isAppearanceLightNavigationBars
        val status = controller?.isAppearanceLightStatusBars
        window?.navigationBarColor = paper
        if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        controller?.isAppearanceLightNavigationBars = !dark
        if (includeStatusBar) controller?.isAppearanceLightStatusBars = !dark
        onDispose {
            if (color != null) window?.navigationBarColor = color
            if (Build.VERSION.SDK_INT >= 29 && contrast != null) window?.isNavigationBarContrastEnforced = contrast
            if (appearance != null) controller?.isAppearanceLightNavigationBars = appearance
            if (includeStatusBar && status != null) controller?.isAppearanceLightStatusBars = status
        }
    }
}

// 歌曲标题、来源与封面来自当前选中行，不依赖正在播放的歌曲。
@Composable
private fun LibrarySheetTrackHeader(track: Track, source: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(track, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TrackTitle(track, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("来自「$source」", color = KimoColors.Secondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// 操作行统一触控区域，首行使用浅桃色强调播放。
@Composable
private fun LibrarySheetAction(label: String, onClick: () -> Unit, highlighted: Boolean = false, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
        .background(if (highlighted) LibraryPalette.PeachLight else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// 已喜欢显示带减号的橙色爱心，未喜欢显示空心爱心。
@Composable
private fun LibraryFavoriteIcon(liked: Boolean) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, Modifier.size(27.dp), tint = KimoColors.Orange)
        if (liked) Icon(Icons.Filled.RemoveCircle, null, Modifier.align(Alignment.BottomEnd).size(14.dp)
            .background(KimoColors.Paper, CircleShape), tint = KimoColors.Orange)
    }
}

// 发现页沿用原有标题层级，与个人收藏的布局相互独立。
@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = KimoColors.Secondary, fontSize = 14.sp)
    }
}
