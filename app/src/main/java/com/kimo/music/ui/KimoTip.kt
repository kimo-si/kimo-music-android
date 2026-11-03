package com.kimo.music.ui

import android.view.WindowManager.LayoutParams
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kimo.music.ui.theme.KimoColors

private val LocalTipWindows = staticCompositionLocalOf<TipWindows?> { null }
private val LocalTipTopInset = staticCompositionLocalOf { 0 }
// 使用屏幕坐标摆放非焦点窗口，窄对话框也不会改变全局提示的位置。
private val TipPopupProperties = PopupProperties(
    flags = LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    dismissOnBackPress = false, dismissOnClickOutside = false,
)

private object TipStyle {
    val MaxWidth = 360.dp
    val PageInset = 20.dp
    val TopGap = 12.dp
    val Radius = 18.dp
    val IconSize = 32.dp
}

private class TipWindows(val snackbar: SnackbarHostState) {
    private val windows = mutableStateListOf<Any>()

    // 按窗口打开顺序登记宿主，提示只在最上层窗口展示一次。
    fun register(window: Any) { windows.add(window) }

    // 弹窗关闭后释放宿主，尚未结束的提示交回下层页面。
    fun unregister(window: Any) { windows.remove(window) }

    // 通过身份核对当前窗口，避免底页与弹窗同时朗读同一条提示。
    fun isTop(window: Any): Boolean = windows.lastOrNull() === window
}

// 全应用共用消息计时与提示样式，各窗口只决定浮层显示在哪一层。
@Composable
internal fun KimoTipProvider(snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    val windows = remember(snackbar) { TipWindows(snackbar) }
    val density = LocalDensity.current
    val top = WindowInsets.safeDrawing.getTop(density) + with(density) { TipStyle.TopGap.roundToPx() }
    CompositionLocalProvider(LocalTipWindows provides windows, LocalTipTopInset provides top) {
        KimoTipWindow()
        content()
    }
}

// 为页面或原生弹窗登记提示宿主，非焦点浮层不打断输入、返回或页面操作。
@Composable
internal fun KimoTipWindow() {
    val windows = LocalTipWindows.current ?: return
    val window = remember { Any() }
    DisposableEffect(windows, window) {
        windows.register(window)
        onDispose { windows.unregister(window) }
    }
    if (!windows.isTop(window) || windows.snackbar.currentSnackbarData == null) return
    val top = LocalTipTopInset.current
    val position = remember(top) { TipPosition(top) }
    Popup(popupPositionProvider = position, properties = TipPopupProperties) {
        SnackbarHost(windows.snackbar, Modifier.padding(horizontal = TipStyle.PageInset)) { data -> KimoTip(data) }
    }
}

private class TipPosition(private val top: Int) : PopupPositionProvider {
    // 固定在窗口顶部安全区域，不跟随底部面板或滚动内容的位置移动。
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset((windowSize.width - popupContentSize.width) / 2, top)
}

// 暖纸与午夜皮肤共用圆角浮层，长消息自然换行并保留明确的关闭入口。
@Composable
private fun KimoTip(data: SnackbarData) {
    Surface(Modifier.widthIn(max = TipStyle.MaxWidth).fillMaxWidth().testTag("global-tip"),
        shape = RoundedCornerShape(TipStyle.Radius), color = KimoColors.TipSurface,
        contentColor = KimoColors.Ink, shadowElevation = 8.dp,
        border = BorderStroke(0.75.dp, KimoColors.Rail.copy(alpha = 0.7f))) {
        Row(Modifier.heightIn(min = 60.dp).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(TipStyle.IconSize).background(KimoColors.Orange.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Info, null, Modifier.size(19.dp), tint = KimoColors.Orange)
            }
            Column(Modifier.weight(1f)) {
                Text(data.visuals.message, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                data.visuals.actionLabel?.let { label ->
                    TextButton(data::performAction, contentPadding = PaddingValues(0.dp)) { Text(label, color = KimoColors.Orange) }
                }
            }
            IconButton(data::dismiss, Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(17.dp), tint = KimoColors.Secondary)
            }
        }
    }
}
