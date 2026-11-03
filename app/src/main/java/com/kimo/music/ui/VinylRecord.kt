package com.kimo.music.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.Image
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.kimo.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private object VinylStyle {
    const val RotationMs = 24_000
    const val SampleSize = 28
    const val LabelRatio = 0.52f
    const val StageRatio = 0.83f
    val MaxSide = 330.dp
    val Fallback = Color(0xFFD97849)
}

private class VinylAppearance {
    var image by mutableStateOf<Image?>(null)
    var tint by mutableStateOf(VinylStyle.Fallback)
}

// 彩胶、封面和固定唱臂独立绘制，唱片尺寸始终受可用空间约束。
@Composable
internal fun PlayerVinyl(track: Track, playing: Boolean, visible: Boolean, modifier: Modifier = Modifier) {
    val source = track.coverUrl
    val appearance = rememberVinylAppearance(track.id, source)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moving = playing && visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled()
    val rotation = rememberVinylRotation(moving)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, VinylStyle.MaxSide / VinylStyle.StageRatio) * VinylStyle.StageRatio
        if (side > 0.dp) VinylDisc(track, source, appearance, rotation, side)
    }
}

// 从已加载的封面在后台提取颜色，换歌时丢弃旧封面与旧颜色。
@Composable
private fun rememberVinylAppearance(trackId: String, source: Any?): VinylAppearance {
    val appearance = remember(trackId, source) { VinylAppearance() }
    val image = appearance.image
    LaunchedEffect(appearance, image) {
        if (image == null) return@LaunchedEffect
        // 缩图和取色都在后台完成，切歌会取消旧结果的提交。
        appearance.tint = withContext(Dispatchers.Default) { sampleVinylImage(image) ?: VinylStyle.Fallback }
    }
    return appearance
}

// 播放时每 24 秒旋转一周；暂停、退出页面或进入后台后冻结当前角度。
@Composable
private fun rememberVinylRotation(moving: Boolean): State<Float> {
    val frozen = remember { mutableFloatStateOf(0f) }
    if (!moving) return frozen
    val start = remember { frozen.floatValue }
    val transition = rememberInfiniteTransition(label = "Vinyl rotation")
    val angle = transition.animateFloat(start, start + 360f,
        infiniteRepeatable(tween(VinylStyle.RotationMs, easing = LinearEasing), RepeatMode.Restart), label = "Vinyl angle")
    DisposableEffect(angle) { onDispose { frozen.floatValue = angle.value % 360f } }
    return angle
}

// 转动图层只包含彩胶和封面，光影与唱臂始终保持固定。
@Composable
private fun VinylDisc(track: Track, source: Any?, appearance: VinylAppearance, angle: State<Float>, side: Dp) {
    Box(Modifier.size(side).testTag("vinyl-stage"), contentAlignment = Alignment.Center) {
        VinylGlow(appearance.tint)
        Box(Modifier.fillMaxSize().testTag("vinyl-disc").graphicsLayer { rotationZ = angle.value },
            contentAlignment = Alignment.Center) {
            VinylGrooves(appearance.tint)
            VinylCover(track, source, side * VinylStyle.LabelRatio) { appearance.image = it }
            VinylSpindle(side)
        }
        VinylHighlight()
        VinylTonearm()
    }
}

// 柔和光晕使用封面提取色，与底部暗色氛围自然融合。
@Composable
private fun VinylGlow(tint: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), tint.copy(alpha = 0.17f), Color.Transparent),
            center = center, radius = size.minDimension * 0.72f), radius = size.minDimension * 0.72f)
    }
}

// 彩胶底色与密集同心纹理只在封面色变化时重绘。
@Composable
private fun VinylGrooves(tint: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.78f), tint, tint.copy(alpha = 0.72f)),
            center = center, radius = radius), radius = radius)
        var ring = radius * VinylStyle.LabelRatio + 4.dp.toPx()
        var index = 0
        while (ring < radius - 3.dp.toPx()) {
            val color = if (index % 2 == 0) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.08f)
            drawCircle(color, radius = ring, style = Stroke(width = if (index % 6 == 0) 1.15.dp.toPx() else 0.65.dp.toPx()))
            ring += 2.45.dp.toPx()
            index++
        }
        drawCircle(Color.Black.copy(alpha = 0.23f), radius = radius * VinylStyle.LabelRatio + 1.dp.toPx(),
            style = Stroke(width = 3.dp.toPx()))
    }
}

// 圆形标签展示当前歌曲的真实封面，缺图时使用中性音符占位。
@Composable
private fun VinylCover(track: Track, source: Any?, side: Dp, onImage: (Image) -> Unit) {
    val context = LocalContext.current
    val request = remember(context, source) { ImageRequest.Builder(context).data(source).allowHardware(false).build() }
    Box(Modifier.size(side).clip(CircleShape).background(PlayerPalette.Background)
        .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape)
        .semantics { contentDescription = "唱片封面：${track.title}" }, contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MusicNote, null, Modifier.fillMaxSize(0.3f), tint = PlayerPalette.Secondary)
        if (source != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { onImage(it.result.image) })
    }
}

