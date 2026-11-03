package com.kimo.music

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.data.AppearanceMode
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.LibraryStore
import com.kimo.music.playback.MusicViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class LibraryFixedHeaderFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val manyTracks = object : ExternalResource() {
        // 测试曲目复用测试音频，独立曲目编号仅用于验证长列表滚动，不进入正式包。
        override fun before() {
            val store = LibraryStore(ApplicationProvider.getApplicationContext<Context>())
            val tracks = (0 until 90).map { DemoCatalog.tracks.first().copy(id = "fixed-header-$it", title = "固定区域测试 $it") }
            store.saveLocalTracks(tracks); store.saveFavorites(tracks.map { it.id }.toSet())
        }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private data class HeaderBounds(val all: Rect, val account: Rect, val shortcuts: Rect, val title: Rect)

    // 实际手势和深滚动只改变歌曲，账号卡、四个入口、标题及列表控制区均保持原坐标。
    @Test fun localListScrollKeepsEntireHeaderStillAndCardCompact() {
        openLocalLibrary()
        val density = compose.activity.resources.displayMetrics.density
        val before = bounds("本地音乐")
        assertTrue("账号卡应比旧版明显更矮", before.account.height <= 144 * density)
        assertTrue("四个入口应紧接卡片", before.shortcuts.top - before.account.bottom <= 12 * density)
        compose.onNodeWithTag("library-content-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("library-content-list").performScrollToIndex(75)
        compose.onNodeWithContentDescription("播放歌曲：固定区域测试 75").assertIsDisplayed()
        assertEquals(before, bounds("本地音乐"))
        compose.onNodeWithContentDescription("播放歌曲：固定区域测试 0").assertDoesNotExist()
    }

    // 收藏列表也拥有独立滚动区域，切换栏目后原来的滚动位置仍保留。
    @Test fun favoritesAndLocalKeepIndependentScrollWithoutMovingShortcuts() {
        openLocalLibrary()
        compose.onNodeWithTag("library-content-list").performScrollToIndex(70)
        compose.onNodeWithContentDescription("我喜欢").performClick()
        val before = bounds("喜欢的歌曲")
        compose.onNodeWithTag("library-content-list").performScrollToIndex(65)
        assertEquals(before, bounds("喜欢的歌曲"))
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithContentDescription("播放歌曲：固定区域测试 70").assertIsDisplayed()
    }

    // 横屏并保留迷你播放条时，固定顶部仍给歌曲列表留下可操作空间。
    @Test fun landscapeWithMiniPlayerKeepsHeaderFixedAndListUsable() {
        val model = openLocalLibrary()
        compose.runOnIdle { model.play(model.localTracks.first(), model.localTracks) }
        compose.waitUntil(15_000) { model.playback.playing }
        compose.runOnIdle { model.togglePlaying() }
        val previous = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            val before = bounds("本地音乐")
            val list = compose.onNodeWithTag("library-content-list").fetchSemanticsNode().boundsInRoot
            assertTrue("横屏列表应保留至少一行触控区域", list.height >= 48 * compose.activity.resources.displayMetrics.density)
            compose.onNodeWithTag("library-content-list").performScrollToIndex(60)
            assertEquals(before, bounds("本地音乐"))
            compose.onNodeWithContentDescription("我喜欢").assertIsDisplayed()
            compose.onNodeWithContentDescription("最近播放").assertIsDisplayed()
        } finally { compose.activityRule.scenario.onActivity { it.requestedOrientation = previous } }
    }

    // 页面继续使用生产模型和真实本地文件，避免只验证静态布局样例。
    private fun openLocalLibrary(): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]
            model.changeAppearance(AppearanceMode.Dark)
        }
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("本地音乐").performClick()
        return model
    }

    // 读取实际布局坐标，同时检查四个入口之外的标题和控制区域。
    private fun bounds(title: String): HeaderBounds = HeaderBounds(
        compose.onNodeWithTag("library-fixed-header").fetchSemanticsNode().boundsInRoot,
        compose.onNodeWithTag("library-identity-card").fetchSemanticsNode().boundsInRoot,
        compose.onNodeWithTag("library-shortcuts").fetchSemanticsNode().boundsInRoot,
        compose.onAllNodesWithText(title).onLast().fetchSemanticsNode().boundsInRoot,
    )
}
