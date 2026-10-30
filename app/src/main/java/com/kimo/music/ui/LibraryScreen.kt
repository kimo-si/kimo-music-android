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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.kimo.music.R
import com.kimo.music.core.*
import com.kimo.music.data.DemoCatalog
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

private enum class LibraryTab(val title: String) {
    Favorites("喜欢的歌曲"), Playlists("云歌单"), Local("本地音乐"), Recent("最近播放"), Demo("离线演示")
}
private enum class LibraryOverlay { Settings, CreatePlaylist, TrackActions }
private object LibraryPalette {
    val Peach = Color(0xFFF0D1BA)
    val PeachLight = Color(0xFFF8E2D2)
    val Cloud = Color(0xFFF8E3D5)
    val Drive = Color(0xFFEAE7D8)
    val Local = Color(0xFFE4E8D9)
    val Recent = Color(0xFFEFDFD3)
}
data class LibraryActions(val importAudio: () -> Unit, val play: (Track, List<Track>) -> Unit,
    val search: () -> Unit, val queue: () -> Unit, val quality: () -> Unit)

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

// 栏目可在旋转与返回页面后恢复，弹层只保留当前界面的临时状态。
@Composable
private fun rememberLibraryNavigation(): LibraryNavigation {
    val tab = rememberSaveable { mutableStateOf(LibraryTab.Favorites) }
    val overlay = remember { mutableStateOf<LibraryOverlay?>(null) }
    val track = remember { mutableStateOf<Track?>(null) }
    return remember { LibraryNavigation(tab, overlay, track) }
}

// 我的音乐按账号、收藏主卡、四个入口和歌曲列表编排，所有数据来自现有模型。
@Composable
fun LibraryScreen(model: MusicViewModel, actions: LibraryActions, active: Boolean = true) {
    val navigation = rememberLibraryNavigation()
    val tracks = navigation.tab.selectedTracks(model)
    val favorites = LibraryTab.Favorites.selectedTracks(model)
    BackHandler(active && navigation.tab != LibraryTab.Favorites) { navigation.tab = LibraryTab.Favorites }
    LazyColumn(contentPadding = PaddingValues(horizontal = KimoLayout.PageInset, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        libraryHeaderItems(model, navigation, actions, favorites)
        item { LibrarySectionHeader(navigation, model, tracks.size) }
        item { LibrarySectionActions(navigation, model, actions, tracks) }
        libraryContentItems(model, navigation, actions, tracks)
        item { LibraryStatus(navigation.tab, model, tracks.isEmpty()) }
    }
    LibraryOverlays(navigation, model, actions, tracks)
}

// 账号区与收藏卡独立于当前栏目，随时可回到完整收藏列表。
private fun LazyListScope.libraryHeaderItems(model: MusicViewModel, navigation: LibraryNavigation,
                                             actions: LibraryActions, favorites: List<Track>) {
    item { LibraryHeader(actions.search) { navigation.overlay = LibraryOverlay.Settings } }
    item { AccountSummary(model) }
    item { FavoritesCard(favorites, { navigation.tab = LibraryTab.Favorites }) {
        favorites.firstOrNull()?.let { actions.play(it, favorites) }
    } }
    item { LibraryShortcuts(navigation.tab, { navigation.tab = it }) { model.notice("音乐云盘暂未开放") } }
}

// 目录与曲目分别渲染，已加载的云歌单不会被占位入口覆盖。
private fun LazyListScope.libraryContentItems(model: MusicViewModel, navigation: LibraryNavigation,
                                              actions: LibraryActions, tracks: List<Track>) {
    if (navigation.tab == LibraryTab.Playlists) items(model.online.cloud.playlists, key = CatalogEntry::id) { entry ->
        CatalogRow(entry, { model.online.open(entry) })
    } else items(tracks, key = Track::id, contentType = { "library-song" }) { track ->
        LibrarySongRow(track, { actions.play(track, tracks) }, { navigation.showTrack(track) })
    }
}

// 收藏集合包含完整云收藏及本机收藏，同一音频只在列表中显示一次。
private fun LibraryTab.selectedTracks(model: MusicViewModel): List<Track> = when (this) {
    LibraryTab.Favorites -> model.favoriteTracks
    LibraryTab.Local -> model.localTracks
    LibraryTab.Recent -> model.recentTracks
    LibraryTab.Demo -> DemoCatalog.tracks
    else -> emptyList()
}.distinctBy(Track::id)

// 页面标题和右侧操作与参考图对齐，搜索与设置均有独立触控区域。
@Composable
private fun LibraryHeader(onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("我的音乐", Modifier.weight(1f), fontSize = 29.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
        ActionIcon("搜索音乐", Icons.Outlined.Search, onClick = onSearch)
        ActionIcon("音乐设置", Icons.Outlined.Settings, onClick = onSettings)
    }
}

// 头像与昵称读取当前账号，未登录时直接进入登录流程，不使用参考图中的虚构用户。
@Composable
private fun AccountSummary(model: MusicViewModel) {
    val profile = model.account.state.profile
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(role = Role.Button) { model.loginRequested = true }
            .semantics { contentDescription = "账号与同步" }, verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(profile?.avatar, null, Modifier.size(48.dp).clip(CircleShape),
                placeholder = painterResource(R.drawable.sunset_coast), error = painterResource(R.drawable.sunset_coast),
                contentScale = ContentScale.Crop)
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile?.nickname ?: "登录酷狗", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("账号与同步", fontSize = 12.sp, color = KimoColors.Secondary)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = KimoColors.Secondary)
                }
            }
        }
        LibrarySyncButton(model, prominent = true)
    }
}

