package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.AccountState
import com.kimo.music.core.CatalogEntry
import com.kimo.music.ui.theme.KimoColors

internal data class LibraryIdentity(val title: String, val subtitle: String, val action: String,
    val authenticated: Boolean, val avatar: String? = null) {
    companion object {
        // 只展示真实账号资料，缓存会话验证中也保留同一张账号卡的布局。
        fun from(account: AccountState, hasSession: Boolean): LibraryIdentity {
            val nickname = account.profile?.displayNickname?.takeIf(String::isNotBlank)
            val title = if (hasSession) nickname ?: "我的音乐空间" else "登录，找回你的收藏"
            val subtitle = when {
                account.restoring -> "正在恢复账号信息…"
                hasSession && account.error != null -> "账号需要重新验证，点击查看"
                hasSession -> "你的收藏与歌单，都在这里"
                else -> "同步我喜欢与云端歌单"
            }
            return LibraryIdentity(title, subtitle, if (hasSession) "查看账号" else "登录酷狗", hasSession, account.profile?.avatar)
        }
    }
}

// 登录前后共用相同的黑胶账号卡，只替换真实资料和按钮文案。
@Composable
internal fun LibraryIdentityCard(identity: LibraryIdentity, compact: Boolean = false, onAccount: () -> Unit) {
    val surface = KimoColors.Surface
    val glow = lerp(surface, KimoColors.Orange, if (KimoColors.IsDark) 0.17f else 0.12f)
    Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 80.dp else 132.dp).clip(RoundedCornerShape(22.dp))
        .background(Brush.horizontalGradient(listOf(surface, surface, glow))).testTag("library-identity-card")) {
        if (!compact) LibraryHeroWaveform(Modifier.align(Alignment.CenterEnd).offset(x = (-104).dp, y = 10.dp).size(48.dp, 24.dp))
        LibraryRecordIllustration(Modifier.align(Alignment.BottomEnd).offset(x = 24.dp, y = 22.dp).size(if (compact) 90.dp else 128.dp))
        LibraryIdentityCaption(identity, compact, onAccount, Modifier.fillMaxWidth(0.80f)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 4.dp else 10.dp))
    }
}

// 卡片左侧保留稳定的标题、说明和操作层级，大字号时允许标题自然换行。
@Composable
private fun LibraryIdentityCaption(identity: LibraryIdentity, compact: Boolean, onAccount: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)) {
        if (!compact) Text("让喜欢的音乐，有处安放", fontSize = 11.sp, lineHeight = 16.sp, color = KimoColors.Secondary)
        Text(identity.title, fontSize = if (compact) 18.sp else 20.sp, lineHeight = if (compact) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = KimoColors.Ink)
        if (!compact) Text(identity.subtitle, fontSize = 11.sp, lineHeight = 16.sp, color = KimoColors.Secondary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        LibraryIdentityButton(identity, onAccount)
    }
}

// 账号入口沿用现有登录与资料弹层，不在页面内创建第二份账号状态。
@Composable
private fun LibraryIdentityButton(identity: LibraryIdentity, onAccount: () -> Unit) {
    Button(onAccount, Modifier.heightIn(min = 44.dp).semantics { contentDescription = "账号信息" },
        shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = KimoColors.Orange, contentColor = KimoColors.OnDark)) {
        if (identity.authenticated) {
            AccountAvatar(identity.avatar, Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(identity.action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(9.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
    }
}

// 四个栏目共用一排等宽入口，账号登录前后保持顺序和触控区域一致。
@Composable
internal fun LibraryShortcuts(selected: LibraryTab, compact: Boolean = false, onSelect: (LibraryTab) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(vertical = if (compact) 2.dp else 4.dp).testTag("library-shortcuts"),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        LibraryTab.entries.forEach { tab -> LibraryShortcut(tab, tab == selected, compact, Modifier.weight(1f)) { onSelect(tab) } }
    }
}

// 爱心、歌单、手机和时钟分别表达四个栏目，选中态同时提供颜色和读屏语义。
@Composable
private fun LibraryShortcut(tab: LibraryTab, selected: Boolean, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (selected) KimoColors.Orange else KimoColors.Secondary
    val icon = when (tab) {
        LibraryTab.Favorites -> Icons.Filled.Favorite
        LibraryTab.Playlists -> Icons.AutoMirrored.Outlined.PlaylistPlay
        LibraryTab.Local -> Icons.Outlined.Smartphone
        LibraryTab.Recent -> Icons.Outlined.Schedule
    }
    Column(modifier.clip(RoundedCornerShape(15.dp)).selectable(selected, role = Role.Tab, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = tab.shortcutTitle }.padding(vertical = if (compact) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        Box(Modifier.size(if (compact) 32.dp else 44.dp).background(if (selected) tint.copy(alpha = 0.13f) else KimoColors.Surface, CircleShape),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(25.dp), tint = tint) }
        Text(tab.shortcutTitle, fontSize = if (compact) 11.sp else 13.sp, lineHeight = if (compact) 16.sp else 19.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) KimoColors.Ink else KimoColors.Secondary)
    }
}

// 歌单封面行原位展开，标题、数量和展开状态都来自当前真实歌单。
@Composable
internal fun LibraryPlaylistHeader(entry: CatalogEntry, expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onToggle)
        .semantics(mergeDescendants = true) {
            contentDescription = "歌单：${entry.title}"
            stateDescription = if (expanded) "已展开" else "已收起"
        }.padding(vertical = 10.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        CatalogArtwork(entry, Modifier.size(54.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${entry.count} 首歌曲", fontSize = 12.sp, color = KimoColors.Secondary)
        }
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(24.dp), tint = KimoColors.Secondary)
    }
}

// 空收藏提供明确发现入口，真实本机收藏仍正常显示，不伪造账号曲目或数量。
@Composable
internal fun LibraryCollectionEmpty(authenticated: Boolean, onDiscover: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).testTag("library-collection-empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LibraryCollectionIllustration(Modifier.size(102.dp, 74.dp))
        Text(if (authenticated) "还没有喜欢的歌曲" else "收藏，从一首歌开始", fontSize = 18.sp,
            lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(if (authenticated) "发现喜欢的歌，点亮爱心即可收藏" else "登录后，喜欢的歌会出现在这里",
            fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
        TextButton(onDiscover, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("去发现音乐", fontSize = 14.sp)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp))
        }
    }
}
