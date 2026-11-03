package com.kimo.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kimo.music.data.AppearanceMode
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoLayout
import com.kimo.music.ui.theme.KimoTheme

// 设置使用独立窗口承载全屏内容，返回皮肤列表时不丢失当前页面状态。
@Composable
internal fun SettingsScreen(model: MusicViewModel, onClose: () -> Unit, actions: LibraryActions) {
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    val back = { if (appearanceOpen) appearanceOpen = false else onClose() }
    Dialog(back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        KimoTipWindow()
        BackHandler(onBack = back)
        ThemedSheetNavigationBar(includeStatusBar = true)
        Surface(Modifier.fillMaxSize(), color = KimoColors.Paper) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                SettingsHeader(if (appearanceOpen) "外观皮肤" else "设置", back)
                if (appearanceOpen) AppearanceSettings(model.appearance, model::changeAppearance)
                else SettingsContent(model, onClose, actions) { appearanceOpen = true }
            }
        }
    }
}

// 页面标题沿用搜索页的大字和返回按钮，让导航入口保持一致。
@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon("返回$title", Icons.Outlined.ChevronLeft, onClick = onBack)
        Text(title, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = KimoColors.Ink)
    }
}

// 按外观与聆听分组，账号、音质和导入继续使用已有操作。
@Composable
private fun SettingsContent(model: MusicViewModel, onClose: () -> Unit, actions: LibraryActions, onAppearance: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = KimoLayout.PageInset),
        verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SettingsAccount(model) { onClose(); model.loginRequested = true }
        SettingsGroup("个性外观") {
            SettingsRow("外观皮肤", "为 Kimo 换一种氛围", Icons.Outlined.Palette, model.appearance.title, onAppearance)
        }
        SettingsGroup("聆听偏好") {
            SettingsRow("在线音质", "选择音乐播放品质", Icons.Outlined.Equalizer, model.quality.title) {
                onClose(); actions.quality()
            }
            HorizontalDivider(Modifier.padding(start = 42.dp), color = KimoColors.Rail.copy(alpha = 0.55f))
            SettingsRow("导入本地音频", "把设备里的音乐带进来", Icons.Outlined.LibraryMusic) {
                onClose(); actions.importAudio()
            }
        }
        SettingsExtensions(model, onClose)
        Spacer(Modifier.height(12.dp))
    }
}

// 新增功能入口沿用既有设置行，打开独立页面前关闭当前设置窗口。
@Composable
private fun SettingsExtensions(model: MusicViewModel, onClose: () -> Unit) {
    SettingsGroup("播放与桌面") {
        SettingsRow("音效与桌面", "均衡器、渐入渐出、桌面歌词与小组件", Icons.Outlined.Tune) {
            onClose(); model.soundSettingsRequested = true
        }
    }
    SettingsGroup("音乐管理") {
        SettingsRow("下载管理", "下载进度、暂停重试和离线播放", Icons.Outlined.Download) {
            onClose(); model.downloadsRequested = true
        }
        SettingsRow("本地曲库", "按歌手、专辑和文件夹整理音乐", Icons.Outlined.LibraryMusic) {
            onClose(); model.localLibraryRequested = true
        }
    }
    SettingsGroup("应用") {
        SettingsRow("关于 Kimo", "版本信息、更新日志与检查更新", Icons.Outlined.Info, model.updates.installedVersion) {
            onClose(); model.aboutRequested = true
        }
    }
}

// 账号区域只展示真实资料，无资料时提供明确的登录入口。
@Composable
private fun SettingsAccount(model: MusicViewModel, onOpen: () -> Unit) {
    val profile = model.account.state.profile
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(KimoColors.Surface)
        .clickable(role = Role.Button, onClick = onOpen).padding(18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AccountAvatar(profile?.avatar, Modifier.size(52.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(profile?.displayNickname?.takeIf(String::isNotBlank) ?: "账号信息", fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, color = KimoColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (model.account.state.verified) "查看与管理账号" else "登录，收藏你的每一份喜欢", fontSize = 12.sp,
                lineHeight = 18.sp, color = KimoColors.Secondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = KimoColors.Secondary)
    }
}

// 分组以轻量小标题和留白组织内容，避免设置项层层套卡片。
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = KimoColors.Secondary)
        content()
    }
}

// 整行均可触控，较长描述和放大字体会自然换行。
@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = KimoColors.Orange)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = KimoColors.Ink)
            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
        }
        if (detail != null) Text(detail, fontSize = 12.sp, color = KimoColors.Secondary)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = KimoColors.Secondary)
    }
}

// 主题选择保持当前页面，点击即应用；系统选项响应设备深浅模式。
@Composable
private fun AppearanceSettings(selected: AppearanceMode, onSelect: (AppearanceMode) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()
        .padding(horizontal = KimoLayout.PageInset), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("选一种喜欢的氛围，让音乐陪你。", Modifier.padding(bottom = 8.dp),
            fontSize = 14.sp, lineHeight = 21.sp, color = KimoColors.Secondary)
        AppearanceMode.entries.forEach { mode -> AppearanceOption(mode, selected == mode) { onSelect(mode) } }
        Text("切换后立即生效，重新打开也会记住。", Modifier.padding(top = 8.dp),
            fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
        Spacer(Modifier.height(16.dp))
    }
}

// 选项使用单选语义与橙色描边，预览自身的配色不随当前主题改变。
@Composable
private fun AppearanceOption(mode: AppearanceMode, selected: Boolean, onSelect: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = KimoColors.Paper,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) KimoColors.Orange else KimoColors.Rail)) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppearancePreview(mode)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mode.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = KimoColors.Ink)
                Text(mode.description, fontSize = 12.sp, lineHeight = 18.sp, color = KimoColors.Secondary)
            }
            Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                Modifier.size(22.dp), tint = if (selected) KimoColors.Orange else KimoColors.Secondary)
        }
    }
}

// 用简化播放界面预览暖纸与午夜配色，系统模式显示对应设备主题。
@Composable
private fun AppearancePreview(mode: AppearanceMode) {
    KimoTheme(mode) {
        Column(Modifier.width(72.dp).height(88.dp).clip(RoundedCornerShape(10.dp)).background(KimoColors.Surface)
            .padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.width(23.dp).height(4.dp).background(KimoColors.Ink, CircleShape))
                Icon(if (mode == AppearanceMode.System) Icons.Outlined.BrightnessAuto else Icons.Outlined.MusicNote,
                    null, Modifier.size(12.dp), tint = KimoColors.Orange)
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(KimoColors.Paper, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Album, null, Modifier.size(25.dp), tint = KimoColors.Orange)
            }
            Box(Modifier.fillMaxWidth(0.7f).height(3.dp).background(KimoColors.Secondary, CircleShape))
        }
    }
}
