package com.kimo.music.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.data.formatDuration
import com.kimo.music.playback.MusicViewModel
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

private object SeekStyle {
    const val CycleMs = 1400
    const val SettleMs = 180
    val ThumbArea = 10.dp
    val ThumbRadius = 5.dp
    val TrackArea = 20.dp
    val TrackWidth = 2.5.dp
    val WaveHeight = 1.8.dp
    val Wavelength = 24.dp
    val SampleStep = 3.dp
    val WaveTaper = 8.dp
}

private data class SeekMotion(val phase: State<Float>,
    val energy: State<Float>, val thumbScale: State<Float>, val dragging: Boolean)

// 保留真实进度与拖动提交规则，动效状态仅由绘制层读取。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeek(model: MusicViewModel) {
    val playback = model.playback
    var dragging by remember(playback.track?.id) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
    val position = dragging ?: playback.positionMs.toFloat().coerceIn(0f, duration)
    val motion = rememberSeekMotion(playback.playing && dragging == null, dragging != null)
    Column {
        Slider(position, { dragging = it }, valueRange = 0f..duration,
            onValueChangeFinished = { dragging?.let { model.seek(it.toLong()) }; dragging = null },
            modifier = Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = "播放进度" },
            colors = SliderDefaults.colors(thumbColor = PlayerPalette.Accent,
                activeTrackColor = PlayerPalette.Accent, inactiveTrackColor = PlayerPalette.Rail),
            thumb = { SeekThumb(motion) }, track = { SeekWaveTrack(position / duration, motion) })
        SeekTimes(position.toLong(), playback.durationMs)
    }
}

// 暂停时冻结波形相位并平滑收平，关闭系统动画时不运行循环任务。
@Composable
private fun rememberSeekMotion(moving: Boolean, dragging: Boolean): SeekMotion {
    val phase = rememberSeekPhase(moving)
    val energy = animateFloatAsState(if (moving) 1f else 0f, tween(SeekStyle.SettleMs), label = "Seek wave energy")
    val thumb = animateFloatAsState(if (dragging) 1.4f else 1f, tween(SeekStyle.SettleMs), label = "Seek thumb size")
    return remember(phase, energy, thumb, dragging) { SeekMotion(phase, energy, thumb, dragging) }
}

// 仅在播放时创建系统管理的循环动画，暂停时保存相位，恢复后延续原波形。
@Composable
private fun rememberSeekPhase(moving: Boolean): State<Float> {
    val frozen = remember { mutableFloatStateOf(0f) }
    if (!moving) return frozen
    val start = remember { frozen.floatValue }
    val transition = rememberInfiniteTransition(label = "Seek wave motion")
    val phase = transition.animateFloat(start, start + 1f,
        infiniteRepeatable(tween(SeekStyle.CycleMs, easing = LinearEasing), RepeatMode.Restart), label = "Seek wave phase")
    DisposableEffect(phase) { onDispose { frozen.floatValue = phase.value % 1f } }
    return phase
}

// 未播放部分保持安静的轨道，已播放部分绘制轻微流动的橙色波形。
@Composable
private fun SeekWaveTrack(progress: Float, motion: SeekMotion) {
    val path = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(SeekStyle.TrackArea)) {
        val end = size.width * progress.coerceIn(0f, 1f)
        val center = size.height / 2f
        val stroke = SeekStyle.TrackWidth.toPx()
        drawLine(PlayerPalette.Rail, Offset(end, center), Offset(size.width, center), strokeWidth = stroke, cap = StrokeCap.Round)
        if (end <= 0f) return@Canvas
        val height = SeekStyle.WaveHeight.toPx() * motion.energy.value
        val wavelength = SeekStyle.Wavelength.toPx()
        val taper = SeekStyle.WaveTaper.toPx()
        val samples = ceil(end / SeekStyle.SampleStep.toPx()).toInt().coerceAtLeast(1)
        val phase = motion.phase.value
        path.reset()
        for (index in 0..samples) {
            val x = end * index / samples
            val envelope = min(1f, min(x, end - x) / taper).coerceAtLeast(0f)
            val y = center + sin((x / wavelength - phase) * 2f * PI).toFloat() * height * envelope
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, PlayerPalette.Accent, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

// 固定绘制区域承载呼吸光晕与拖动放大，不逐帧改变 Slider 的布局尺寸。
@Composable
private fun SeekThumb(motion: SeekMotion) {
    Canvas(Modifier.size(SeekStyle.ThumbArea)) {
        val energy = motion.energy.value
        val pulse = (sin(motion.phase.value * 2f * PI).toFloat() + 1f) / 2f
        val radius = SeekStyle.ThumbRadius.toPx() * motion.thumbScale.value
        val halo = if (motion.dragging) 0.2f else (0.08f + pulse * 0.08f) * energy
        drawCircle(PlayerPalette.Accent.copy(alpha = halo * 0.45f), radius = radius + (4f + pulse) * density)
        drawCircle(PlayerPalette.Accent.copy(alpha = halo), radius = radius + 2.dp.toPx())
        drawCircle(PlayerPalette.Accent, radius = radius)
    }
}

// 时间始终来自媒体会话或当前拖动位置，不用动画推算虚假的播放进度。
@Composable
private fun SeekTimes(positionMs: Long, durationMs: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(positionMs), fontSize = 12.sp, lineHeight = 18.sp, color = PlayerPalette.Secondary)
        Text(if (durationMs > 0) formatDuration(durationMs) else "--:--", fontSize = 12.sp, lineHeight = 18.sp, color = PlayerPalette.Secondary)
    }
}
