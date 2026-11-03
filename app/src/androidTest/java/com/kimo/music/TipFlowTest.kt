package com.kimo.music

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.data.AppearanceMode
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.KimoTipProvider
import com.kimo.music.ui.PlaylistNameDialog
import com.kimo.music.ui.theme.KimoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class TipFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 含操作按钮的无限期消息也必须在三秒后消失，不受 Material 默认时长影响。
    @Test fun actionableTipExpiresAtThreeSecondsWithoutManualDismiss() {
        val snackbar = SnackbarHostState()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.setContent {
                KimoTheme { KimoTipProvider(snackbar) {
                    LaunchedEffect(Unit) { snackbar.showSnackbar("下载已完成", "查看下载", duration = SnackbarDuration.Indefinite) }
                } }
            } }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithText("查看下载").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(2_800)
            compose.onNodeWithTag("global-tip").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("global-tip").assertDoesNotExist()
            compose.runOnIdle { assertEquals(null, snackbar.currentSnackbarData) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    // 新消息拥有独立三秒，旧消息的计时器不能提前关闭新结果。
    @Test fun replacingTipStartsANewThreeSecondLifetime() {
        val snackbar = SnackbarHostState()
        val message = mutableStateOf("第一条结果")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.setContent {
                KimoTheme { KimoTipProvider(snackbar) {
                    LaunchedEffect(message.value) { snackbar.showSnackbar(message.value, duration = SnackbarDuration.Indefinite) }
                } }
            } }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.advanceTimeBy(1_500)
            compose.runOnIdle { message.value = "第二条结果" }
            compose.mainClock.advanceTimeBy(64)
            compose.mainClock.advanceTimeBy(1_700)
            compose.onNodeWithText("第二条结果").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_400)
            compose.onNodeWithTag("global-tip").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
    }

    // 同一条提示实时跟随主题，实际像素改变且无需手动关闭便自动释放浮层。
    @Test fun tipFollowsThemeAndDismissesAutomatically() {
        val model = currentModel()
        compose.runOnIdle { model.changeAppearance(AppearanceMode.Light); model.notice("已加入我喜欢") }
        assertSingleTip("已加入我喜欢")
        assertTipSurface(Color(0xFFFCF9F2))
        saveScreen("tip-light")
        compose.runOnIdle { model.changeAppearance(AppearanceMode.Dark) }
        assertSingleTip("已加入我喜欢")
        assertTipSurface(Color(0xFF2C302B))
        saveScreen("tip-dark")
        compose.onNodeWithContentDescription("关闭提示").assertDoesNotExist()
        compose.waitUntil(4_000) { compose.onAllNodesWithTag("global-tip").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(null, model.message) }
    }

    // 原生弹窗切换时只有最上层宿主显示提示，浮层不阻塞窗口外的正常操作。
    @Test fun tipsRemainVisibleAcrossSettingsLoginAndSheets() {
        val model = currentModel()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.runOnIdle { model.notice("已加入我喜欢") }
        assertSingleTip("已加入我喜欢")
        saveScreen("tip-settings")
        compose.onNodeWithContentDescription("返回设置").performClick()
        assertSingleTip("已加入我喜欢")
        compose.runOnIdle { model.loginRequested = true; model.notice("请登录后同步收藏") }
        assertSingleTip("请登录后同步收藏")
        saveScreen("tip-login")
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("在线音质").performClick()
        compose.runOnIdle { model.notice("音质设置已更新") }
        assertSingleTip("音质设置已更新")
        saveScreen("tip-sheet")
        compose.onNodeWithContentDescription("关闭在线音质").performClick()
        assertSingleTip("音质设置已更新")
    }

    // 新结果替换旧结果，长消息完整换行；提示不抢输入焦点且到时自动收起。
    @Test fun latestTipWrapsWithoutInterruptingSearchAndExpires() {
        val model = currentModel()
        compose.onNodeWithContentDescription("搜索音乐").performClick()
        compose.onNodeWithContentDescription("搜索关键词").performTextInput("周")
        compose.runOnIdle { model.notice("已加入播放列表") }
        assertSingleTip("已加入播放列表")
        val result = "歌曲已加入我喜欢，云端列表暂时无法刷新，请稍后在喜欢的歌曲中重新同步。"
        compose.runOnIdle { model.notice(result) }
        assertSingleTip(result)
        compose.onNodeWithText("已加入播放列表").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索关键词").assertIsFocused().performTextInput("杰伦")
        compose.onNodeWithContentDescription("搜索关键词").assertTextEquals("周杰伦")
        saveScreen("tip-multiline-search")
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("global-tip").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(null, model.message) }
    }

    // 窄歌单对话框仍将提示放在屏幕安全区域，关闭弹窗后提示继续由页面接管。
    @Test fun tipInCompactDialogUsesScreenBoundsAndSurvivesClosing() {
        val open = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                KimoTheme {
                    val snackbar = remember { SnackbarHostState() }
                    KimoTipProvider(snackbar) {
                        if (open.value) PlaylistNameDialog("新建云歌单", { open.value = false }) { }
                        LaunchedEffect(Unit) { snackbar.showSnackbar("歌单名称将在保存后同步", duration = SnackbarDuration.Indefinite) }
                    }
                }
            }
        }
        assertSingleTip("歌单名称将在保存后同步")
        saveScreen("tip-compact-dialog")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("新建云歌单").assertDoesNotExist()
        assertSingleTip("歌单名称将在保存后同步")
        compose.onNodeWithContentDescription("关闭提示").assertDoesNotExist()
        compose.waitUntil(4_000) { compose.onAllNodesWithTag("global-tip").fetchSemanticsNodes().isEmpty() }
    }

    // 读取真实页面模型，提示与生产收藏、播放反馈使用同一入口。
    private fun currentModel(): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        return model
    }

    // 同时核对可见内容与宿主数量，防止底层页面重复展示或朗读。
    private fun assertSingleTip(message: String) {
        compose.onNodeWithTag("global-tip").assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onAllNodesWithTag("global-tip").assertCountEquals(1)
        val top = compose.onNodeWithTag("global-tip").fetchSemanticsNode().layoutInfo.coordinates.positionOnScreen().y
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("提示必须位于屏幕顶部，而非小弹窗内部：$top", top in 0f..100f * density)
    }

    // 读取卡片左侧内部像素，避开圆角边框、图标和文字。
    private fun assertTipSurface(expected: Color) {
        val pixels = compose.onNodeWithTag("global-tip").captureToImage().toPixelMap()
        assertEquals(expected.toArgb(), pixels[8, pixels.height / 2].toArgb())
    }

    // 保存屏幕合成后的画面，真实检查提示是否位于原生弹窗和键盘上层。
    private fun saveScreen(name: String) {
        val file = File(compose.activity.filesDir, "qa/$name.png")
        file.parentFile?.mkdirs()
        // 等待原生窗口的系统转场落定，避免截图停在半透明中间帧。
        SystemClock.sleep(300)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
