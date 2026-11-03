package com.kimo.music

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.PlaybackService
import com.kimo.music.data.DemoCatalog
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnlineUiTest {
    @get:Rule(order = 0) val localLibrary = LocalLibraryFixture(seedTracks = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private var libraryModel: MusicViewModel? = null

    // 导航栏缩小后仍保留完整触控区域与三个可用入口。
    @Test fun bottomNavigationIsCompactAndUsable() {
        val density = compose.activity.resources.displayMetrics.density
        val bar = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("bottom-nav-icon-Home", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(60f * density, bar.height, 1f)
        assertEquals(20f * density, icon.width, 1f)
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("我的音乐").assertIsDisplayed()
        compose.onNodeWithText("发现", useUnmergedTree = true).performClick()
        compose.onNodeWithText("歌单广场").assertIsDisplayed()
    }

    // 手动同步只保留在喜欢的歌曲右侧，其他入口依赖后台同步。
    @Test fun libraryShowsOnlyFavoritesSyncAction() {
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText("登录后同步").assertCountEquals(1)
        compose.onNodeWithText("立即同步").assertDoesNotExist()
        compose.onNodeWithText("账号与同步").assertDoesNotExist()
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onAllNodesWithText("登录后同步").assertCountEquals(0)
    }

    // 检查真实本机收藏计数、歌曲菜单及从收藏列表启动播放，不修改任何云端账号。
    @Test fun favoriteLibraryKeepsActualTracksAndPlayback() {
        val model = openLibraryWithDemoFavorites()
        compose.onNodeWithText("我的音乐").assertIsDisplayed()
        compose.onNodeWithContentDescription("我喜欢").assertIsDisplayed()
        saveImage("library", compose.onRoot())
        val initialCount = model.favoriteTracks.size
        compose.onNodeWithText("$initialCount 首").assertIsDisplayed()
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithContentDescription("歌曲操作：晚风经过").performScrollTo().performClick()
        compose.onNodeWithText("取消喜欢").performClick()
        compose.runOnIdle { assertFalse("evening-wind" in model.favorites) }
        compose.onNodeWithContentDescription("我喜欢").performClick()
        compose.onNodeWithText("${initialCount - 1} 首").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("正在播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.waitUntil(15_000) { model.playback.track?.id == "mountain-letter" && model.playback.playing }
    }

    // 歌曲菜单从底部展示封面、来源和全部操作，取消后仍可再次打开并播放。
    @Test fun trackActionsUseBottomSheetAndKeepPlayback() {
        val model = openLibraryWithDemoFavorites()
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithContentDescription("歌曲操作：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("来自「本地音乐」").assertIsDisplayed()
        compose.onNodeWithText("播放这首歌").assertIsDisplayed()
        compose.onNodeWithText("加入播放列表").assertIsDisplayed()
        compose.onNodeWithText("取消喜欢").assertIsDisplayed()
        saveImage("library-track-sheet", compose.onNode(isDialog()))
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("播放这首歌").assertDoesNotExist()
        compose.onNodeWithContentDescription("歌曲操作：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("播放这首歌").performClick()
        compose.waitUntil(15_000) { model.playback.track?.id == "mountain-letter" && model.playback.playing }
        compose.onNodeWithText("正在播放").assertDoesNotExist()
    }

    // 新总览只展示可用入口，本地音乐无需登录，云歌单入口明确进入登录流程。
    @Test fun libraryShortcutsOpenOfflineContentAndGateCloudPlaylists() {
        val model = openLibraryWithDemoFavorites()
        compose.onNodeWithContentDescription("音乐云盘").assertDoesNotExist()
        compose.runOnIdle { assertNull(model.online.detail) }
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithText("导入本地音频").assertIsDisplayed()
        compose.onNodeWithContentDescription("我喜欢").performClick()
        compose.onNodeWithContentDescription("我的歌单").performClick()
        compose.onNodeWithText("登录后查看你的歌单").assertIsDisplayed()
        compose.onNodeWithContentDescription("账号信息").performClick()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithContentDescription("搜索音乐").performClick()
        compose.onNodeWithContentDescription("搜索酷狗曲库").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("离线演示").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回设置").performClick()
    }

    // 测试只添加应用自有的离线演示曲，实际通过现有收藏逻辑更新偏好。
    private fun openLibraryWithDemoFavorites(): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]
            libraryModel = model
            DemoCatalog.tracks.filter { it.id !in model.favorites }.forEach(model::toggleFavorite)
        }
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.waitUntil(30_000) { !model.account.state.restoring && !model.online.cloud.loading }
        return model
    }

    // 关闭测试媒体服务；原始本机曲库由外层快照规则完整恢复。
    @After fun restoreLibraryFixtures() {
        if (libraryModel == null) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }

    // 全屏登录页核对协议勾选、手机号输入和真实二维码，不发送短信或代替用户授权。
    @Test fun phoneAndQrLoginRemainAvailable() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("账号信息").performClick()
        compose.waitUntil(10_000) { !model.account.state.restoring }
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.onNode(hasText("Kimo") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("手机号").assertIsDisplayed()
        compose.onNodeWithText("获取验证码").assertIsDisplayed()
        compose.onNodeWithContentDescription("同意酷狗协议").assertIsOff()
        compose.onNodeWithText("《用户服务协议》").assertHasClickAction()
        compose.onNodeWithText("《隐私政策》").assertHasClickAction()
        compose.onNodeWithText("获取验证码").performClick()
        compose.onNodeWithText("请先阅读并同意酷狗用户服务协议和隐私政策").assertIsDisplayed()
        compose.runOnIdle { assertFalse(model.account.state.sending) }
        compose.onNodeWithContentDescription("手机号输入").performTextInput("13800138000abc")
        compose.onNodeWithContentDescription("手机号输入").assertTextEquals("13800138000")
        compose.onNodeWithContentDescription("验证码输入").performTextInput("123456789")
        compose.onNodeWithContentDescription("验证码输入").assertTextEquals("12345678")
        compose.onNodeWithText("登录").performScrollTo().performClick()
        compose.onNodeWithText("请先阅读并同意酷狗用户服务协议和隐私政策").assertIsDisplayed()
        compose.onNodeWithText("扫码登录").performScrollTo().performClick()
        compose.onNodeWithText("同意协议后显示二维码").assertIsDisplayed()
        compose.onNodeWithContentDescription("同意酷狗协议").performClick().assertIsOn()
        compose.waitUntil(25_000) { compose.onAllNodesWithContentDescription("酷狗登录二维码").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithText("为此刻，选一首。").assertIsDisplayed()
    }

    // 从播放页打开登录后返回，现有歌曲与播放状态继续保留。
    @Test fun closingLoginReturnsToPlayingTrack() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]; libraryModel = model }
        compose.waitUntil(15_000) { model.playback.ready }
        compose.runOnIdle { model.play(DemoCatalog.tracks.first(), DemoCatalog.tracks) }
        compose.waitUntil(15_000) { model.playback.playing }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.runOnIdle { model.loginRequested = true }
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.runOnIdle { assertEquals("evening-wind", model.playback.track?.id); assertTrue(model.playback.playing) }
    }

    // 短屏和大字号下登录、协议与切换入口仍能滚动到可操作区域。
    @Test fun loginContentRemainsReachableOnSmallScreens() {
        compose.onNodeWithContentDescription("账号信息").performClick()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.onNodeWithText("登录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("同意酷狗协议").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("《隐私政策》").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("扫码登录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("手机号输入").performScrollTo().performClick()
        compose.onNodeWithContentDescription("验证码输入").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("登录").performScrollTo().assertIsDisplayed()
    }

    // 真实榜单详情可以读取曲目，返回后保留排行榜标签和滚动上下文。
    @Test fun rankDetailKeepsNavigationAndRealTracks() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithText("发现").performClick()
        compose.onNodeWithText("排行榜").performClick()
        compose.waitUntil(25_000) { model.online.charts.state.items.isNotEmpty() }
        val title = model.online.charts.state.items.first().title
        compose.onNodeWithText(title).performClick()
        compose.waitUntil(25_000) { model.online.detail?.songs?.state?.items?.isNotEmpty() == true }
        compose.onNodeWithText("播放全部").assertIsDisplayed()
        saveImage("online-detail", compose.onRoot())
        compose.onNodeWithContentDescription("返回曲库").performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("首页").performClick()
        compose.waitUntil(25_000) { model.online.playlists.state.items.isNotEmpty() }
        saveImage("online-home", compose.onRoot())
    }

    // 保存设备真实渲染结果，供布局与登录入口人工验收。
    private fun saveImage(name: String, node: SemanticsNodeInteraction) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "$name.png").outputStream().use { output ->
            node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