// 同步按钮只发送现有同步请求，未登录时引导授权，加载期间避免重复请求。
@Composable
private fun LibrarySyncButton(model: MusicViewModel, prominent: Boolean = false) {
    val loading = model.online.cloud.loading
    TextButton({ requestLibrarySync(model) }, Modifier.height(36.dp), enabled = !loading,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(if (prominent) Icons.Outlined.CloudSync else Icons.Outlined.Sync, null, Modifier.size(21.dp),
            tint = if (prominent) KimoColors.Orange else KimoColors.Secondary)
        Spacer(Modifier.width(5.dp))
        Text(if (loading) "同步中" else if (prominent) "立即同步" else "同步", fontSize = 12.sp, lineHeight = 18.sp,
            color = if (prominent) KimoColors.Orange else KimoColors.Secondary)
    }
}

// 账号验证和完整分页同步沿用原有逻辑，不把本机数据上传为云收藏。
private fun requestLibrarySync(model: MusicViewModel) {
    if (model.account.credentials == null) model.loginRequested = true else model.online.syncAccount()
}

// 桃色收藏主卡以真实数量和首曲封面呈现，播放入口与查看收藏分开处理。
@Composable
private fun FavoritesCard(tracks: List<Track>, onOpen: () -> Unit, onPlay: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp))
        .background(Brush.linearGradient(listOf(LibraryPalette.PeachLight, LibraryPalette.Peach)))
        .clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = "查看我喜欢" }) {
        val artworkWidth = if (maxWidth < 340.dp) 124.dp else 144.dp
        Row(Modifier.fillMaxWidth().heightIn(min = 124.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FavoriteCaption(tracks.size, Modifier.weight(1f))
            Box(Modifier.width(artworkWidth).height(100.dp)) {
                CollectionRecordArtwork(tracks.firstOrNull(), Modifier.fillMaxSize().padding(end = 12.dp))
                IconButton(onPlay, Modifier.align(Alignment.BottomEnd).size(42.dp)
                    .background(KimoColors.Orange, CircleShape), enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, "播放我喜欢", Modifier.size(27.dp), tint = Color.White)
                }
            }
        }
    }
}

