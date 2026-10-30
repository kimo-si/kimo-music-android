package com.kimo.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kimo.music.data.Track
import com.kimo.music.ui.theme.KimoColors
import kotlin.math.min

private object SongFlightSpec {
    const val DurationMs = 560
    const val LandingScale = 0.08f
    val Diameter = 36.dp
    val ArcLift = 88.dp
    val ArcBend = 48.dp
}

internal data class SongFlight(val id: Long, val start: Offset, val end: Offset)

internal class SongFlightState {
    var flight by mutableStateOf<SongFlight?>(null); private set
    var landing by mutableLongStateOf(0); private set
    var root: LayoutCoordinates? = null
    val target = ArtworkAnchor()
    private var sequence = 0L
    private var enabled = true

    // 使用屏幕坐标兼容弹窗来源，再换算为当前绘制层的坐标。
    fun launch(source: Offset) {
        val origin = root?.takeIf { it.isAttached }?.localToScreen(Offset.Zero) ?: return
        val destination = target.center()
        if (!enabled || !source.isSpecified || !destination.isSpecified) return
        flight = SongFlight(++sequence, source - origin, destination - origin)
    }

    // 快速连续点歌只保留最新的一枚音符，旧动画不得清除新事件。
    fun complete(id: Long) {
        if (flight?.id != id) return
        landing++
        flight = null
    }

    // 完整播放页覆盖迷你条时停用反馈，防止飞向不可见的封面。
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) flight = null
    }
}

internal val LocalSongFlight = staticCompositionLocalOf<SongFlightState?> { null }

internal class ArtworkAnchor {
    private var coordinates: LayoutCoordinates? = null

    // 滚动只更新普通坐标，不写 Compose 状态，也不触发列表重组。
    fun update(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    // 只在点击时换算一次坐标，滚动和弹层展开期间不调用屏幕坐标转换。
    fun center(): Offset {
        val value = coordinates?.takeIf { it.isAttached } ?: return Offset.Unspecified
        return value.localToScreen(Offset(value.size.width / 2f, value.size.height / 2f))
    }
}

internal class SongSelection(val anchor: ArtworkAnchor, val play: () -> Unit)

// 歌曲行共用同一个反馈入口，真实播放操作保持原有模型调用。
@Composable
internal fun rememberSongSelection(onPlay: () -> Unit): SongSelection {
    val feedback = LocalSongFlight.current
    val action = rememberUpdatedState(onPlay)
    val anchor = remember { ArtworkAnchor() }
    return remember(feedback) { SongSelection(anchor) { feedback?.launch(anchor.center()); action.value() } }
}

// 全屏透明绘制层只承载一个矢量音符，动画帧仅更新图层，不重排底层页面。
@Composable
internal fun SongFlightLayer() {
    val feedback = LocalSongFlight.current ?: return
    val flight = feedback.flight
    val progress = remember { Animatable(0f) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { feedback.root = it }) {
        if (flight != null) FlyingNote(flight, progress)
    }
    LaunchedEffect(flight?.id) {
        if (flight == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SongFlightSpec.DurationMs, easing = FastOutSlowInEasing))
        feedback.complete(flight.id)
    }
}

// 从点击封面沿贝塞尔曲线飞向当前播放封面，落点处缩小并柔和消失。
@Composable
private fun FlyingNote(flight: SongFlight, progress: Animatable<Float, *>) {
    val density = LocalDensity.current
    val lift = with(density) { SongFlightSpec.ArcLift.toPx() }
    val bend = with(density) { SongFlightSpec.ArcBend.toPx() }
    Icon(Icons.Rounded.MusicNote, null, Modifier.size(SongFlightSpec.Diameter).testTag("song-flight").graphicsLayer {
        val fraction = progress.value.coerceIn(0f, 1f)
        val point = songFlightPoint(flight.start, flight.end, fraction, lift, bend)
        translationX = point.x - size.width / 2
        translationY = point.y - size.height / 2
        scaleX = 1f - fraction * 0.5f
        scaleY = scaleX
        rotationZ = -18f * (1f - fraction)
        alpha = min(fraction / 0.08f, (1f - fraction) / 0.12f).coerceIn(0f, 1f)
    }.background(KimoColors.Paper, CircleShape).padding(7.dp), tint = KimoColors.Orange)
}

// 曲线路径的首尾严格对应两个真实封面中心，比例越界时安全收敛到端点。
internal fun songFlightPoint(start: Offset, end: Offset, progress: Float, lift: Float, bend: Float): Offset {
    val t = progress.coerceIn(0f, 1f)
    val inverse = 1f - t
    val first = start + Offset(bend, -lift)
    val second = end + Offset(bend, -lift)
    return start * (inverse * inverse * inverse) + first * (3 * inverse * inverse * t) +
        second * (3 * inverse * t * t) + end * (t * t * t)
}

// 迷你封面只在落点事件时轻微回弹，不新增常驻动画或每帧组合状态。
@Composable
internal fun MiniPlayerArtwork(track: Track) {
    val feedback = LocalSongFlight.current
    val landing = feedback?.landing ?: 0
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(landing) {
        if (landing == 0L) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
    }
    TrackArtwork(track, Modifier.size(46.dp).testTag("mini-player-artwork")
        .onGloballyPositioned { feedback?.target?.update(it) }
        .graphicsLayer { scaleX = 1f + pulse.value * SongFlightSpec.LandingScale; scaleY = scaleX })
}
