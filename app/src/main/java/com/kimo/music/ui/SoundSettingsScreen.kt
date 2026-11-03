package com.kimo.music.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.kimo.music.playback.EqualizerBand
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.widget.MusicWidgetProvider
import kotlin.math.roundToInt

// 音效、渐入渐出、桌面歌词和小组件入口统一放在聆听设置中。
@Composable
internal fun SoundSettingsScreen(model: MusicViewModel, onClose: () -> Unit) {
    KimoFullScreen("音效与桌面", onClose) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            EqualizerSettings(model)
            HorizontalDivider(color = KimoColors.Rail)
            FadeSettings(model)
            HorizontalDivider(color = KimoColors.Rail)
            DesktopLyricsSettings(model)
            WidgetSettings(model)
        }
    }
}

// 频段以设备能力为准，不支持时保留准确说明并禁用无效开关。
@Composable
private fun EqualizerSettings(model: MusicViewModel) {
    val options = model.listening.options
    val status = model.equalizer
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("均衡器", Modifier.weight(1f), fontSize = 18.sp)
            Switch(options.equalizerEnabled && status.available, { model.listening.update(options.copy(equalizerEnabled = it)) },
                Modifier.semantics { contentDescription = "启用均衡器" }, enabled = status.available)
        }
        if (!status.available) Text(status.message, color = KimoColors.Secondary, fontSize = 13.sp)
        if (status.available) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("平直", "低音", "人声", "摇滚").forEach { name ->
                    AssistChip({ model.listening.update(options.copy(equalizerEnabled = true,
                        equalizerGains = status.bands.map { presetLevel(name, it.frequencyHz) })) }, label = { Text(name) })
                }
            }
            status.bands.forEach { band -> EqualizerSlider(band, options.equalizerEnabled) { gain ->
                val gains = status.bands.map { if (it.index == band.index) gain else it.level }
                model.listening.update(model.listening.options.copy(equalizerGains = gains))
            } }
        }
    }
}

// 增益在手势完成后提交，避免拖动时反复写入持久化配置。
@Composable
private fun EqualizerSlider(band: EqualizerBand, enabled: Boolean, onCommit: (Int) -> Unit) {
    var value by remember(band.level) { mutableFloatStateOf(band.level.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (band.frequencyHz >= 1000) "${band.frequencyHz / 1000f} kHz" else "${band.frequencyHz} Hz", Modifier.width(65.dp), fontSize = 12.sp)
        Slider(value, { value = it }, Modifier.weight(1f), enabled = enabled && band.maximum > band.minimum,
            valueRange = band.minimum.toFloat()..band.maximum.coerceAtLeast(band.minimum + 1).toFloat(), onValueChangeFinished = { onCommit(value.roundToInt()) })
        Text("${(value / 10).roundToInt() / 10f} dB", Modifier.width(55.dp), fontSize = 12.sp)
    }
}

// 预设按真实中心频率计算，设备频段数量变化也能保持相同听感方向。
private fun presetLevel(name: String, hz: Int): Int = when (name) {
    "低音" -> if (hz < 250) 500 else 0
    "人声" -> if (hz in 250..4000) 350 else -100
    "摇滚" -> if (hz < 250 || hz > 4000) 400 else -150
    else -> 0
}

// 渐入渐出允许完全关闭，时长保存后由后台音频包络执行。
@Composable
private fun FadeSettings(model: MusicViewModel) {
    Text("歌曲渐入渐出", fontSize = 18.sp)
    Text("播放开始淡入，曲目结束淡出", fontSize = 13.sp, color = KimoColors.Secondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L).forEach { duration ->
            FilterChip(model.listening.options.fadeMs == duration, { model.listening.update(model.listening.options.copy(fadeMs = duration)) },
                label = { Text(if (duration == 0L) "关闭" else if (duration % 1000 == 0L) "${duration / 1000} 秒" else "${duration / 1000f} 秒") })
        }
    }
}

// 用户开启功能时才请求悬浮窗授权，拒绝或返回后保留关闭状态。
@Composable
private fun DesktopLyricsSettings(model: MusicViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Settings.canDrawOverlays(context)
        model.listening.update(model.listening.options.copy(desktopLyrics = granted))
        if (!granted) model.notice("未授予悬浮窗权限，桌面歌词保持关闭")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("桌面歌词", fontSize = 18.sp)
            Text("离开 Kimo 后显示，可拖动或随时关闭", fontSize = 12.sp, color = KimoColors.Secondary)
        }
        Switch(model.listening.options.desktopLyrics && Settings.canDrawOverlays(context), { enabled ->
            if (!enabled || Settings.canDrawOverlays(context)) model.listening.update(model.listening.options.copy(desktopLyrics = enabled))
            else runCatching { launcher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())) }
                .onFailure { model.notice("请在系统设置中为 Kimo 开启悬浮窗权限") }
        }, Modifier.semantics { contentDescription = "开启桌面歌词" })
    }
}

// 添加入口交由系统桌面确认，控件控制当前媒体会话而非另启播放器。
@Composable
private fun WidgetSettings(model: MusicViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("桌面小组件", fontSize = 18.sp)
        Text("在桌面查看歌曲、暂停播放或切换上一首和下一首", fontSize = 13.sp, color = KimoColors.Secondary)
        OutlinedButton({
            val manager = AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, MusicWidgetProvider::class.java), null, null)
            else model.notice("请长按桌面，在小组件列表中添加 Kimo 音乐")
        }) { Text("添加桌面小组件") }
    }
}
