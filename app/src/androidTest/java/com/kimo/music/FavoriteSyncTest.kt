package com.kimo.music

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.CloudCache
import com.kimo.music.core.CoreTransport
import com.kimo.music.core.KugouApi
import com.kimo.music.core.OnlineLibrary
import com.kimo.music.core.SERVER_ERROR_MESSAGE
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.FavoriteIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FavoriteSyncTest {
    // 慢网络下爱心与收藏列表立即变化，服务端成功后再读取真实云端编号。
    @Test fun favoriteFeedbackDoesNotWaitForCloudWrite() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) {
                fixture.library.toggleFavorite(fixture.track, fixture::notice)
                assertTrue("网络尚未返回时爱心应立即点亮", fixture.library.liked(fixture.track))
                assertTrue(FavoriteIndex(fixture.library.favoriteTracks).contains(fixture.track))
                assertEquals(fixture.track.id, fixture.library.favoriteTracks.single().id)
                assertTrue(fixture.library.cloud.favorites.isEmpty())
                assertTrue("写入确认前不显示正在加入提示", fixture.messages.isEmpty())
            }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.library.cloud.favorites.singleOrNull()?.cloudFileId == 42L }
        } finally { fixture.close() }
    }

    // 服务端明确拒绝收藏时撤销临时状态，并展示失败原因。
    @Test fun failedWriteRollsBackOptimisticFavorite() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) { fixture.server.failWrite = true; fixture.library.toggleFavorite(fixture.track, fixture::notice) }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.messages.any { it == SERVER_ERROR_MESSAGE } }
            withContext(Dispatchers.Main) {
                assertFalse(fixture.library.liked(fixture.track))
                assertTrue(fixture.library.favoriteTracks.isEmpty())
                assertEquals(SERVER_ERROR_MESSAGE, fixture.messages.last())
                assertEquals(1, fixture.server.writeAttempts)
            }
        } finally { fixture.close() }
    }

    // 成功提示只等待收藏写入，不等待后续的慢速全量曲库读取。
    @Test fun favoriteSuccessDoesNotWaitForFullLibraryRefresh() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) {
                fixture.server.readGate = CompletableDeferred()
                fixture.library.toggleFavorite(fixture.track, fixture::notice)
            }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.messages.any { it == "已加入我喜欢" } }
            withContext(Dispatchers.Main) {
                assertTrue(fixture.library.cloud.favorites.isEmpty())
                assertTrue(fixture.library.liked(fixture.track))
                assertEquals(listOf("已加入我喜欢"), fixture.messages)
                fixture.server.readGate?.complete(Unit)
            }
            fixture.await { fixture.library.cloud.favorites.singleOrNull()?.cloudFileId == 42L }
            withContext(Dispatchers.Main) { assertEquals(1, fixture.messages.count { it == "已加入我喜欢" }) }
        } finally { fixture.close() }
    }

    // 取消收藏失败时恢复爱心和列表，已确认的云端记录仍保留。
    @Test fun failedRemovalRestoresFavorite() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) { fixture.library.toggleFavorite(fixture.track, fixture::notice) }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.library.cloud.favorites.singleOrNull()?.cloudFileId == 42L }
            val gate = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                fixture.server.writeGate = gate; fixture.server.failWrite = true
                fixture.library.toggleFavorite(fixture.track, fixture::notice)
                assertFalse(fixture.library.liked(fixture.track))
            }
            gate.complete(Unit)
            fixture.await { fixture.messages.any { it == SERVER_ERROR_MESSAGE } }
            withContext(Dispatchers.Main) {
                assertTrue(fixture.library.liked(fixture.track))
                assertEquals(2, fixture.server.writeAttempts)
            }
        } finally { fixture.close() }
    }

    // 请求尚未返回时反复点击，以最后一次选择为准，并用真实 fileid 取消。
    @Test fun rapidToggleKeepsLastChoiceAndUsesCloudFileId() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) { fixture.library.toggleFavorite(fixture.track, fixture::notice) }
            fixture.server.writeStarted.await()
            withContext(Dispatchers.Main) {
                fixture.library.toggleFavorite(fixture.track, fixture::notice)
                assertFalse("第二次点击应立即取消高亮", fixture.library.liked(fixture.track))
            }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.server.writes.size == 2 && fixture.library.cloud.favorites.isEmpty() }
            withContext(Dispatchers.Main) {
                assertEquals(listOf("add", "del:42"), fixture.server.writes)
                assertFalse(fixture.library.liked(fixture.track))
            }
        } finally { fixture.close() }
    }

    // 云端写入成功但读取失败时保留已提交的状态，重新同步后继续处理下一次点击。
    @Test fun refreshFailurePreservesCommittedChangeAndReconcilesLaterToggle() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) {
                fixture.server.failReadsAfterWrite = true
                fixture.library.toggleFavorite(fixture.track, fixture::notice)
            }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.messages.any { it.contains("待确认") } }
            withContext(Dispatchers.Main) {
                assertTrue(fixture.library.liked(fixture.track))
                fixture.library.toggleFavorite(fixture.track, fixture::notice)
                assertFalse(fixture.library.liked(fixture.track))
            }
            fixture.await { fixture.messages.any { it == SERVER_ERROR_MESSAGE } }
            withContext(Dispatchers.Main) { fixture.server.failReads = false; fixture.library.syncAccount() }
            fixture.await { fixture.server.writes.size == 2 && fixture.library.cloud.favorites.isEmpty() }
            withContext(Dispatchers.Main) { assertFalse(fixture.library.liked(fixture.track)) }
        } finally { fixture.close() }
    }

    // 手动同步不能中断同账号正在进行的收藏写入。
    @Test fun manualSyncKeepsPendingWrite() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) { fixture.library.toggleFavorite(fixture.track, fixture::notice) }
            fixture.server.writeStarted.await()
            withContext(Dispatchers.Main) { fixture.library.syncAccount(); assertTrue(fixture.library.liked(fixture.track)) }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.library.cloud.favorites.singleOrNull()?.cloudFileId == 42L }
        } finally { fixture.close() }
    }

    // 切换账号时立刻清除旧账号的待提交状态，旧请求不能写入新账号。
    @Test fun accountSwitchClearsPendingWrite() = runBlocking {
        val fixture = FavoriteFixture()
        try {
            fixture.start()
            withContext(Dispatchers.Main) { fixture.library.toggleFavorite(fixture.track, fixture::notice) }
            fixture.server.writeStarted.await()
            withContext(Dispatchers.Main) {
                fixture.owner = "another-test-owner"
                fixture.library.syncAccount()
                assertTrue(fixture.library.favoriteTracks.isEmpty())
            }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.library.cloud.ownerId == "another-test-owner" && !fixture.library.cloud.loading }
            withContext(Dispatchers.Main) { assertTrue(fixture.server.writes.isEmpty()) }
        } finally { fixture.close() }
    }

    // 同时收藏多首歌曲时，后点的歌曲也必须保留并依次写入服务端。
    @Test fun distinctSongsRemainQueuedDuringSlowWrite() = runBlocking {
        val fixture = FavoriteFixture()
        val second = fixture.track.copy(id = "fixture-second", hash = "fixture-second", mixSongId = 456)
        try {
            fixture.start()
            withContext(Dispatchers.Main) { fixture.library.toggleFavorite(fixture.track, fixture::notice) }
            fixture.server.writeStarted.await()
            withContext(Dispatchers.Main) {
                fixture.library.toggleFavorite(second, fixture::notice)
                assertEquals(2, fixture.library.favoriteTracks.size)
            }
            fixture.server.writeGate.complete(Unit)
            fixture.await { fixture.library.cloud.favorites.size == 2 }
            withContext(Dispatchers.Main) { assertTrue(fixture.library.liked(second)) }
        } finally { fixture.close() }
    }
}

