package com.kimo.music.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.kimo.music.core.CatalogEntry
import com.kimo.music.playback.MusicViewModel

// 云歌单管理始终围绕用户选定的对象，删除前明确说明影响范围。
@Composable
fun ManagePlaylistDialog(entry: CatalogEntry, model: MusicViewModel, onDismiss: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    when {
        rename -> PlaylistNameDialog("重命名歌单", onDismiss, initial = entry.title) { name ->
            model.online.changePlaylist(entry, name, onResult = model::notice); onDismiss()
        }
        deleting -> ConfirmPlaylistDeletion(entry, model, onDismiss)
        else -> KimoActionSheet(entry.title, onDismiss, "管理歌单") {
            if (entry.listType == 0) KimoSheetOption("重命名云歌单", Icons.Outlined.Edit, { rename = true })
            KimoSheetOption(if (entry.listType == 0) "删除这个云歌单" else "取消收藏这个歌单",
                Icons.Outlined.DeleteOutline, { deleting = true })
        }
    }
}

// 用户确认后才发送云端删除请求，不扩大为清空账号曲库。
@Composable
private fun ConfirmPlaylistDeletion(entry: CatalogEntry, model: MusicViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认处理「${entry.title}」？") },
        text = {
            KimoTipWindow()
            Text(if (entry.listType == 0) "将删除酷狗账号中的这个歌单，歌单内的歌曲不会从平台曲库删除。"
                else "将从酷狗账号的收藏歌单中移除这个歌单。")
        },
        confirmButton = { TextButton({
            model.online.changePlaylist(entry, null, delete = true, onResult = model::notice)
            onDismiss()
            model.online.closeDetail()
        }) { Text("确认") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
