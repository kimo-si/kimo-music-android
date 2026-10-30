package com.kimo.music

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kimo.music.playback.PlaybackService
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.SleepTimerRequest
import com.kimo.music.playback.PlaybackCommands
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import java.io.File
import java.util.concurrent.TimeUnit

class PlayerFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 每个流程结束后释放测试服务，其他流程重新连接独立会话。
    @After fun stopPlaybackService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    // 波形动效不改变真实拖动跳转，暂停后进度条的绘制必须停止变化。
    @Test fun animatedProgressSeeksAndSettlesAfterPause() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.waitUntil(15_000) { model.playback.ready }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.waitUntil(15_000) { model.playback.playing }
        seekToFraction(model, 0.4f)
        compose.onNodeWithContentDescription("暂停").performClick()
        compose.waitUntil(10_000) { !model.playback.playing }
        seekToFraction(model, 0.7f)
        compose.mainClock.advanceTimeBy(400)
        val before = compose.onNodeWithContentDescription("播放进度").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(700)
        val after = compose.onNodeWithContentDescription("播放进度").captureToImage().asAndroidBitmap()
        assertTrue("暂停后的波形与光点应保持静止", before.sameAs(after))
    }

    // 通过 Slider 原有的无障碍进度动作验证跳转，等待媒体会话报告实际位置。
    private fun seekToFraction(model: MusicViewModel, fraction: Float) {
        val target = (model.playback.durationMs * fraction).toLong()
        compose.onNodeWithContentDescription("播放进度").performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(target.toFloat()))
        }
        compose.waitUntil(10_000) { model.playback.positionMs in target..target + 1_500 }
    }

    // 真实启动 Media3 播放并暂停，验证完整播放页、收藏和返回入口。
    @Test fun fullPlayerControlsRealAudioAndFavorite() {
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("播放") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("加入我喜欢").performClick()
        compose.onNodeWithContentDescription("取消喜欢").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停").performClick()
        verifyPlayerMenu()
        saveScreenshot("player")
        compose.onNodeWithContentDescription("收起播放页").performClick()
        compose.onNodeWithText("为此刻，选一首。").assertIsDisplayed()
        saveScreenshot("home")
    }

    // 从媒体会话队列切歌后，迷你播放条与完整播放页必须同步。
    @Test fun queueSelectionAndNavigationShareState() {
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithContentDescription("播放列表").performClick()
        compose.onNodeWithText("播放列表 · 3").assertIsDisplayed()
        compose.onNodeWithText("山谷来信").performClick()
        compose.onNodeWithText("山谷来信").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一首").performClick()
        compose.onNodeWithText("午后三点").assertIsDisplayed()
        compose.onNodeWithContentDescription("背景封面：午后三点").assertIsDisplayed()
        saveScreenshot("player-next")
        compose.onNodeWithContentDescription("收起播放页").performClick()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("我的音乐").assertIsDisplayed()
    }

    // 展开过程中立即返回，再次进入仍可操作，转场不会重建媒体状态或卡住触摸。
    @Test fun playerTransitionCanReverseBeforeExpansionCompletes() {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("打开播放页").performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("player-presentation").assertExists()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(1_500)
            compose.onNodeWithTag("player-presentation").assertDoesNotExist()
            compose.onNodeWithText("为此刻，选一首。").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        verifyPlayerFeedback()
        compose.onNodeWithContentDescription("收起播放页").performClick()
        compose.onNodeWithContentDescription("打开播放页").assertIsDisplayed()
    }

    // 播放页覆盖底层后，服务错误和收藏结果使用的提示通道仍然可见。
    private fun verifyPlayerFeedback() {
        lateinit var model: MusicViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]
            model.notice("播放页反馈验证")
        }
        compose.onNodeWithText("播放页反馈验证").assertIsDisplayed()
        compose.runOnIdle { model.clearMessage() }
    }

    // 播放页覆盖个人页时优先处理系统返回，收起后保留原栏目与滚动位置。
    @Test fun closingPlayerKeepsLibraryTabAndScrollPosition() {
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("离线演示").performClick()
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("正在播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("3 首原创合成演示曲，仅用于离线体验。"))
        compose.onNodeWithText("3 首原创合成演示曲，仅用于离线体验。").assertIsDisplayed()
    }

    // 验证迁移后的菜单入口和音质弹层，防止移除工具栏时漏接功能。
    private fun verifyPlayerMenu() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("更多播放选项").performClick()
        compose.onNodeWithText("下载音频").assertIsDisplayed()
        compose.onNodeWithText("音量").assertIsDisplayed()
        compose.onNodeWithText("音质 · 标准").performClick()
        compose.onNodeWithText("在线音质").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("睡眠定时").assertDoesNotExist()
        compose.onNodeWithContentDescription("更多播放选项").performClick()
        compose.onNodeWithContentDescription("睡眠定时").performClick()
        compose.onNodeWithText("15 分钟后").performClick()
        compose.waitUntil(10_000) { model.playback.sleepTimerRemainingMs > 0 }
        compose.onNodeWithContentDescription("更多播放选项").performClick()
        compose.onNodeWithContentDescription("睡眠定时").performClick()
        compose.onNodeWithText("取消定时").performClick()
        compose.waitUntil(10_000) { model.playback.sleepTimerRemainingMs == 0L }
    }

    // Activity 销毁后仍由媒体服务到时暂停，排除仅在页面内倒计时的假实现。
    @Test fun sleepTimerStopsAudioAfterActivityIsDestroyed() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.waitUntil(15_000) { model.playback.ready }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.waitUntil(15_000) { model.playback.playing }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            compose.runOnIdle { model.setSleepTimer(SleepTimerRequest(4_000)) }
            compose.waitUntil(10_000) { model.playback.sleepTimerRemainingMs > 0 }
            compose.activityRule.scenario.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { assertTrue(controller.isPlaying) }
            android.os.SystemClock.sleep(4_500)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertFalse("定时应停止后台音频", controller.isPlaying)
                assertEquals(0L, controller.sessionExtras.getLong(PlaybackCommands.TIMER_DEADLINE))
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { MediaController.releaseFuture(future) }
        }
    }

    // Activity 退到后台后，通过另一媒体控制器确认音频服务仍在推进真实进度。
    @Test fun audioContinuesAfterActivityStops() {
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("播放") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("暂停").fetchSemanticsNodes().isNotEmpty() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val future = MediaController.Builder(context, SessionToken(context,
            ComponentName(context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            android.os.SystemClock.sleep(1_000)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue("后台媒体服务应保持播放", controller.isPlaying)
                assertTrue("后台音频进度应持续推进", controller.currentPosition > 500)
                controller.pause()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { MediaController.releaseFuture(future) }
        }
    }

    // 将实际 Compose 渲染结果保存到测试应用私有目录，供视觉验收查看。
    private fun saveScreenshot(name: String) {
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "$name.png")
        file.outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