private class FavoriteFixture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "favorite-test-${System.nanoTime()}")
    private val isolatedContext = object : ContextWrapper(context) {
        // 测试快照只写入独立临时目录，不留下伪账号曲库。
        override fun getFilesDir(): File = directory
    }
    val server = FakeFavoriteServer()
    var owner: String? = "favorite-fixture-${System.nanoTime()}"
    val messages = mutableListOf<String>()
    val track = DemoCatalog.tracks.first().copy(id = "fixture-song", hash = "fixture-song", mixSongId = 123)
    val library = OnlineLibrary(KugouApi(server) { null }, scope, CloudCache(isolatedContext), { owner }, { })

    // 使用完全隔离的假接口构造云收藏，不触碰设备上的真实账号。
    suspend fun start() {
        withContext(Dispatchers.Main) { library.register(listOf(track)); library.syncAccount() }
        await { library.cloud.playlists.isNotEmpty() && !library.cloud.loading }
    }

    // 所有界面状态读取都在主线程，超时直接暴露错误而非无限等待。
    suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(20)
    }

    // 保存操作反馈，供测试断言失败原因。
    fun notice(message: String) { messages.add(message) }

    // 取消假请求并等待缓存写入结束后，删除仅属于本次测试的临时目录。
    suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(Dispatchers.IO) { directory.deleteRecursively() }
    }
}

private class FakeFavoriteServer : CoreTransport {
    var writeGate = CompletableDeferred<Unit>()
    var readGate: CompletableDeferred<Unit>? = null
    val writeStarted = CompletableDeferred<Unit>()
    val writes = mutableListOf<String>()
    var failWrite = false
    var failReads = false
    var failReadsAfterWrite = false
    var writeAttempts = 0
    private val tracks = mutableListOf<JSONObject>()

    // 返回隔离的酷狗协议形状，并允许测试控制网络延迟和失败。
    override suspend fun execute(module: String, parameters: JSONObject, credentials: JSONObject): JSONObject = when (module) {
        "user_playlist" -> playlists()
        "playlist_track_all_new" -> response(JSONArray(tracks))
        "playlist_tracks_add" -> write(parameters, true)
        "playlist_tracks_del" -> write(parameters, false)
        else -> response(JSONArray())
    }

    // 曲库读取故障不影响假服务器中已经提交的数据。
    private suspend fun playlists(): JSONObject {
        readGate?.await()
        if (failReads) error("模拟曲库读取失败")
        return response(JSONArray().put(JSONObject().put("listid", 7).put("name", "我喜欢").put("is_def", 2)))
    }

    // 假服务端分配 fileid，删除只能使用这个真实记录编号。
    private suspend fun write(parameters: JSONObject, add: Boolean): JSONObject {
        writeAttempts++
        writeStarted.complete(Unit)
        writeGate.await()
        if (failWrite) error("模拟收藏失败")
        if (add) {
            val fields = parameters.getString("data").split("|")
            tracks.add(JSONObject().put("hash", fields[1]).put("mixsongid", fields[3])
                .put("name", "测试歌曲").put("fileid", 42 + tracks.size))
            writes.add("add")
        } else {
            val fileId = parameters.getString("fileids")
            tracks.removeAll { it.optString("fileid") == fileId }
            writes.add("del:$fileId")
        }
        if (failReadsAfterWrite) { failReads = true; failReadsAfterWrite = false }
        return JSONObject().put("status", 1)
    }

    // 对应酷狗列表接口的 data.info 包装。
    private fun response(rows: JSONArray): JSONObject = JSONObject().put("status", 1).put("data", JSONObject().put("info", rows))
}
