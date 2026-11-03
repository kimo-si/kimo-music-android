package com.kimo.music.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.data.Track
import com.kimo.music.data.online
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout

enum class Destination(val title: String) { Home("首页"), Discover("发现"), Library("我的") }
private enum class Overlay { Queue, Search, Volume, Notes, More, Quality, AddToPlaylist, Timer }

// 路由字段由 rememberSaveable 持有，页面编排通过一个对象传递。
private class AppNavigation(destinationState: MutableState<Destination>, expandedState: MutableState<Boolean>,
                            overlayState: MutableState<Overlay?>) {
    var destination by destinationState
    var expanded by expandedState
    var overlay by overlayState

    // 点歌后留在列表，完整播放页由底部播放条显式展开。
    fun play(model: MusicViewModel, track: Track, queue: List<Track>) { model.play(track, queue) }

    // 切换底部目的地时关闭旧详情，避免导航选中态与实际页面不一致。
    fun navigate(model: MusicViewModel, target: Destination) { model.online.closeDetail(); destination = target }

    // 统一打开与关闭弹层。
    fun show(value: Overlay) { overlay = value }

    // 消费当前弹层的关闭操作。
    fun dismiss() { overlay = null }
}

// 独立保存三个简单字段，旋转或进程重建后恢复可见页面。
@Composable
private fun rememberAppNavigation(): AppNavigation {
    val destination = rememberSaveable { mutableStateOf(Destination.Home) }
    val expanded = rememberSaveable { mutableStateOf(false) }
    val overlay = rememberSaveable { mutableStateOf<Overlay?>(null) }
    return remember { AppNavigation(destination, expanded, overlay) }
}

// 根页面只编排路由、系统文件选择器和弹层，不持有独立的播放状态。
@Composable
fun KimoApp(model: MusicViewModel) {
    val navigation = rememberAppNavigation()
    val motion = rememberPlayerMotion(navigation.expanded)
    val onImport = rememberAudioImporter(model)
    val onExport = rememberAudioExporter(model)
    val snackbar = remember { SnackbarHostState() }
    val selection = remember(model) { SongFlightState(model::beginSongSelection) }
    // 媒体会话失去当前歌曲时，关闭可能仍处于展开状态的播放页。
    LaunchedEffect(model.playback.track) {
        if (model.playback.track == null) {
            navigation.expanded = false
            if (navigation.overlay in setOf(Overlay.Notes, Overlay.More, Overlay.AddToPlaylist)) navigation.dismiss()
        }
    }
    BackHandler(motion.visible && navigation.overlay == null) { navigation.expanded = false }
    BackHandler(!motion.visible && model.online.detail != null && navigation.overlay == null) { model.online.closeDetail() }
    BackHandler(navigation.overlay == Overlay.Search) { navigation.dismiss() }
    ConsumeMessages(model, snackbar)
    CompositionLocalProvider(LocalSongFlight provides selection, LocalTrackMemberships provides model.online.memberships,
        LocalTrackActions provides { track -> model.trackActionsRequested = track }) {
        // 完整播放页打开时停止向不可见的迷你封面发送落点反馈。
        SideEffect { selection.setEnabled(!motion.visible && navigation.overlay != Overlay.Search) }
        KimoTipProvider(snackbar) {
            AppSurface(model, navigation, onImport, motion)
            AppOverlay(navigation.overlay, model, navigation::dismiss, navigation::show, onExport)
            FeatureOverlays(model)
            if (model.loginRequested) AccountDialog(model) { model.loginRequested = false }
        }
    }
}

// 播放页叠加在稳定的浏览页面上，进入和退出时不切换 Scaffold 或安全区域。
@Composable
private fun AppSurface(model: MusicViewModel, navigation: AppNavigation,
                       onImport: () -> Unit, motion: PlayerMotion) {
    Box(Modifier.fillMaxSize()) {
        AppScaffold(model, navigation, onImport, motion)
        if (navigation.overlay == Overlay.Search) SearchScreen(model, navigation::dismiss,
            { track, queue -> navigation.play(model, track, queue) }, {
                navigation.dismiss(); navigation.expanded = true
            })
        PlayerMotionHost(motion) { systemBarsActive ->
            PlayerScreen(model, { navigation.expanded = false }, { navigation.show(Overlay.Queue) },
                { navigation.show(Overlay.Notes) }, { navigation.show(Overlay.More) }, systemBarsActive)
        }
        SongFlightLayer()
    }
}

// 根外壳为内容、安全区域与固定播放条分配空间。
@Composable
private fun AppScaffold(model: MusicViewModel, navigation: AppNavigation,
                        onImport: () -> Unit, motion: PlayerMotion) {
    val searching = navigation.overlay == Overlay.Search
    Scaffold(modifier = Modifier.fillMaxSize().playerBrowseSurface(motion, searching), containerColor = KimoColors.Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            AppBottomBar(model, navigation.destination, { navigation.navigate(model, it) },
                { navigation.expanded = true }, { navigation.show(Overlay.Queue) }, motion::updateOrigin)
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { AppScreen(model, navigation, onImport, !motion.visible && !searching) }
    }
}

