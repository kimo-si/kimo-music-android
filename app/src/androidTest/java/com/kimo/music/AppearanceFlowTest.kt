package com.kimo.music

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.data.AppearanceMode
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.LibraryStore
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.PlaybackService
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AppearanceFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(seedTracks = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 深浅模式立即改变实际画面，并在 Activity 重建后保留设置与本机偏好。
    @Test fun appearanceChangesImmediatelyAndSurvivesRecreation() {
        val model = openSettings()
        saveImage("settings-light", compose.onNode(isDialog()))
        compose.onNodeWithText("外观皮肤").performClick()
        compose.onNodeWithText("午夜深色").performClick().assertIsSelected()
        assertBackground(Color(0xFF181A19))
        assertEquals(AppearanceMode.Dark, LibraryStore(compose.activity).appearance)
        saveImage("appearance-dark", compose.onNode(isDialog()))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("午夜深色").assertIsSelected()
        assertEquals(AppearanceMode.Dark, model.appearance)
        compose.onNodeWithContentDescription("返回外观皮肤").performClick()
        saveImage("settings-dark", compose.onNode(isDialog()))
        compose.onNodeWithContentDescription("返回设置").performClick()
        assertBackground(Color(0xFF181A19), compose.onRoot())
        saveImage("library-dark", compose.onRoot())
        compose.onNodeWithContentDescription("搜索音乐").performClick()
        assertBackground(Color(0xFF181A19), compose.onRoot())
        saveImage("search-dark", compose.onRoot())
    }

    // 更换皮肤不会打断真实播放，音质弹层和登录页也跟随当前主题。
    @Test fun darkAppearanceKeepsPlaybackAndSecondaryPagesUsable() {
        val model = openSettings()
        compose.runOnIdle { model.play(DemoCatalog.tracks.first(), DemoCatalog.tracks) }
        compose.waitUntil(15_000) { model.playback.playing }
        compose.onNodeWithText("外观皮肤").performClick()
        compose.onNodeWithText("午夜深色").performClick()
        compose.onNodeWithContentDescription("返回外观皮肤").performClick()
        compose.onNodeWithText("在线音质").performClick()
        compose.onNodeWithText("根据当前账号权益播放，必要时降低音质").assertIsDisplayed()
        assertBackground(Color(0xFF181A19), compose.onNodeWithTag("action-sheet-content"))
        saveImage("quality-dark", compose.onNode(isDialog()))
        compose.onNodeWithContentDescription("关闭在线音质").performClick()
        compose.runOnIdle {
            assertEquals(DemoCatalog.tracks.first().id, model.playback.track?.id)
            assertTrue(model.playback.playing)
        }
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("账号信息").performClick()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        assertBackground(Color(0xFF181A19))
        saveImage("login-dark", compose.onNode(isDialog()))
    }

    // 跟随系统时响应设备配置变化，显式浅色选择则不会被系统夜间模式覆盖。
    @Test fun systemAppearanceTracksDeviceNightMode() {
        val manager = compose.activity.getSystemService(UiModeManager::class.java)
        val previous = manager.nightMode
        try {
            setNightMode("no")
            openSettings()
            compose.onNodeWithText("外观皮肤").performClick()
            compose.onNodeWithText("跟随系统").performClick().assertIsSelected()
            assertBackground(Color(0xFFF5F1E8))
            setNightMode("yes")
            compose.waitForIdle()
            assertBackground(Color(0xFF181A19))
            compose.onNodeWithText("暖纸浅色").performClick().assertIsSelected()
            assertBackground(Color(0xFFF5F1E8))
            saveImage("appearance-light", compose.onNode(isDialog()))
        } finally {
            setNightMode(when (previous) {
                UiModeManager.MODE_NIGHT_YES -> "yes"
                UiModeManager.MODE_NIGHT_NO -> "no"
                else -> "auto"
            }, awaitUi = false)
        }
    }

    // 从真实个人页进入设置，并固定起始外观以隔离设备原有偏好。
    private fun openSettings(): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]
            model.changeAppearance(AppearanceMode.Light)
        }
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        return model
    }

    // 读取实际渲染像素，验证主题确实改变界面而不只改变选中标签。
    private fun assertBackground(expected: Color, node: SemanticsNodeInteraction = compose.onNode(isDialog())) {
        compose.waitForIdle()
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(expected.toArgb(), pixels[0, pixels.height / 2].toArgb())
    }

    // 仅对独立模拟器变更系统夜间模式，读取命令结果以等待配置提交。
    private fun setNightMode(mode: String, awaitUi: Boolean = true) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd uimode night $mode").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        // 清理阶段随即销毁 Activity，不等待配置重建后的下一帧，避免系统自动模式阻塞测试宿主。
        if (awaitUi) compose.waitForIdle()
    }

    // 保留实际设备截图用于检查主题、文字对比度和布局。
    private fun saveImage(name: String, node: SemanticsNodeInteraction) {
        val file = File(compose.activity.filesDir, "qa/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    // 测试结束停止独立媒体服务，本机曲库与外观由快照规则恢复。
    @After fun stopPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
