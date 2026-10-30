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
    @get:Rule(order = 0) val localLibrary = LocalLibraryFixture()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private var libraryModel: MusicViewModel? = null

    // 检查真实本机收藏计数、歌曲菜单及从收藏列表启动播放，不修改任何云端账号。
    @Test fun favoriteLibraryKeepsActualTracksAndPlayback() {
        val model = openLibraryWithDemoFavorites()
        compose.onNodeWithText("我的音乐").assertIsDisplayed()
        compose.onNodeWithContentDescription("查看我喜欢").assertIsDisplayed()
        saveImage("library", compose.onRoot())
        val initialCount = model.favoriteTracks.size
        compose.onNodeWithText("$initialCount 首珍藏").assertIsDisplayed()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("离线演示").performClick()
        compose.onNodeWithContentDescription("歌曲操作：晚风经过").performScrollTo().performClick()
        compose.onNodeWithText("取消喜欢").performClick()
        compose.runOnIdle { assertFalse("evening-wind" in model.favorites) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("${initialCount - 1} 首珍藏").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("正在播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.waitUntil(15_000) { model.playback.track.id == "mountain-letter" && model.playback.playing }
    }

    // 歌曲菜单从底部展示封面、来源和全部操作，取消后仍可再次打开并播放。
    @Test fun trackActionsUseBottomSheetAndKeepPlayback() {
        val model = openLibraryWithDemoFavorites()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("离线演示").performClick()
        compose.onNodeWithContentDescription("歌曲操作：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("来自「离线演示」").assertIsDisplayed()
        compose.onNodeWithText("播放这首歌").assertIsDisplayed()
        compose.onNodeWithText("加入播放列表").assertIsDisplayed()
        compose.onNodeWithText("取消喜欢").assertIsDisplayed()
        saveImage("library-track-sheet", compose.onNode(isDialog()))
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("播放这首歌").assertDoesNotExist()
        compose.onNodeWithContentDescription("歌曲操作：山谷来信").performScrollTo().performClick()
        compose.onNodeWithText("播放这首歌").performClick()
        compose.waitUntil(15_000) { model.playback.track.id == "mountain-letter" && model.playback.playing }
        compose.onNodeWithText("正在播放").assertDoesNotExist()
    }

    // 云盘只显示占位反馈，其他快捷入口、搜索与设置仍保留真实导航。
    @Test fun libraryShortcutsKeepCloudAsPlaceholder() {
        val model = openLibraryWithDemoFavorites()
        compose.onNodeWithContentDescription("音乐云盘").performClick()
        compose.onNodeWithText("音乐云盘暂未开放").assertIsDisplayed()
        compose.runOnIdle { assertNull(model.online.detail) }
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithText("导入本地音频").assertIsDisplayed()
        compose.onNodeWithContentDescription("云歌单").performClick()
        compose.onNodeWithText("新建云歌单").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索音乐").performClick()
        compose.onNodeWithContentDescription("搜索酷狗曲库").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("离线演示").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("3 首原创合成演示曲，仅用于离线体验。"))
        compose.onNodeWithText("3 首原创合成演示曲，仅用于离线体验。").assertIsDisplayed()
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

    // 实际呈现两种登录方式与真实二维码，但不发送短信或代替用户授权。
    @Test fun phoneAndQrLoginRemainAvailable() {
        compose.onNodeWithContentDescription("账号与同步").performClick()
        compose.onNodeWithText("手机号").assertIsDisplayed()
        compose.onNodeWithText("获取验证码").assertIsDisplayed()
        compose.onNodeWithText("登录并同步").assertIsDisplayed()
        compose.onNodeWithText("扫码登录").performClick()
        compose.waitUntil(25_000) { compose.onAllNodesWithContentDescription("酷狗登录二维码").fetchSemanticsNodes().isNotEmpty() }
        saveImage("account", compose.onNode(isDialog()))
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("为此刻，选一首。").assertIsDisplayed()
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