// 完整播放器、目录详情与主目的地按同一导航状态切换。
@Composable
private fun AppScreen(model: MusicViewModel, navigation: AppNavigation, onImport: () -> Unit, active: Boolean) {
    val pages = rememberSaveableStateHolder()
    val play: (Track, List<Track>) -> Unit = { track, queue -> navigation.play(model, track, queue) }
    val detail = model.online.detail
    when {
        detail != null -> CatalogDetailScreen(detail, model, model.online::closeDetail, play)
        // 返回详情前的页面时恢复栏目、输入与滚动位置。
        else -> pages.SaveableStateProvider(navigation.destination.name) {
            MainScreen(navigation.destination, model, onImport, play, { navigation.show(Overlay.Search) },
                { navigation.navigate(model, it) }, { navigation.show(Overlay.Queue) }, { navigation.show(Overlay.Quality) }, active)
        }
    }
}

// 系统选择器仅授予选中文档的权限，支持一次导入多个音频文件。
@Composable
private fun rememberAudioImporter(model: MusicViewModel): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::importAudio)
    return { launcher.launch(arrayOf("audio/*")) }
}

// 导出开始时固定歌曲对象，用户选择保存位置期间切歌不会导出错误曲目。
@Composable
private fun rememberAudioExporter(model: MusicViewModel): () -> Unit {
    var pending by remember { mutableStateOf<Track?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        val track = pending
        if (uri != null && track != null) model.exportAudio(track, uri)
        pending = null
    }
    return action@{
        val track = model.playback.track ?: return@action
        pending = track
        launcher.launch(if (track.online) "${track.title}.audio" else track.fileName)
    }
}

// 按一次性事件显示播放、导入和保存反馈。
@Composable
private fun ConsumeMessages(model: MusicViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(model.message) {
        val message = model.message ?: return@LaunchedEffect
        val action = model.noticeAction
        val result = snackbar.showSnackbar(message, actionLabel = action?.title, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed && action != null) model.handleNotice(action)
        model.clearMessage()
    }
}

// 三个底部目的地共用播放入口，返回首页不重建媒体服务。
@Composable
private fun MainScreen(destination: Destination, model: MusicViewModel, onImport: () -> Unit,
                       onPlay: (Track, List<Track>) -> Unit, onSearch: () -> Unit, onNavigate: (Destination) -> Unit,
                       onQueue: () -> Unit, onQuality: () -> Unit, active: Boolean) {
    when (destination) {
        Destination.Home -> HomeScreen(model, onSearch, { onNavigate(Destination.Discover) }, onPlay)
        Destination.Discover -> DiscoverScreen(model, onPlay)
        Destination.Library -> LibraryScreen(model, LibraryActions(onImport, onPlay, onSearch, onQueue, onQuality, { onNavigate(Destination.Discover) }), active)
    }
}

// 播放条在导航上方独占高度，系统手势区域由 navigationBarsPadding 处理。
@Composable
private fun AppBottomBar(model: MusicViewModel, selected: Destination, onNavigate: (Destination) -> Unit,
                         onExpand: () -> Unit, onQueue: () -> Unit, onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit) {
    Column(Modifier.navigationBarsPadding()) {
        MiniPlayer(model, onExpand, onQueue, showProgress = selected == Destination.Library, onBoundsChanged = onBoundsChanged)
        Row(Modifier.fillMaxWidth().height(KimoLayout.BottomNavigationHeight).testTag("bottom-navigation")) {
            Destination.entries.forEach { destination ->
                BottomNavigationItem(destination, selected == destination, Modifier.weight(1f)) { onNavigate(destination) }
            }
        }
    }
}

// 缩小图标与导航栏高度，同时保留整列可点击的无障碍触控区域。
@Composable
private fun BottomNavigationItem(destination: Destination, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) KimoColors.Orange else KimoColors.Secondary
    Column(modifier.fillMaxHeight().selectable(selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(when (destination) {
            Destination.Home -> if (selected) Icons.Rounded.Home else Icons.Outlined.Home
            Destination.Discover -> Icons.Outlined.Explore
            Destination.Library -> Icons.Outlined.PersonOutline
        }, null, Modifier.size(KimoLayout.BottomNavigationIconSize).testTag("bottom-nav-icon-${destination.name}"), tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(destination.title, color = tint, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
    }
}

// 弹层集中编排，队列选择沿用原始播放顺序。
@Composable
private fun AppOverlay(overlay: Overlay?, model: MusicViewModel, onDismiss: () -> Unit,
                       onChange: (Overlay) -> Unit, onExport: () -> Unit) {
    when (overlay) {
        Overlay.Queue -> QueueSheet(model, onDismiss)
        Overlay.Search -> Unit
        Overlay.Volume -> VolumeDialog(model, onDismiss)
        Overlay.Notes -> if (model.playback.track != null) LyricsSheet(model, onDismiss)
        Overlay.More -> PlayerOptionsDialog(model, onDismiss, PlayerMenuActions(
            quality = { onChange(Overlay.Quality) }, playlist = { onChange(Overlay.AddToPlaylist) },
            lyrics = { onChange(Overlay.Notes) }, volume = { onChange(Overlay.Volume) },
            download = { onDismiss(); onExport() }, timer = { onChange(Overlay.Timer) }))
        Overlay.Quality -> QualityDialog(model, onDismiss)
        Overlay.AddToPlaylist -> AddToPlaylistSheet(model, onDismiss)
        Overlay.Timer -> SleepTimerDialog(model, onDismiss)
        null -> Unit
    }
}
