package com.kimo.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.ReleaseChannel
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors

// 版本检查与更新日志来自公开发布接口，下载由系统浏览器执行。
@Composable
internal fun AboutScreen(model: MusicViewModel, onClose: () -> Unit) {
    val updates = model.updates
    val state = updates.state
    val uri = LocalUriHandler.current
    LaunchedEffect(Unit) { if (!state.checked && !state.checking) updates.check() }
    KimoFullScreen("关于 Kimo", onClose) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Kimo", fontSize = 38.sp)
            Text("当前版本 ${updates.installedVersion}", color = KimoColors.Secondary)
            Text("原生音乐播放器 · 在线曲库与离线聆听", color = KimoColors.Secondary, fontSize = 13.sp)
            OutlinedButton(updates::check, enabled = !state.checking) { Text(if (state.checking) "正在检查更新…" else "检查更新") }
            if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.checked && state.release == null && state.error == null) Text("暂未发布可用的安装包，请稍后检查。")
            state.release?.let { release ->
                Text(if (updates.available) "发现新版本 ${release.version}" else "当前已是最新版本", fontSize = 20.sp)
                if (updates.available && release.apkUrl != null) Button({ uri.openUri(release.apkUrl) }) { Text("下载更新") }
                TextButton({ uri.openUri(release.pageUrl) }) { Text("查看发布详情") }
                HorizontalDivider(color = KimoColors.Rail)
                Text("${release.version} 更新日志", fontSize = 18.sp)
                Text(release.notes.ifBlank { "此版本没有附带更新说明。" }, fontSize = 14.sp, lineHeight = 23.sp)
            }
            TextButton({ uri.openUri(ReleaseChannel.REPOSITORY) }) { Text("GitHub 源码与问题反馈") }
        }
    }
}