// 轴心随唱片旋转，遮住封面中心并补充唱片的实体感。
@Composable
private fun VinylSpindle(side: Dp) {
    Box(Modifier.size(side * 0.030f).background(Color.Black.copy(alpha = 0.82f), CircleShape),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(side * 0.011f).background(Color.White.copy(alpha = 0.52f), CircleShape))
    }
}

// 固定高光与外缘不随唱片旋转，模拟灯光落在唱片上的效果。
@Composable
private fun VinylHighlight() {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f), Color.Transparent),
            center = Offset(size.width * 0.30f, size.height * 0.26f), radius = size.minDimension * 0.4f), radius = radius)
        drawCircle(Color.White.copy(alpha = 0.16f), radius = radius - 1.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
    }
}

// 唱臂位置沿用参考项目的圆周角度，线缆、杆体与唱针保持静止。
@Composable
private fun VinylTonearm() {
    Canvas(Modifier.fillMaxSize()) {
        val radius = size.minDimension / 2f
        val pivot = polarPoint(28f, radius * 1.045f)
        val head = polarPoint(82f, radius * 0.71f)
        drawTonearmCable(pivot)
        drawLine(Color.Black.copy(alpha = 0.25f), pivot + Offset(1.dp.toPx(), 2.dp.toPx()),
            head + Offset(1.dp.toPx(), 2.dp.toPx()), strokeWidth = maxOf(4.dp.toPx(), size.width * 0.016f), cap = StrokeCap.Round)
        drawLine(Color.White, pivot, head, strokeWidth = maxOf(3.6.dp.toPx(), size.width * 0.011f), cap = StrokeCap.Round)
        drawTonearmHead(pivot, head)
    }
}

// 按角度计算唱臂锚点，零度对应唱片正上方。
private fun DrawScope.polarPoint(degrees: Float, radius: Float): Offset {
    val radians = degrees * PI.toFloat() / 180f
    return center + Offset(sin(radians) * radius, -cos(radians) * radius)
}

// 绘制唱臂上方的弯曲线缆。
private fun DrawScope.drawTonearmCable(pivot: Offset) {
    val path = Path().apply {
        moveTo(pivot.x, pivot.y)
        lineTo(pivot.x + size.width * 0.006f, pivot.y - size.width * 0.075f)
        quadraticTo(pivot.x + size.width * 0.09f, pivot.y - size.width * 0.09f,
            pivot.x + size.width * 0.072f, pivot.y - size.width * 0.006f)
    }
    drawPath(path, Color.White, style = Stroke(width = maxOf(2.2.dp.toPx(), size.width * 0.007f), cap = StrokeCap.Round))
}

// 唱针和固定轴使用短粗线与双层圆点，保持清晰的层次。
private fun DrawScope.drawTonearmHead(pivot: Offset, head: Offset) {
    val direction = head - pivot
    val length = direction.getDistance().coerceAtLeast(1f)
    val half = direction / length * (size.width * 0.021f)
    drawLine(Color.White, head - half, head + half, strokeWidth = maxOf(6.dp.toPx(), size.width * 0.025f), cap = StrokeCap.Round)
    drawCircle(Color.White, radius = size.width * 0.023f, center = pivot)
    drawCircle(Color.Black.copy(alpha = 0.16f), radius = size.width * 0.009f, center = pivot)
}

// 将 Coil 已解码的图片缩为小样本，失败时回退到主题颜色。
private fun sampleVinylImage(image: Image): Color? = try {
    sampleVinylTint(image.toBitmap(VinylStyle.SampleSize, VinylStyle.SampleSize))
} catch (_: Exception) { null }

// 排除透明、过亮和过暗像素后调整 HSL，使唱片颜色贴近参考实现。
internal fun sampleVinylTint(bitmap: Bitmap): Color? {
    val average = averageVinylColor(bitmap) ?: return null
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(average, hsl)
    hsl[1] = (hsl[1] * 1.55f).coerceIn(0.50f, 0.90f)
    hsl[2] = (hsl[2] * 1.08f).coerceIn(0.40f, 0.60f)
    return Color(ColorUtils.HSLToColor(hsl))
}

// 仅遍历缩略图像素，不在主线程处理原始大图。
private fun averageVinylColor(bitmap: Bitmap): Int? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var red = 0L; var green = 0L; var blue = 0L; var count = 0
    for (pixel in pixels) {
        val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
        val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        if (android.graphics.Color.alpha(pixel) < 128 || luminance !in 0.08f..0.92f) continue
        red += r; green += g; blue += b; count++
    }
    if (count == 0) return null
    return android.graphics.Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}
