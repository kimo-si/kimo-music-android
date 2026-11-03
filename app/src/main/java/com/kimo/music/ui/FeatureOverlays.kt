package com.kimo.music.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.kimo.music.data.Track
import com.kimo.music.data.online
import com.kimo.music.playback.MusicViewModel

// 功能页沿用根应用的主题与提示通道，关闭后保留原来浏览位置。
@Composable
internal fun FeatureOverlays(model: MusicViewModel) {
    if (model.downloadsRequested) DownloadCenterScreen(model) { model.downloadsRequested = false }
    if (model.localLibraryRequested) LocalLibraryScreen(model) { model.localLibraryRequested = false }
    if (model.soundSettingsRequested) SoundSettingsScreen(model) { model.soundSettingsRequested = false }
    if (model.aboutRequested) AboutScreen(model) { model.aboutRequested = false }
    model.trackActionsRequested?.let { track -> TrackActionsSheet(track, model) { model.trackActionsRequested = null } }
    DownloadNotificationPermission(model)
}

// 首次下载才请求通知权限，拒绝授权也不会取消用户已创建的下载任务。
@Composable
private fun DownloadNotificationPermission(model: MusicViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(model.notificationPromptRequested) {
        if (!model.notificationPromptRequested) return@LaunchedEffect
        model.notificationPromptRequested = false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

// 列表长按共用同一歌曲操作面板，当前目标不随播放器切歌而改变。
@Composable
private fun TrackActionsSheet(track: Track, model: MusicViewModel, onClose: () -> Unit) {
    KimoActionSheet(track.title, onClose, track.displayArtist.ifBlank { null }) {
        KimoSheetOption("下一首播放", Icons.Outlined.SkipNext, { onClose(); model.playNext(track) }, showChevron = false)
        KimoSheetOption("加入播放列表", Icons.Outlined.AddCircleOutline, { onClose(); model.enqueue(track) }, showChevron = false)
        KimoSheetOption(if (track.id in model.favorites) "取消喜欢" else "加入我喜欢", Icons.Outlined.FavoriteBorder,
            { onClose(); model.toggleFavorite(track) }, showChevron = false)
        if (track.online) KimoSheetOption("下载音频", Icons.Outlined.Download, { onClose(); model.download(track) }, showChevron = false)
        else KimoSheetOption("管理本地曲库", Icons.Outlined.LibraryMusic,
            { onClose(); model.localLibraryRequested = true }, showChevron = false)
    }
}
