package com.kimo.music.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kimo.music.ui.theme.KimoLayout

private object PlayerMotionSpec {
    const val Stiffness = 450f
    const val SettledThreshold = 0.985f
    const val ContentFadeDistance = 0.18f
    val FallbackBottomSpace = 128.dp
}

// 页面和媒体状态独立，动画只持有展开进度与播放条实际位置。
internal class PlayerMotion(val transition: Transition<Boolean>, val progress: State<Float>) {
    val origin = mutableStateOf(Rect.Zero)
    val visible: Boolean get() = transition.currentState || transition.targetState || transition.isRunning

    // 底部页面始终保留布局，旋转之后自动使用新的播放条位置作为收起目标。
    fun updateOrigin(bounds: Rect) { origin.value = bounds }
}

// 临界阻尼弹簧支持中途反向，不因连续进入、返回而重置到首尾帧。
@Composable
internal fun rememberPlayerMotion(expanded: Boolean): PlayerMotion {
    val transition = updateTransition(expanded, label = "Player expansion")
    val progress = transition.animateFloat(transitionSpec = {
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PlayerMotionSpec.Stiffness, visibilityThreshold = 0.001f)
    }, label = "Player expansion progress") { if (it) 1f else 0f }
    return remember(transition) { PlayerMotion(transition, progress) }
}

// 保留浏览页面和滚动位置，过渡期间屏蔽其触摸与读屏，避免误点和重复节点。
internal fun Modifier.playerBrowseSurface(motion: PlayerMotion, covered: Boolean = false): Modifier {
    val drawing = graphicsLayer { alpha = if (covered || motion.progress.value >= 0.9999f) 0f else 1f }
    if (!motion.visible && !covered) return drawing
    return drawing.clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

// 全尺寸内容只绘制一次，移动、圆角与裁切在图层执行，不逐帧重排歌曲列表。
@Composable
internal fun PlayerMotionHost(motion: PlayerMotion, content: @Composable (Boolean) -> Unit) {
    if (!motion.visible) return
    val systemBarsActive by remember(motion) { derivedStateOf { motion.progress.value >= PlayerMotionSpec.SettledThreshold } }
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = 0.08f * motion.progress.value.coerceIn(0f, 1f))) }) {
        Box(Modifier.fillMaxSize().testTag("player-presentation").graphicsLayer {
            val progress = motion.progress.value.coerceIn(0f, 1f)
            val source = motion.origin.value.takeUnless(Rect::isEmpty) ?: Rect(
                KimoLayout.MiniPlayerInset.toPx(),
                (size.height - PlayerMotionSpec.FallbackBottomSpace.toPx() - KimoLayout.MiniPlayerHeight.toPx()).coerceAtLeast(0f),
                size.width - KimoLayout.MiniPlayerInset.toPx(), size.height - PlayerMotionSpec.FallbackBottomSpace.toPx())
            val bounds = playerMotionBounds(source, size, progress)
            translationX = bounds.left
            translationY = bounds.top
            shape = PlayerRevealShape(bounds.size, KimoLayout.MiniPlayerRadius.toPx() * (1f - progress))
            clip = true
            alpha = (progress / PlayerMotionSpec.ContentFadeDistance).coerceIn(0f, 1f)
        }) { content(systemBarsActive) }
    }
}

// 计算完整容器的绘制范围，屏幕尺寸变化或反向动画都不会产生越界矩形。
internal fun playerMotionBounds(source: Rect, viewport: Size, progress: Float): Rect {
    val fraction = progress.coerceIn(0f, 1f)
    val width = source.width.coerceIn(0f, viewport.width)
    val height = source.height.coerceIn(0f, viewport.height)
    val left = source.left.coerceIn(0f, (viewport.width - width).coerceAtLeast(0f))
    val top = source.top.coerceIn(0f, (viewport.height - height).coerceAtLeast(0f))
    return Rect(lerp(left, 0f, fraction), lerp(top, 0f, fraction),
        lerp(left + width, viewport.width, fraction), lerp(top + height, viewport.height, fraction))
}

private class PlayerRevealShape(private val bounds: Size, private val radius: Float) : Shape {
    // 圆角随容器展开逐渐消失，使用原生圆角裁切避免每帧重建位图。
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(0f, 0f, bounds.width, bounds.height), CornerRadius(radius)))
}
