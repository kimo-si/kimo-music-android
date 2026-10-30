package com.kimo.music.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.kimo.music.data.online
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoLayout
import androidx.compose.ui.res.painterResource

internal object PlayerPalette {
    val Background = Color(0xFF211D19)
    val Foreground = Color(0xFFFFF2E2)
    val Secondary = Foreground.copy(alpha = 0.64f)
    val Accent = Color(0xFFFF7D33)
    val Rail = Foreground.copy(alpha = 0.28f)
    val Inset = 26.dp
}
private data class PlayerActions(val close: () -> Unit, val queue: () -> Unit,
    val notes: () -> Unit, val more: () -> Unit)

// 按参考图以封面铺底，文字与控制直接叠在深色渐变上，使用安卓真实安全区域。
@Composable
fun PlayerScreen(model: MusicViewModel, onClose: () -> Unit, onQueue: () -> Unit,
                 onNotes: () -> Unit, onMore: () -> Unit, systemBarsActive: Boolean = true) {
    val actions = PlayerActions(onClose, onQueue, onNotes, onMore)
    if (systemBarsActive) PlayerSystemBars()
    BoxWithConstraints(Modifier.fillMaxSize().background(PlayerPalette.Background)) {
        PlayerBackdrop(model)
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = PlayerPalette.Inset)) {
            PlayerHeader(model, actions)
            if (landscape) PlayerLandscape(model, actions) else PlayerPortrait(model, actions)
        }
    }
}

// 底图随真实歌曲变化，顶部与底部遮罩分别保障状态栏和文字的可读性。
@Composable
private fun BoxWithConstraintsScope.PlayerBackdrop(model: MusicViewModel) {
    val track = model.playback.track
    val bundledArtwork = !track.online && !track.local
    AsyncImage(track.coverUrl ?: track.artwork.takeIf { bundledArtwork }, "背景封面：${track.title}", Modifier.fillMaxSize(),
        placeholder = if (bundledArtwork) painterResource(track.artwork) else null,
        error = if (bundledArtwork) painterResource(track.artwork) else null,
        contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
    PlayerBackdropScrim(maxHeight < 700.dp || LocalDensity.current.fontScale > 1.2f, maxWidth > maxHeight)
}

// 短屏与大字号的文字位置更靠上，提前加深遮罩；横屏只加强控制区域的对比度。
@Composable
private fun PlayerBackdropScrim(compact: Boolean, landscape: Boolean) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to PlayerPalette.Background.copy(alpha = 0.72f),
        0.14f to PlayerPalette.Background.copy(alpha = 0.34f),
        (if (compact) 0.22f else 0.26f) to Color.Transparent,
        (if (compact) 0.28f else 0.44f) to PlayerPalette.Background.copy(alpha = 0.08f),
        (if (compact) 0.40f else 0.58f) to PlayerPalette.Background.copy(alpha = 0.68f),
        0.76f to PlayerPalette.Background.copy(alpha = 0.94f),
        1f to PlayerPalette.Background)))
    if (landscape) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
        0.35f to Color.Transparent, 0.45f to PlayerPalette.Background.copy(alpha = 0.65f),
        1f to PlayerPalette.Background.copy(alpha = 0.86f))))
}

// 进入沉浸播放页使用浅色系统图标，离开后恢复个人页的深色图标。
@Composable
private fun PlayerSystemBars() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val status = controller?.isAppearanceLightStatusBars
        val navigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            status?.let { controller?.isAppearanceLightStatusBars = it }
            navigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

// Compose 的主题包装不一定直接提供 Activity，通过上下文链安全获取窗口。
private fun Context.findActivity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }
    .filterIsInstance<Activity>().firstOrNull()

// 顶部对齐参考图的白色标题、疏排专辑名与两侧图标。
@Composable
private fun PlayerHeader(model: MusicViewModel, actions: PlayerActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon("收起播放页", Icons.Outlined.KeyboardArrowDown, tint = PlayerPalette.Foreground, onClick = actions.close)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("正在播放", color = PlayerPalette.Foreground, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(model.playback.track.album.ifBlank { model.playback.track.mood.title }, color = PlayerPalette.Secondary,
                fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ActionIcon("更多播放选项", Icons.Outlined.MoreHoriz, tint = PlayerPalette.Foreground, onClick = actions.more)
    }
}

// 歌曲信息和歌词整体上移指定像素，原有歌词区域继续按屏幕空间适配。
@Composable
private fun ColumnScope.PlayerPortrait(model: MusicViewModel, actions: PlayerActions) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val compact = maxHeight < 220.dp
        val contentLift = with(LocalDensity.current) { KimoLayout.PlayerContentLiftPx.toDp() }
        Column(Modifier.fillMaxSize().offset(y = -contentLift)) {
            Spacer(Modifier.weight(3f))
            PlayerMetadata(model, compact)
            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
            PlayerLyricsRegion(model, actions.notes, Modifier.weight(1f))
        }
    }
    PlayerBottomControls(model, actions)
}