// 收藏文案只描述当前集合，空库仍保留真实零计数。
@Composable
private fun FavoriteCaption(count: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Outlined.Favorite, null, Modifier.size(27.dp).background(KimoColors.Orange.copy(alpha = 0.1f),
                RoundedCornerShape(7.dp)).padding(5.dp), tint = KimoColors.Orange)
            Text("我喜欢", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count 首珍藏", fontSize = 13.sp, lineHeight = 19.sp, color = KimoColors.Ink.copy(alpha = 0.7f))
        Text("把喜欢的旋律，留在这里", fontSize = 11.sp, lineHeight = 17.sp, color = KimoColors.Secondary, maxLines = 2)
    }
}

// 四个入口共用对齐与配色，音乐云盘按需求仅保留占位反馈，不发起接口请求。
@Composable
private fun LibraryShortcuts(selected: LibraryTab, onSelect: (LibraryTab) -> Unit, onCloud: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        LibraryShortcut("云歌单", Icons.Outlined.CloudQueue, LibraryPalette.Cloud, selected == LibraryTab.Playlists,
            Modifier.weight(1f)) { onSelect(LibraryTab.Playlists) }
        LibraryShortcut("音乐云盘", Icons.Outlined.CloudUpload, LibraryPalette.Drive, false, Modifier.weight(1f), onCloud)
        LibraryShortcut("本地音乐", Icons.Outlined.Smartphone, LibraryPalette.Local, selected == LibraryTab.Local,
            Modifier.weight(1f)) { onSelect(LibraryTab.Local) }
        LibraryShortcut("最近播放", Icons.Outlined.Schedule, LibraryPalette.Recent, selected == LibraryTab.Recent,
            Modifier.weight(1f)) { onSelect(LibraryTab.Recent) }
    }
}

// 快捷入口具备完整按钮语义，文字放大时可自然换行而不挤压图标。
@Composable
private fun LibraryShortcut(title: String, icon: ImageVector, background: Color, selected: Boolean,
                            modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = title }.padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(44.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = if (selected) KimoColors.Orange else KimoColors.Ink)
        }
        Text(title, fontSize = 12.sp, lineHeight = 17.sp, color = if (selected) KimoColors.Orange else KimoColors.Ink)
    }
}

