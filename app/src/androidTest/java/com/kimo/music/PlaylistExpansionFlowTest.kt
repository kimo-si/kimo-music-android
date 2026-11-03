package com.kimo.music

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.*
import com.kimo.music.data.Track
import com.kimo.music.ui.*
import com.kimo.music.ui.theme.KimoTheme
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlaylistExpansionFlowTest {
    @get:Rule(order = 0) val local = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 生产歌单行原位展开，真实分页加载更多后保留前页曲目，切换另一歌单不会混入旧列表。
    @Test fun expandingPlaylistLoadsPagesAndRoutesSongAndWholePlaylistActions() {
        val fixture = PlaylistExpansionFixture()
        val first = CatalogEntry("playlist-a", "清晨歌单", CatalogKind.CloudPlaylist, count = 31, listId = 1)
        val second = first.copy(id = "playlist-b", title = "夜晚歌单", listId = 2)
        var played: Track? = null
        var whole: CatalogEntry? = null
        try {
            showPlaylists(fixture, listOf(first, second), { played = it }, { whole = it })
            compose.onNodeWithContentDescription("歌单：清晨歌单").performClick()
            compose.waitUntil(10_000) { fixture.library.knownTracks.size == 30 }
            compose.onNodeWithContentDescription("播放歌曲：曲目1-0").performScrollTo().performClick()
            assertEquals("曲目1-0", played?.title)
            compose.onNodeWithText("播放整个歌单").performScrollTo().performClick()
            assertEquals(first, whole)
            compose.onNode(hasScrollToIndexAction()).performScrollToKey("playlist-footer-playlist-a")
            compose.onNodeWithText("加载更多").performClick()
            compose.waitUntil(10_000) { fixture.library.knownTracks.size == 31 }
            compose.onNode(hasScrollToIndexAction()).performScrollToKey("playlist-playlist-b")
            compose.onNodeWithContentDescription("歌单：夜晚歌单").performClick()
            compose.waitUntil(10_000) { fixture.library.knownTracks.any { it.title == "曲目2-0" } }
            compose.onNodeWithContentDescription("播放歌曲：曲目2-0").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("歌单：夜晚歌单").performScrollTo().performClick()
            compose.onNodeWithText("播放整个歌单").assertDoesNotExist()
            assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), fixture.requests)
        } finally { fixture.close() }
    }

    // 读取失败可原位重试，换账号后迟到的数据不能登记为新账号歌曲。
    @Test fun failedExpansionRetriesAndAccountSwitchRejectsLateRows() = runBlocking {
        val fixture = PlaylistExpansionFixture()
        val entry = CatalogEntry("retry-list", "重试歌单", CatalogKind.CloudPlaylist, listId = 2)
        try {
            fixture.fail = true
            val feed = withContext(Dispatchers.Main) { fixture.library.playlistSongs(entry).also { it.load() } }
            fixture.await { feed.state.error != null && !feed.state.loading }
            fixture.fail = false
            withContext(Dispatchers.Main) { feed.load() }
            fixture.await { feed.state.items.size == 1 }
            fixture.gate = CompletableDeferred()
            val late = withContext(Dispatchers.Main) { fixture.library.playlistSongs(entry.copy(listId = 3)).also { it.load() } }
            fixture.await { fixture.requests.any { it.first == 3 } }
            withContext(Dispatchers.Main) { fixture.owner = "another-owner" }
            fixture.gate!!.complete(Unit)
            fixture.await { !late.state.loading }
            withContext(Dispatchers.Main) {
                assertTrue(late.state.items.isEmpty())
                assertFalse(fixture.library.knownTracks.any { it.title == "曲目3-0" })
                feed.reset(); late.reset()
            }
        } finally { fixture.close() }
    }

    // 界面只替换账号与网络边界，歌单行、分页状态和歌曲操作全部使用生产组件。
    private fun showPlaylists(fixture: PlaylistExpansionFixture, entries: List<CatalogEntry>, onPlay: (Track) -> Unit, onAll: (CatalogEntry) -> Unit) {
        compose.runOnUiThread { compose.activity.setContent {
            var expanded by remember { mutableStateOf<ExpandedLibraryPlaylist?>(null) }
            val selected = expanded
            DisposableEffect(selected) { onDispose { selected?.songs?.reset() } }
            KimoTheme {
                LazyColumn {
                    libraryPlaylistRows(entries, expanded, false, LibraryPlaylistActions({ entry ->
                        expanded = if (expanded?.entry == entry) null else ExpandedLibraryPlaylist(entry,
                            fixture.library.playlistSongs(entry).also { it.load() })
                    }, { song, _ -> onPlay(song) }, {}, onAll))
                }
            }
        } }
    }
}

private class PlaylistExpansionFixture {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "playlist-expansion-${System.nanoTime()}")
    private val isolated = object : ContextWrapper(context) {
        // 测试使用独立缓存目录，不存储任何真实账号曲库。
        override fun getFilesDir(): File = directory
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var owner: String? = "playlist-test-owner"
    var fail = false
    var gate: CompletableDeferred<Unit>? = null
    val requests = mutableListOf<Pair<Int, Int>>()
    val library = OnlineLibrary(KugouApi(CoreTransport { _, parameters, _ -> response(parameters) }) { null },
        scope, CloudCache(isolated), { owner }, {})

    // 分页协议从模拟的远端返回，数据仍经过真实接口映射和共享曲库登记。
    private suspend fun response(parameters: JSONObject): JSONObject {
        val list = parameters.getInt("listid"); val page = parameters.getInt("page")
        requests.add(list to page); gate?.await()
        if (fail) return JSONObject().put("status", 0)
        val rows = JSONArray()
        val start = (page - 1) * 30
        repeat((if (list == 1) 31 - start else 1).coerceIn(0, 30)) { offset ->
            rows.put(JSONObject().put("hash", "playlist-$list-${start + offset}")
                .put("name", "曲目$list-${start + offset}").put("fileid", list * 100 + start + offset).put("timelen", 60_000))
        }
        return JSONObject().put("status", 1).put("data", JSONObject().put("info", rows))
    }

    // 状态读取保留在主线程，等待实际协程结果而不是使用固定睡眠。
    suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(20)
    }

    // 等待测试请求取消后，仅移除本次用例自己的临时目录。
    fun close() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        directory.deleteRecursively()
    }
}