// 可用高度决定真实歌词行数，最多显示五行，短屏与大字号自动减少行数。
@Composable
private fun PlayerLyricsRegion(model: MusicViewModel, onExpand: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val lineHeight = 28.dp * LocalDensity.current.fontScale
        val visibleLines = ((maxHeight - 12.dp) / lineHeight).toInt().coerceIn(1, 5)
        PlayerLyricPreview(model, onExpand, visibleLines = visibleLines)
    }
}

// 横屏保留左侧照片，右侧信息可滚动，底部按钮仍在可见安全区域内。
@Composable
private fun ColumnScope.PlayerLandscape(model: MusicViewModel, actions: PlayerActions) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Spacer(Modifier.weight(0.8f))
        Column(Modifier.weight(1.2f).fillMaxHeight()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Bottom) {
                PlayerMetadata(model, compact = true)
                PlayerLyricPreview(model, actions.notes, compact = true)
            }
            PlayerBottomControls(model, actions, compact = true)
        }
    }
}

// 曲名和歌手左对齐，橙色收藏直接置于右侧，不增加圆形底或额外卡片。
@Composable
private fun PlayerMetadata(model: MusicViewModel, compact: Boolean = false) {
    val track = model.playback.track
    val liked = track.id in model.favorites
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(track.title, color = PlayerPalette.Foreground, fontSize = if (compact) 24.sp else 28.sp,
                lineHeight = if (compact) 30.sp else 38.sp, fontWeight = FontWeight.Bold,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = PlayerPalette.Secondary, fontSize = 14.sp, lineHeight = 22.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ActionIcon(if (liked) "取消喜欢" else "加入我喜欢",
            if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            tint = if (liked) PlayerPalette.Accent else PlayerPalette.Foreground) { model.toggleFavorite(track) }
    }
}

// 歌词按真实进度居中，离线纯音乐显示原有手记而不伪造同步人声歌词。
@Composable
private fun PlayerLyricPreview(model: MusicViewModel, onExpand: () -> Unit, compact: Boolean = false, visibleLines: Int = 2) {
    if (model.playback.track.online || model.playback.track.local) SyncedLyricPreview(model, onExpand, compact,
        LyricPreviewColors(PlayerPalette.Foreground, PlayerPalette.Secondary), showChevron = false, visibleLines = visibleLines)
    else TextButton(onExpand, Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("让晚风，替我慢慢说。", color = PlayerPalette.Foreground, fontSize = 16.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact && visibleLines > 1) Text("把今天留在日落以后。", color = PlayerPalette.Secondary, fontSize = 14.sp,
                lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// 进度与控制整体上移，横屏同时预留空间，避免遮挡上方歌词。
@Composable
private fun PlayerBottomControls(model: MusicViewModel, actions: PlayerActions, compact: Boolean = false) {
    val groupLift = with(LocalDensity.current) { KimoLayout.PlayerBottomGroupLiftPx.toDp() }
    val placement = if (compact) Modifier.padding(bottom = groupLift) else Modifier.offset(y = -groupLift)
    Column(Modifier.fillMaxWidth().then(placement)) {
        PlayerSeek(model)
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        PlayerTransport(model, actions.queue, compact)
    }
}

// 同一控制行整体上移，缩小的播放键与左右图标共用垂直中心线。
@Composable
private fun PlayerTransport(model: MusicViewModel, onQueue: () -> Unit, compact: Boolean) {
    val state = model.playback
    val controlsLift = with(LocalDensity.current) { KimoLayout.PlayerControlsLiftPx.toDp() }
    val rowHeight = if (compact) KimoLayout.CompactPlayerControlsHeight else KimoLayout.PlayerControlsHeight
    val buttonSize = if (compact) KimoLayout.CompactPlayerButtonSize else KimoLayout.PlayerButtonSize
    Row(Modifier.fillMaxWidth().height(rowHeight).offset(y = -controlsLift),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ActionIcon("随机播放", Icons.Outlined.Shuffle,
            tint = if (state.shuffle) PlayerPalette.Accent else PlayerPalette.Secondary, onClick = model::toggleShuffle)
        ActionIcon("上一首", Icons.Rounded.SkipPrevious, tint = PlayerPalette.Foreground) { model.skip(false) }
        IconButton(model::togglePlaying, Modifier.size(buttonSize)
            .background(PlayerPalette.Foreground, CircleShape), enabled = state.ready) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) "暂停" else "播放", Modifier.size(KimoLayout.PlayerButtonIconSize), tint = PlayerPalette.Background)
        }
        ActionIcon("下一首", Icons.Rounded.SkipNext, tint = PlayerPalette.Foreground) { model.skip(true) }
        ActionIcon("播放列表", Icons.Outlined.QueueMusic, tint = PlayerPalette.Secondary, onClick = onQueue)
    }
}
