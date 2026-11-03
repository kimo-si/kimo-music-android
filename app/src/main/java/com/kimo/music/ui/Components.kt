package com.kimo.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.kimo.music.data.Track
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout
import coil3.compose.AsyncImage
import com.kimo.music.core.MembershipStatus
import com.kimo.music.core.TrackMemberships

internal val LocalTrackMemberships = staticCompositionLocalOf<TrackMemberships?> { null }

enum class TrackRowAction(val label: String, val icon: ImageVector) {
    Enqueue("加入播放列表", Icons.Outlined.AddCircleOutline),
    Like("加入我喜欢", Icons.Outlined.FavoriteBorder),
    Unlike("取消喜欢", Icons.Outlined.Favorite),
}

// 装饰性封面由父级歌曲标题描述，避免读屏重复朗读图片名称。
@Composable
fun TrackArtwork(track: Track, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(KimoColors.Surface), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MusicNote, null, Modifier.fillMaxSize(0.3f), tint = KimoColors.Secondary)
        if (track.coverUrl != null) AsyncImage(track.coverUrl, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

// 账号没有真实头像时只显示中性图标，不使用其他照片冒充用户头像。
@Composable
fun AccountAvatar(avatar: String?, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(KimoColors.Surface), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.PersonOutline, null, Modifier.fillMaxSize(0.62f), tint = KimoColors.Secondary)
        if (!avatar.isNullOrBlank()) AsyncImage(avatar, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
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
    val selection = rememberSongSelection(track, onPlay)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = selection.play)
            .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(track, Modifier.size(54.dp).onGloballyPositioned(selection.anchor::update))
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TrackTitle(track, color = if (active) KimoColors.Orange else KimoColors.Ink)
                val subtitle = listOf(track.displayArtist, track.displayAlbum).filter(String::isNotBlank).joinToString(" · ")
                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = KimoColors.Secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ActionIcon("${action.label}：${track.title}", action.icon,
            tint = if (action == TrackRowAction.Unlike) KimoColors.Orange else KimoColors.Ink, onClick = onAdd)
    }
}

// 歌曲标题和会员标识共用一行，长标题优先省略，确保标识始终可见。
@Composable
internal fun TrackTitle(track: Track, modifier: Modifier = Modifier, color: Color = KimoColors.Ink,
    fontSize: TextUnit = 16.sp, fontWeight: FontWeight = FontWeight.Medium,
    maxLines: Int = 1, lineHeight: TextUnit = TextUnit.Unspecified) {
    val memberships = LocalTrackMemberships.current
    val status = memberships?.status(track) ?: MembershipStatus.Unknown
    // 可见标题才触发权益核实，同一歌曲的不同入口共用批处理和缓存。
    LaunchedEffect(memberships, track.hash, track.albumId) { memberships?.request(track) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(track.title, Modifier.weight(1f, fill = false), fontSize = fontSize, fontWeight = fontWeight,
            color = color, maxLines = maxLines, lineHeight = lineHeight, overflow = TextOverflow.Ellipsis)
        if (status == MembershipStatus.Vip) VipBadge(track)
    }
}

// 只为服务端明确标注的在线会员歌曲显示 VIP，本地音频与缺失权益信息不添加标识。
@Composable
private fun VipBadge(track: Track) {
    val accent = KimoColors.Orange
    val shape = RoundedCornerShape(4.dp)
    Text("VIP", Modifier.clip(shape).background(accent.copy(alpha = 0.1f))
        .border(0.75.dp, accent.copy(alpha = 0.7f), shape).padding(horizontal = 4.dp, vertical = 1.dp)
        .testTag("vip-badge-${track.id}")
        .semantics { contentDescription = "VIP歌曲：${track.title}" }, color = accent,
        fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
}

// 收藏卡只在存在歌曲时展示其真实封面，空收藏使用中性图标。
@Composable
fun CollectionRecordArtwork(track: Track?, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (track != null) VinylArtwork(Modifier.align(Alignment.CenterEnd).size(96.dp))
        val cover = Modifier.align(Alignment.CenterStart).size(88.dp)
            .shadow(6.dp, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp))
        if (track != null) TrackArtwork(track, cover)
        else Box(cover.background(KimoColors.Surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MusicNote, null, Modifier.size(30.dp), tint = KimoColors.Secondary)
        }
    }
}

// 黑胶纹理由矢量绘制，随着屏幕密度保持清晰，不引入额外位图资源。
@Composable
private fun VinylArtwork(modifier: Modifier) {
    val accent = KimoColors.Orange
    val paper = KimoColors.Paper
    Canvas(modifier) {
        val radius = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(Color(0xFF484741), Color(0xFF151612), Color(0xFF34352F))))
        for (ring in 5..14) drawCircle(Color.White.copy(alpha = 0.055f), radius * ring / 15f,
            style = Stroke(width = radius * 0.01f))
        drawCircle(accent, radius * 0.34f)
        drawCircle(paper, radius * 0.045f)
    }
}

// 设置和播放操作共用底部面板，内容过多时仍可在小屏幕上滚动。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KimoActionSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = KimoColors.Paper,
        scrimColor = Color.Black.copy(alpha = 0.58f), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        KimoTipWindow()
        ThemedSheetNavigationBar()
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("action-sheet-content").verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            KimoSheetHeader(title, subtitle, onDismiss)
            content()
        }
    }
}

// 标题、说明和关闭按钮统一排列，关闭按钮保留完整触控区域。
@Composable
private fun KimoSheetHeader(title: String, subtitle: String?, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = KimoColors.Ink)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, lineHeight = 19.sp, color = KimoColors.Secondary)
        }
        ActionIcon("关闭$title", Icons.Outlined.Close, tint = KimoColors.Secondary, onClick = onDismiss)
    }
}

// 选项使用整行触控，并以文字和图标分别表达当前值及选中状态。
@Composable
internal fun KimoSheetOption(title: String, icon: ImageVector, onClick: () -> Unit,
    detail: String? = null, selected: Boolean = false, showChevron: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(if (selected) KimoColors.Orange.copy(alpha = 0.12f) else KimoColors.Surface,
            RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (selected) KimoColors.Orange else KimoColors.Ink)
        }
        Text(title, Modifier.weight(1f).padding(start = 14.dp), fontSize = 15.sp,
            fontWeight = FontWeight.Medium, color = KimoColors.Ink)
        if (detail != null) Text(detail, fontSize = 13.sp, color = KimoColors.Secondary)
        if (selected) Icon(Icons.Outlined.Check, "已选择", Modifier.padding(start = 8.dp).size(22.dp), tint = KimoColors.Orange)
        else if (showChevron) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(22.dp), tint = KimoColors.Secondary)
    }
}
