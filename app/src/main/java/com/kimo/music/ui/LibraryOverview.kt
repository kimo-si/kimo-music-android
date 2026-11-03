package com.kimo.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.AccountState
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
internal fun LibraryIdentityCard(identity: LibraryIdentity, onAccount: () -> Unit) {
    val surface = KimoColors.Surface
    val glow = lerp(surface, KimoColors.Orange, if (KimoColors.IsDark) 0.17f else 0.12f)
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(24.dp))
        .background(Brush.horizontalGradient(listOf(surface, surface, glow))).testTag("library-identity-card")) {
        LibraryHeroWaveform(Modifier.align(Alignment.CenterEnd).offset(x = (-126).dp, y = 12.dp).size(58.dp, 30.dp))
        LibraryRecordIllustration(Modifier.align(Alignment.BottomEnd).offset(x = 32.dp, y = 25.dp).size(155.dp))
        LibraryIdentityCaption(identity, onAccount, Modifier.fillMaxWidth(0.80f).padding(18.dp))
    }
}

// 卡片左侧保留稳定的标题、说明和操作层级，大字号时允许标题自然换行。
@Composable
private fun LibraryIdentityCaption(identity: LibraryIdentity, onAccount: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("让喜欢的音乐，有处安放", fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
        Text(identity.title, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, color = KimoColors.Ink)
        Text(identity.subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary,
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

// 离线入口在登录前后都可操作，不把本地功能误放进账号授权流程。
@Composable
internal fun LibraryOfflineAccess(onLocal: () -> Unit, onDownloads: () -> Unit, onRecent: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("离线也能听", fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LibraryOfflineTile("本地音乐", "导入设备里的音乐", Icons.Outlined.Smartphone, Modifier.weight(1f), onLocal)
            LibraryOfflineTile("已下载", "随时离线播放", Icons.Outlined.FileDownload, Modifier.weight(1f), onDownloads)
        }
        LibraryRecentAccess(onRecent)
    }
}

// 双列入口共用间距和图标规格，小屏或大字号时文案允许换行。
@Composable
private fun LibraryOfflineTile(title: String, subtitle: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(KimoColors.Surface)
        .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = title }
        .heightIn(min = 72.dp).padding(horizontal = 13.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(28.dp), tint = KimoColors.Orange)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.sp, lineHeight = 16.sp, color = KimoColors.Secondary)
        }
    }
}

// 最近播放保持单行入口，历史内容在独立列表中按真实播放记录展示。
@Composable
private fun LibraryRecentAccess(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(KimoColors.Surface)
        .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "最近播放" }
        .heightIn(min = 46.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(23.dp), tint = KimoColors.Ink)
        Text("最近播放", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("查看全部", fontSize = 11.sp, color = KimoColors.Secondary)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = KimoColors.Secondary)
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

// 歌单入口在两种账号状态下占据同一位置，登录后展示真实云歌单数量。
@Composable
internal fun LibraryPlaylistAccess(authenticated: Boolean, count: Int?, onOpen: () -> Unit) {
    Column {
        HorizontalDivider(color = KimoColors.Rail.copy(alpha = 0.55f), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "我的歌单" }
            .padding(horizontal = 4.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(24.dp))
            Text("我的歌单", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!authenticated) Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = KimoColors.Secondary)
            Text(if (!authenticated) "登录后查看" else count?.let { "$it 个歌单" } ?: "查看全部",
                fontSize = 12.sp, color = KimoColors.Secondary)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = KimoColors.Secondary)
        }
        HorizontalDivider(color = KimoColors.Rail.copy(alpha = 0.55f), thickness = 0.5.dp)
    }
}
