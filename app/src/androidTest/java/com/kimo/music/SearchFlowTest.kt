package com.kimo.music

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.CatalogKind
import com.kimo.music.data.LibraryStore
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.PlaybackService
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 首页真实内容滚动时，顶部标题、搜索入口及栏目均保持原坐标。
    @Test fun homeHeaderStaysFixedWhenFeedScrolls() {
        val heading = compose.onNodeWithText("为此刻，选一首。")
        val original = heading.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        heading.assertIsDisplayed()
        assertEquals(original, heading.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("搜索音乐").assertIsDisplayed()
        compose.onNodeWithText("新歌").assertIsDisplayed()
    }

    // 历史、三列灵感和实时热搜页面均可见，清空仅删除测试快照内的搜索历史。
    @Test fun searchLandingKeepsActualHistoryAndClearAction() {
        val model = openSearch()
        compose.runOnIdle { model.rememberSearch("晴天"); model.rememberSearch("孙子涵") }
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.onNodeWithText("听点不一样").assertIsDisplayed()
        compose.onNodeWithText("日落漫游").assertIsDisplayed()
        compose.onNodeWithText("安静独处").assertIsDisplayed()
        compose.onNodeWithText("热门搜索").assertIsDisplayed()
        compose.waitUntil(30_000) { model.online.hotSearches.state.items.isNotEmpty() }
        compose.onNodeWithContentDescription("清空最近搜索").performClick()
        compose.onNodeWithText("暂无搜索记录").assertIsDisplayed()
        compose.waitUntil(10_000) { LibraryStore(ApplicationProvider.getApplicationContext()).searchHistory.isEmpty() }
        compose.onNodeWithContentDescription("返回上一页").performClick()
        compose.onNodeWithText("为此刻，选一首。").assertIsDisplayed()
    }

    // 搜索真实歌曲后快速切换分类，旧请求不能覆盖最后选择的专辑结果。
    @Test fun fullSearchKeepsSongArtistAlbumAndPlaylistResults() {
        val model = openSearch()
        compose.onNodeWithContentDescription("搜索关键词").performTextInput("周杰伦")
        compose.onNodeWithContentDescription("搜索酷狗曲库").performClick()
        compose.waitUntil(30_000) { !model.online.searchLoading && model.online.search.tracks.isNotEmpty() }
        compose.onNodeWithText("歌手").performClick()
        compose.onNodeWithText("专辑").performClick()
        compose.waitUntil(30_000) { !model.online.searchLoading && model.online.search.entries.isNotEmpty() }
        compose.runOnIdle { assertTrue(model.online.search.entries.all { it.kind == CatalogKind.Album }) }
        compose.onNodeWithText("歌单").performClick()
        compose.waitUntil(30_000) { !model.online.searchLoading && model.online.search.entries.isNotEmpty() }
        compose.runOnIdle { assertTrue(model.online.search.entries.all { it.kind == CatalogKind.Playlist }) }
        compose.waitUntil(10_000) { "周杰伦" in LibraryStore(ApplicationProvider.getApplicationContext()).searchHistory }
    }

    // 搜索从首页入口打开，所有用例共用实际页面与同一媒体模型。
    private fun openSearch(): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.onNodeWithContentDescription("搜索音乐").performClick()
        compose.onNodeWithContentDescription("搜索关键词").assertIsDisplayed()
        return model
    }

    // 仅停止测试媒体服务，本机记录由外层快照恢复，账号容器不参与测试。
    @After fun stopPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
