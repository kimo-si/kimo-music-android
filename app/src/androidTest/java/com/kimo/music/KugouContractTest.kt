package com.kimo.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.*
import com.kimo.music.data.TrackStorage
import com.kimo.music.playback.KugouDataSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouContractTest {
    // 歌词服务使用 status=200、errcode=200 表示成功，其他接口的错误仍须拒绝。
    @Test fun lyricSuccessCodeDoesNotMaskCatalogErrors() {
        assertEquals(200, JSONObject().put("status", 200).put("errcode", 200).requireSuccess().getInt("status"))
        try { JSONObject().put("status", 0).put("errcode", 200).requireSuccess(); fail("目录错误不能被当作歌词成功") }
        catch (_: MusicApiException) { }
    }

    // 艺人专辑直接以数组返回，映射必须保留专辑编号和后续分页。
    @Test fun artistAlbumArrayRetainsAllEntriesAndPagination() = runBlocking {
        val fake = CoreTransport { _, _, _ -> JSONObject().put("status", 1).put("data", JSONArray()
            .put(JSONObject().put("album_id", 77).put("album_name", "专辑"))) }
        val page = KugouApi(fake) { null }.artistAlbums(CatalogEntry("artist", "歌手", CatalogKind.Artist), PageRequest(size = 1))
        assertEquals("77", page.entries.single().id)
        assertEquals(2, page.nextPage)
    }

    // 收藏的歌单使用 type=1，自建歌单使用 type=0，不能让两类目录读到同一集合。
    @Test fun collectedPlaylistKeepsItsCloudListType() = runBlocking {
        val api = KugouApi(CoreTransport { _, parameters, _ ->
            assertEquals(1, parameters.getInt("type"))
            JSONObject().put("status", 1).put("data", JSONObject().put("info", JSONArray().put(track(1))))
        }) { null }
        val entry = CatalogEntry("collected", "收藏歌单", CatalogKind.CloudPlaylist, listId = 88, listType = 1)
        assertEquals(1, api.tracks(entry, PageRequest()).tracks.size)
    }

    // 云盘歌曲保留来源并调用专用地址接口，不能在历史恢复后变成普通曲库音频。
    @Test fun cloudDriveSourceSurvivesStorageAndUsesCloudPlayback() = runBlocking {
        val api = KugouApi(CoreTransport { module, _, _ -> when (module) {
            "user_cloud" -> JSONObject().put("status", 1).put("data", JSONObject().put("list", JSONArray().put(track(3))))
            "user_cloud_url" -> JSONObject().put("status", 1).put("url", JSONArray().put("https://example.com/cloud.mp3"))
            else -> error("云盘不应调用其他取流模块：$module")
        } }) { null }
        val entry = CatalogEntry("cloud", "音乐云盘", CatalogKind.CloudDrive)
        val track = api.tracks(entry, PageRequest()).tracks.single()
        val restored = TrackStorage.decode(TrackStorage.encode(track))
        assertTrue(restored.cloudDrive)
        assertEquals("true", KugouDataSource.uri(restored, AudioQuality.Standard).getQueryParameter("cloud"))
        assertEquals("https://example.com/cloud.mp3", KugouPlaybackApi(api).stream(restored, AudioQuality.Standard).url)
    }

    // 大型我喜欢集合必须持续分页到末尾，不能因移动端默认页大小丢歌。
    @Test fun favoritesKeepAllSixHundredAndOneTracks() = runBlocking {
        var requests = 0
        val fake = CoreTransport { module, parameters, _ ->
            assertEquals("playlist_track_all_new", module)
            requests++
            val start = (parameters.getInt("page") - 1) * parameters.getInt("pagesize")
            val rows = JSONArray()
            for (index in start until minOf(start + 300, 601)) rows.put(track(index))
            JSONObject().put("status", 1).put("data", JSONObject().put("info", rows).put("count", 601))
        }
        val api = KugouApi(fake) { null }
        val tracks = api.allTracks(CatalogEntry("favorites", "我喜欢", CatalogKind.Favorites, listId = 7))
        assertEquals(601, tracks.size)
        assertEquals(3, requests)
        assertEquals(601L, tracks.last().cloudFileId)
    }

    // 账号歌单超过一百个时仍保留全部目录。
    @Test fun accountPlaylistsAreNotTruncatedAtOnePage() = runBlocking {
        val fake = CoreTransport { _, parameters, _ ->
            val start = (parameters.getInt("page") - 1) * 100
            val rows = JSONArray()
            for (index in start until minOf(start + 100, 205)) rows.put(JSONObject().put("listid", index + 1).put("name", "歌单$index"))
            JSONObject().put("status", 1).put("data", JSONObject().put("info", rows))
        }
        assertEquals(205, KugouApi(fake) { null }.userPlaylists().size)
    }

    // 推荐接口可能忽略请求页大小，必须尊重服务端 has_next 而不是提前截断。
    @Test fun shortRecommendationPageStillHonorsServerPagination() = runBlocking {
        val fake = CoreTransport { _, _, _ -> JSONObject().put("status", 1).put("data", JSONObject()
            .put("has_next", 1).put("special_list", JSONArray().put(JSONObject().put("global_collection_id", "collection-1").put("specialname", "歌单")))) }
        val page = KugouApi(fake) { null }.playlists(PageRequest(size = 30))
        assertEquals(1, page.entries.size)
        assertEquals(2, page.nextPage)
    }

    // 搜索歌单必须使用 gid，展示 specialid 不能覆盖全局取曲标识。
    @Test fun searchPlaylistKeepsGlobalCollectionIdentifier() {
        val value = JSONObject().put("specialid", 55).put("gid", "collection_3_1_2_0").put("specialname", "示例")
        assertEquals("collection_3_1_2_0", KugouMapper.entry(value, CatalogKind.Playlist)?.id)
    }

    // 缓存完整保留云端 fileid 与全部收藏，其他账号不可读取这份快照。
    @Test fun cloudCachePreservesTrackIdentifiersAndAccountIsolation() {
        val cache = CloudCache(ApplicationProvider.getApplicationContext<Context>())
        val owner = "cache-test-${System.nanoTime()}"
        val favorite = requireNotNull(KugouMapper.track(track(7).put("mixsongid", 812)))
        val entry = CatalogEntry("fav", "我喜欢", CatalogKind.Favorites, listId = 9)
        cache.save(CloudLibraryState(listOf(entry), listOf(favorite), ownerId = owner))
        val restored = requireNotNull(cache.load(owner))
        assertEquals(8L, restored.favorites.single().cloudFileId)
        assertEquals(812L, restored.favorites.single().mixSongId)
        assertEquals(9L, restored.playlists.single().listId)
        assertTrue(restored.fromCache)
        assertNull(cache.load("different-$owner"))
    }

    // 注销后下一次请求必须立即停止使用旧 token 和 t1。
    @Test fun credentialsFollowCurrentAccountWithoutCachedAuthorization() = runBlocking {
        var credentials: AuthCredentials? = AuthCredentials("test-token", "17", "test-t1")
        val seen = mutableListOf<JSONObject>()
        val api = KugouApi(CoreTransport { _, _, auth -> seen.add(auth); JSONObject().put("status", 1) }) { credentials }
        api.request("user_playlist")
        credentials = null
        api.request("top_song")
        assertEquals("test-t1", seen.first().getString("t1"))
        assertEquals(0, seen.last().length())
    }

    // 歌单中的 audio_id 不可替代 mixsongid，零值别名不能遮住有效编号。
    @Test fun cloudTrackMappingPreservesPlaybackAndDeleteIdentifiers() {
        val track = requireNotNull(KugouMapper.track(track(2).put("album_audio_id", 0).put("mixsongid", 901)
            .put("audio_id", 555).put("name", "歌手 - 示例.flac").put("author_name", "歌手")))
        assertEquals(901L, track.mixSongId)
        assertEquals(3L, track.cloudFileId)
        assertEquals("示例", track.title)
    }

    // 服务器明确的账号失效必须传播，不能被当作空曲库。
    @Test fun expiredSessionIsAnErrorRatherThanEmptyLibrary() = runBlocking {
        val api = KugouApi(CoreTransport { _, _, _ -> JSONObject().put("status", 0).put("error_code", 20017) }) { null }
        try { api.userPlaylists(); fail("应拒绝失效会话") }
        catch (error: MusicApiException) { assertTrue(error.expired) }
    }

    // 登录凭据只能以设备密钥加密保存，注销后不可恢复。
    @Test fun keystoreSessionRoundTripNeverStoresPlainToken() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = SessionVault(context)
        val session = AccountSession(AuthCredentials("not-a-real-token", "test-user", "test-t1"), AccountProfile("test-user", "测试用户"))
        try {
            vault.save(session)
            assertEquals(session, vault.load())
            val stored = context.getSharedPreferences("kimo_account", Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(stored.contains(session.credentials.token))
        } finally { vault.clear() }
        assertNull(vault.load())
    }

    // 创建只用于协议回归的歌曲，避免测试修改真实酷狗账号。
    private fun track(index: Int): JSONObject = JSONObject().put("hash", "hash-$index").put("name", "歌曲$index")
        .put("fileid", index + 1).put("timelen", 240000)
}