// 分区标题保留实时数量，非收藏栏目提供明确返回入口。
@Composable
private fun LibrarySectionHeader(navigation: LibraryNavigation, model: MusicViewModel, trackCount: Int) {
    val playlists = navigation.tab == LibraryTab.Playlists
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (navigation.tab != LibraryTab.Favorites) ActionIcon("返回喜欢的歌曲", Icons.AutoMirrored.Outlined.ArrowBack) {
            navigation.tab = LibraryTab.Favorites
        }
        Text(navigation.tab.title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(if (playlists) "${model.online.cloud.playlists.size} 个" else "$trackCount 首",
            Modifier.weight(1f).padding(start = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
        if (navigation.tab in listOf(LibraryTab.Favorites, LibraryTab.Playlists)) LibrarySyncButton(model)
    }
}

// 播放全部、导入与新建分别使用现有能力，不生成影子歌单或演示收藏。
@Composable
private fun LibrarySectionActions(navigation: LibraryNavigation, model: MusicViewModel,
                                  actions: LibraryActions, tracks: List<Track>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (navigation.tab == LibraryTab.Local) OutlinedButton(actions.importAudio) { Text("导入本地音频") }
        if (navigation.tab == LibraryTab.Playlists) OutlinedButton({
            if (model.account.state.verified) navigation.overlay = LibraryOverlay.CreatePlaylist else model.loginRequested = true
        }) { Icon(Icons.Outlined.Add, null); Text("新建云歌单") }
        else LibraryPlayAll(tracks, actions)
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
        ActionIcon("查看当前播放队列", Icons.Outlined.QueueMusic, tint = KimoColors.Secondary, onClick = actions.queue)
    }
}

// 曲目行使用真实封面和单行元数据，更多按钮只针对该行歌曲。
@Composable
private fun LibrarySongRow(track: Track, onPlay: () -> Unit, onMore: () -> Unit) {
    val selection = rememberSongSelection(onPlay)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = selection.play)
                .semantics { contentDescription = "播放歌曲：${track.title}" }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track, Modifier.size(52.dp).onGloballyPositioned(selection.anchor::update))
                Column(Modifier.weight(1f).padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(track.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${track.artist} · ${track.album}", fontSize = 11.sp, lineHeight = 16.sp,
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
        if (cloud && model.online.cloud.loading) CircularProgressIndicator(Modifier.size(22.dp))
        if (cloud && model.online.cloud.fromCache) Text("显示上次同步的曲库，联网后可重新同步。", fontSize = 12.sp, color = KimoColors.Secondary)
        if (cloud) model.online.cloud.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (cloud) model.account.state.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (cloud && model.account.credentials == null) Text("登录后同步酷狗收藏与歌单，本地收藏也会保留。", fontSize = 12.sp, color = KimoColors.Secondary)
        else if (empty && !model.online.cloud.loading && tab != LibraryTab.Playlists) Text("这里暂时没有歌曲。", color = KimoColors.Secondary)
        if (tab == LibraryTab.Demo) Text("3 首原创合成演示曲，仅用于离线体验。", fontSize = 12.sp, color = KimoColors.Secondary)
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

// 设置入口连接账号、音质、本地导入和既有离线演示，避免新增无效按钮。
@Composable
private fun LibrarySettingsDialog(navigation: LibraryNavigation, model: MusicViewModel, actions: LibraryActions) {
    AlertDialog(onDismissRequest = navigation::dismiss, title = { Text("音乐设置") }, text = {
        Column {
            TextButton({ navigation.dismiss(); model.loginRequested = true }) { Text("账号与同步") }
            TextButton({ navigation.dismiss(); actions.quality() }) { Text("在线音质 · ${model.quality.title}") }
            TextButton({ navigation.dismiss(); actions.importAudio() }) { Text("导入本地音频") }
            TextButton({ navigation.dismiss(); navigation.tab = LibraryTab.Demo }) { Text("离线演示") }
        }
    }, confirmButton = { TextButton(navigation::dismiss) { Text("完成") } })
}

// 底部操作面板展示真实封面和来源，继续调用原有播放、入队与收藏能力。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTrackSheet(track: Track, tab: LibraryTab, model: MusicViewModel, onDismiss: () -> Unit, onPlay: () -> Unit) {
    val liked = track.id in model.favorites
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = KimoColors.Paper,
        scrimColor = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        LibrarySheetNavigationBar()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            LibrarySheetTrackHeader(track, if (tab == LibraryTab.Favorites) "我喜欢" else tab.title)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), thickness = 0.5.dp, color = KimoColors.Rail)
            LibrarySheetAction("播放这首歌", { onDismiss(); onPlay() }, highlighted = true) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(34.dp).background(KimoColors.Orange, CircleShape).padding(7.dp), tint = Color.White)
            }
            LibrarySheetAction("加入播放列表", { model.enqueue(track); onDismiss() }) {
                Icon(Icons.Outlined.PlaylistAdd, null, Modifier.size(28.dp), tint = KimoColors.Ink)
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

// 面板的系统导航区域沿用暖米白，关闭后恢复原窗口设置。
@Suppress("DEPRECATION")
@Composable
private fun LibrarySheetNavigationBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val color = window?.navigationBarColor
        val contrast = if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val appearance = controller?.isAppearanceLightNavigationBars
        window?.navigationBarColor = KimoColors.Paper.toArgb()
        if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            if (color != null) window?.navigationBarColor = color
            if (Build.VERSION.SDK_INT >= 29 && contrast != null) window?.isNavigationBarContrastEnforced = contrast
            if (appearance != null) controller?.isAppearanceLightNavigationBars = appearance
        }
    }
}

// 歌曲标题、来源与封面来自当前选中行，不依赖正在播放的歌曲。
@Composable
private fun LibrarySheetTrackHeader(track: Track, source: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TrackArtwork(track, Modifier.size(64.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(track.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
