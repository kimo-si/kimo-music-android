package com.kimo.music

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
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
import com.kimo.music.core.VIP_LOGIN_REQUIRED_MESSAGE
import com.kimo.music.data.Track
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.LibraryStore
import com.kimo.music.ui.sampleVinylTint
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import java.io.File
import java.util.concurrent.TimeUnit

class PlayerFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true, seedTracks = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 真实权益与媒体服务共同回归：未登录播放会员歌应显示登录提示，不能退化成系统异常。
    @Test fun guestVipPlaybackShowsThemedLoginTipThroughMediaSession() {
        lateinit var model: MusicViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]
            assertNull("本用例仅在未登录的独立测试设备运行", model.account.credentials)
            val song = Track("b3a52a7a958bf0aed0ebfba2e9a818b7", "晴天", "周杰伦", "叶惠美", "", 269_000,
                hash = "B3A52A7A958BF0AED0EBFBA2E9A818B7", albumId = 966846, mixSongId = 32100650)
            model.play(song, listOf(song))
        }
        compose.waitUntil(30_000) { model.loginRequested && model.message != null }
        compose.onNodeWithText(VIP_LOGIN_REQUIRED_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag("global-tip").assertIsDisplayed()
        compose.onNodeWithText("系统服务器异常请稍后再试").assertDoesNotExist()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.runOnIdle { assertFalse(model.playback.playing) }
    }

    // 由测试主动选择测试包中的本地音频，正式应用本身保持空队列启动。
    @Before fun prepareTestPlayback() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready }
        compose.runOnIdle { model.play(DemoCatalog.tracks.first(), DemoCatalog.tracks) }
        compose.waitUntil(15_000) { model.playback.track?.id == DemoCatalog.tracks.first().id && model.playback.playing }
        compose.runOnIdle { model.togglePlaying() }
        compose.waitUntil(10_000) { !model.playback.playing }
    }

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

    // 播放菜单展示当前歌曲及真实状态，关闭后不改变播放页。
    @Test fun playerOptionsKeepTrackAndPlaybackActions() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithContentDescription("更多播放选项").performClick()
        compose.onNode(hasText("晚风经过") and hasAnyAncestor(hasTestTag("player-options-sheet"))).assertIsDisplayed()
        compose.onNodeWithContentDescription("加入云歌单").performScrollTo().assertIsNotEnabled()
        saveOptionsPreview()
        val before = model.playback.repeatMode
        compose.onNodeWithContentDescription("播放模式").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.playback.repeatMode != before }
        compose.onNodeWithContentDescription("关闭播放选项").performScrollTo().performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
    }

    // 测试截图保存在应用缓存的测试子目录，不写入用户曲库或偏好。
    private fun saveOptionsPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.cacheDir, "kimo-test-previews").apply { mkdirs() }
        File(directory, "player-options-preview.png").outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
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

    // 暂停后的唱片画面保持静止，切歌后封面必须同步更新。
    @Test fun vinylStaysStillWhenPausedAndFollowsTrack() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithContentDescription("唱片封面：晚风经过").assertIsDisplayed()
        compose.waitUntil(15_000) { model.playback.ready }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.waitUntil(15_000) { model.playback.playing }
        compose.onNodeWithContentDescription("暂停").performClick()
        compose.waitUntil(10_000) { !model.playback.playing }
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        assertPausedVinylFrames()
        compose.onNodeWithContentDescription("下一首").performClick()
        compose.waitUntil(10_000) { model.playback.track?.title == "山谷来信" }
        compose.onNodeWithContentDescription("唱片封面：山谷来信").assertIsDisplayed()
    }

    // 使用测试时钟验证暂停后唱片画面不会继续变化。
    private fun assertPausedVinylFrames() {
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(600)
            val before = compose.onNodeWithTag("vinyl-stage").captureToImage().asAndroidBitmap()
            compose.mainClock.advanceTimeBy(2_000)
            val after = compose.onNodeWithTag("vinyl-stage").captureToImage().asAndroidBitmap()
            assertTrue("暂停后的唱片应保持静止", before.sameAs(after))
        } finally { compose.mainClock.autoAdvance = true }
    }

    // 横屏唱片与歌曲信息分居两侧，控制按钮仍在屏幕可见区域。
    @Test fun landscapeKeepsVinylAndControlsVisible() {
        val previous = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.onNodeWithContentDescription("打开播放页").performClick()
            val cover = compose.onNodeWithContentDescription("唱片封面：晚风经过").assertIsDisplayed()
            val title = compose.onNodeWithText("晚风经过").assertIsDisplayed()
            assertTrue(cover.fetchSemanticsNode().boundsInRoot.right < title.fetchSemanticsNode().boundsInRoot.left)
            compose.onNodeWithContentDescription("播放进度").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放列表").assertIsDisplayed()
            saveScreenshot("player-vinyl-landscape")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
        }
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
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("正在播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").assertIsDisplayed()
    }

    // 验证迁移后的菜单入口和音质弹层，防止移除工具栏时漏接功能。
    private fun verifyPlayerMenu() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("更多播放选项").performClick()
        compose.onNodeWithText("下载音频").assertIsDisplayed()
        compose.onNodeWithText("音量").assertIsDisplayed()
        compose.onNodeWithText("音质").performClick()
        compose.onNodeWithText("在线音质").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭在线音质").performClick()
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

class EmptyPlayerFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 空会话不预填歌曲或播放条，播放队列也显示真实空状态。
    @Test fun emptySessionShowsNoPretendSong() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready }
        compose.runOnIdle { assertNull(model.playback.track); assertTrue(model.playback.queue.isEmpty()) }
        compose.onNodeWithContentDescription("打开播放页").assertDoesNotExist()
        compose.onNodeWithText("晚风经过").assertDoesNotExist()
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithContentDescription("查看当前播放队列").performClick()
        compose.onNodeWithText("播放列表 · 0").assertIsDisplayed()
        compose.onNodeWithText("播放列表为空").assertIsDisplayed()
    }

    // 真实选歌后显示播放条，暂停保留，清空会话后再次隐藏。
    @Test fun selectedTrackRemainsVisibleWhenPausedAndDisappearsWhenCleared() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready }
        val track = DemoCatalog.tracks.first()
        compose.runOnIdle { model.play(track, listOf(track)) }
        compose.waitUntil(15_000) { model.playback.track?.id == track.id && model.playback.playing }
        compose.onNodeWithContentDescription("打开播放页").assertIsDisplayed()
        compose.runOnIdle { model.togglePlaying() }
        compose.waitUntil(10_000) { !model.playback.playing }
        compose.onNodeWithContentDescription("打开播放页").assertIsDisplayed()
        clearMediaQueue()
        compose.waitUntil(10_000) { model.playback.track == null }
        compose.onNodeWithContentDescription("打开播放页").assertDoesNotExist()
    }

    // 通过真实媒体控制器清空队列，验证界面不依赖测试直接改写状态。
    private fun clearMediaQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        try { compose.runOnIdle { controller.clearMediaItems() } }
        finally { compose.runOnIdle { MediaController.releaseFuture(future) } }
    }

    // 结束测试会话，本机曲库由外层快照恢复。
    @After fun stopPlaybackService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}

class LegacyDemoFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true, seedLegacyDemo = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 旧版本留下的演示收藏与历史不会显示成真实歌曲，也不会改写原有偏好。
    @Test fun oldDemoIdsAreNotDisplayedOrDeleted() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready }
        compose.runOnIdle { assertTrue(model.favoriteTracks.isEmpty()); assertTrue(model.recentTracks.isEmpty()) }
        compose.onNodeWithContentDescription("打开播放页").assertDoesNotExist()
        compose.onNodeWithText("晚风经过").assertDoesNotExist()
        val store = LibraryStore(ApplicationProvider.getApplicationContext())
        assertTrue("原有偏好应被保留", DemoCatalog.tracks.first().id in store.favorites)
        assertTrue(DemoCatalog.tracks.first().id in store.recentIds)
    }

    // 测试结束释放媒体会话，偏好由外层快照恢复。
    @After fun stopPlaybackService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}

class EarlySelectionFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 在控制器连接回调执行前点歌，连接完成后仍必须播放用户选择的歌曲。
    @Test fun selectionBeforeControllerConnectionIsRetained() {
        val store = ViewModelStore()
        val track = DemoCatalog.tracks.first()
        lateinit var model: MusicViewModel
        try {
            compose.runOnIdle {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(compose.activity.application)
                model = ViewModelProvider(store, factory)[MusicViewModel::class.java]
                assertFalse("测试必须在媒体会话连接前发起", model.playback.ready)
                model.play(track, listOf(track))
            }
            compose.waitUntil(15_000) { model.playback.track?.id == track.id && model.playback.playing }
        } finally { compose.runOnIdle { store.clear() } }
    }

    // 不保留测试媒体服务，本机偏好由外层快照恢复。
    @After fun stopPlaybackService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}

class VinylColorTest {
    // 提取颜色应区分不同封面，纯黑图片交由主题颜色兜底。
    @Test fun coverTintUsesVisiblePixelsAndRejectsBlack() {
        val bitmap = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(185, 70, 45))
            val warm = sampleVinylTint(bitmap)
            bitmap.eraseColor(android.graphics.Color.rgb(35, 85, 190))
            val cool = sampleVinylTint(bitmap)
            assertNotNull(warm)
            assertNotNull(cool)
            assertNotEquals(warm, cool)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            assertNull(sampleVinylTint(bitmap))
        } finally { bitmap.recycle() }
    }
}
