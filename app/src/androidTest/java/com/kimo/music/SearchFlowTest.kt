package com.kimo.music

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.CatalogKind
import com.kimo.music.core.SearchSuggestion
import com.kimo.music.core.MembershipStatus
import com.kimo.music.data.LibraryStore
import com.kimo.music.data.AppearanceMode
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.PlaybackService
import com.kimo.music.ui.rememberKeywordSuggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SearchFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 真实搜索中的会员歌显示标识，免费版本不误标；切换深色仍可辨认并保持搜索结果。
    @Test fun vipSongsKeepTheirBadgesInLightAndDarkSearch() {
        val model = openSearch()
        compose.runOnIdle { model.changeAppearance(AppearanceMode.Light) }
        compose.onNodeWithContentDescription("搜索关键词").performTextInput("晴天")
        compose.onNodeWithContentDescription("搜索酷狗曲库").performClick()
        compose.waitUntil(30_000) { !model.online.searchLoading && model.online.search.tracks.isNotEmpty() }
        compose.waitUntil(30_000) {
            val states = model.online.search.tracks.map(model.online.memberships::status)
            MembershipStatus.Vip in states && MembershipStatus.NotRequired in states
        }
        val vip = model.online.search.tracks.first { model.online.memberships.status(it) == MembershipStatus.Vip }
        val free = model.online.search.tracks.first { model.online.memberships.status(it) == MembershipStatus.NotRequired }
        compose.onNodeWithTag("vip-badge-${vip.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("vip-badge-${free.id}", useUnmergedTree = true).assertDoesNotExist()
        saveSearchImage("vip-search-light")
        compose.runOnIdle { model.changeAppearance(AppearanceMode.Dark) }
        compose.onNodeWithTag("vip-badge-${vip.id}", useUnmergedTree = true).assertIsDisplayed()
        saveSearchImage("vip-search-dark")
    }

    // 保存设备实际画面，用于核对会员标识和歌曲标题在两套主题下的排列。
    private fun saveSearchImage(name: String) {
        val file = File(compose.activity.filesDir, "qa/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

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

    // 历史、真实推荐歌单和实时热搜可见，清空仅删除测试快照内的搜索历史。
    @Test fun searchLandingKeepsActualHistoryAndClearAction() {
        val model = openSearch()
        compose.runOnIdle { model.rememberSearch("晴天"); model.rememberSearch("孙子涵") }
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.waitUntil(30_000) { model.online.playlists.state.items.isNotEmpty() }
        compose.onNodeWithText("听点不一样").assertIsDisplayed()
        compose.onNodeWithText("日落漫游").assertDoesNotExist()
        compose.onNodeWithText("安静独处").assertDoesNotExist()
        compose.onNodeWithText("热门搜索").assertIsDisplayed()
        compose.waitUntil(30_000) { model.online.hotSearches.state.items.isNotEmpty() }
        val loadedArtwork = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "封面已加载")
        compose.waitUntil(45_000) { compose.onAllNodes(loadedArtwork).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(loadedArtwork).onFirst().assertIsDisplayed()
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

    // 真实联想可点击搜索；重新编辑、清空时恢复正确的面板和搜索历史。
    @Test fun keywordSuggestionsSearchAndReturnAfterEditing() {
        val model = openSearch()
        compose.runOnIdle { model.clearSearchHistory() }
        val input = compose.onNodeWithContentDescription("搜索关键词")
        input.performTextInput("周杰伦")
        val matcher = hasContentDescription("搜索建议：", substring = true)
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size >= 2 }
        val description = compose.onAllNodes(matcher).fetchSemanticsNodes()[1].config[SemanticsProperties.ContentDescription].first()
        val selected = description.removePrefix("搜索建议：")
        compose.runOnIdle { assertTrue("输入联想不应写入历史", model.searchHistory.isEmpty()) }
        compose.onNodeWithContentDescription(description).performClick()
        input.assertTextEquals(selected)
        compose.onAllNodes(matcher).assertCountEquals(0)
        compose.waitUntil(30_000) { !model.online.searchLoading && model.online.search.tracks.isNotEmpty() }
        compose.runOnIdle { assertEquals(selected, model.searchHistory.first()) }
        input.performClick().performTextReplacement("林俊杰")
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("清空搜索词").performClick()
        compose.onAllNodes(matcher).assertCountEquals(0)
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
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

class SearchSuggestionStateTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 即使上游忽略取消，旧词的延迟响应也不能覆盖新词或重新打开面板。
    @Test fun obsoleteSuggestionResponseNeverReplacesLatestInput() {
        val query = mutableStateOf("")
        val editing = mutableStateOf(true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        compose.runOnUiThread { compose.activity.setContent {
            val suggestions = rememberKeywordSuggestions(query.value, editing.value) { keyword ->
                if (keyword == "旧词") {
                    started.complete(Unit)
                    try { release.await() } catch (_: CancellationException) { withContext(NonCancellable) { release.await() } }
                    listOf(SearchSuggestion("旧结果"))
                } else listOf(SearchSuggestion("新结果"))
            }
            Column { suggestions.forEach { Text(it.keyword) } }
        } }
        compose.runOnIdle { query.value = "旧词" }
        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle { query.value = "新词" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("新结果").fetchSemanticsNodes().isNotEmpty() }
        release.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("新结果").assertExists()
        compose.onNodeWithText("旧结果").assertDoesNotExist()
        compose.runOnIdle { editing.value = false }
        compose.onNodeWithText("新结果").assertDoesNotExist()
    }

    // 使用正式页面作为测试宿主，结束后释放它启动的媒体服务。
    @After fun stopPlayback() {
        compose.activity.stopService(Intent(compose.activity, PlaybackService::class.java))
    }
}
