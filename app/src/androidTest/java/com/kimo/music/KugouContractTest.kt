package com.kimo.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.*
import com.kimo.music.data.TrackStorage
import com.kimo.music.data.Track
import com.kimo.music.playback.KugouDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouContractTest {
    // 未登录且标准音质已核实为会员时，在取流前结束并提供准确的登录提示。
    @Test fun verifiedVipRequiresLoginBeforeRequestingStream() = runBlocking {
        var streamCalls = 0
        val song = Track("vip-track", "会员歌曲", "歌手", "专辑", "", 0, hash = "vip-hash", albumId = 17)
        val api = KugouApi(CoreTransport { module, _, _ ->
            if (module == "privilege_lite") membershipResponse(song, 3, 10)
            else { streamCalls++; JSONObject().put("status", 0).put("errcode", 20028) }
        }) { null }
        val error = runCatching { KugouPlaybackApi(api).stream(song, AudioQuality.Standard) }.exceptionOrNull()
        assertEquals("这首歌需要 VIP 权益，请先登录", error?.message)
        assertEquals(0, streamCalls)
    }

    // 免费或无法核实的歌曲不误拦截，标准地址仍由服务端返回。
    @Test fun guestFreeAndUnknownTracksStillRequestActualStreams() = runBlocking {
        val song = Track("free-track", "免费歌曲", "歌手", "专辑", "", 0, hash = "free-hash", albumId = 19)
        for (verifiedFree in listOf(true, false)) {
            var streamCalls = 0
            val api = KugouApi(CoreTransport { module, _, _ ->
                if (module == "privilege_lite") {
                    if (verifiedFree) membershipResponse(song, 0, 0) else membershipResponse(song.copy(hash = "other-hash"), 3, 10)
                } else { streamCalls++; JSONObject().put("status", 1).put("url", "https://example.com/free.mp3") }
            }) { null }
            assertEquals("https://example.com/free.mp3", KugouPlaybackApi(api).stream(song, AudioQuality.Standard).url)
            assertEquals(1, streamCalls)
        }
    }

    // 已登录时由取流接口判断实际权益，不凭账号展示资料强行拒绝或承诺可播。
    @Test fun authenticatedTracksUseTheServersPlaybackDecision() = runBlocking {
        val modules = mutableListOf<String>()
        val api = KugouApi(CoreTransport { module, _, _ ->
            modules.add(module)
            JSONObject().put("status", 1).put("url", "https://example.com/authorized.mp3")
        }) { AuthCredentials("test-token", "test-user") }
        val song = Track("member-track", "歌曲", "歌手", "专辑", "", 0, hash = "member-hash")
        assertEquals("https://example.com/authorized.mp3", KugouPlaybackApi(api).stream(song, AudioQuality.Standard).url)
        assertEquals(listOf("song_url"), modules)
    }

    // 权益核实临时失败时继续尝试实际地址，不能用查询故障误判需要会员。
    @Test fun membershipLookupFailureDoesNotInventVipRestrictions() = runBlocking {
        var streamCalls = 0
        val api = KugouApi(CoreTransport { module, _, _ ->
            if (module == "privilege_lite") throw MusicApiException(SERVER_ERROR_MESSAGE)
            streamCalls++
            JSONObject().put("status", 1).put("url", "https://example.com/available.mp3")
        }) { null }
        val song = Track("unknown-track", "歌曲", "歌手", "专辑", "", 0, hash = "unknown-hash")
        assertEquals("https://example.com/available.mp3", KugouPlaybackApi(api).stream(song, AudioQuality.Standard).url)
        assertEquals(1, streamCalls)
    }

    // 返回与歌曲身份匹配的真实权益结构，不使用搜索列表中的付费提示。
    private fun membershipResponse(song: Track, payType: Int, privilege: Int): JSONObject = JSONObject()
        .put("status", 1).put("data", JSONArray().put(JSONObject().put("hash", song.hash).put("album_id", song.albumId)
            .put("relate_goods", JSONArray().put(JSONObject().put("quality", "128").put("level", 2)
                .put("pay_type", payType).put("privilege", privilege)))))

    // 列表付费类型和旧版缓存标记都不冒充已核实的会员权益。
    @Test fun legacyVipHintsAreNotRestoredAsVerifiedMembership() {
        val song = requireNotNull(KugouMapper.track(track(1)))
        assertEquals(song, KugouMapper.track(track(1).put("PayType", 3).put("Privilege", 10)))
        val legacy = TrackStorage.encode(song).put("requiresVip", true)
        assertEquals(song, TrackStorage.decode(legacy))
    }

    // 模拟截图中的 Axios 对象异常，只读请求暂时失败后应自动恢复。
    @Test fun transientReadFailureRetriesWithoutExposingAxiosDetails() = runBlocking {
        var calls = 0
        val api = KugouApi(CoreTransport { _, _, _ ->
            calls++
            if (calls < 3) networkFailure() else JSONObject().put("status", 1).put("data", "ok")
        }) { null }
        assertEquals("ok", api.request("top_song").getString("data"))
        assertEquals(3, calls)
        assertEquals("", networkFailure().text("msg"))
        var thrown = 0
        val transport = KugouApi(CoreTransport { _, _, _ ->
            thrown++
            if (thrown == 1) error("write EPIPE with private configuration") else JSONObject().put("status", 1)
        }) { null }
        assertEquals(1, transport.request("search").getInt("status"))
        assertEquals(2, thrown)
    }

    // 重试耗尽后只显示固定提示，异常栈和请求配置不能到达用户界面。
    @Test fun exhaustedReadFailureUsesOnlySafeMessage() = runBlocking {
        var calls = 0
        val api = KugouApi(CoreTransport { _, _, _ -> calls++; networkFailure() }) { null }
        try { api.request("user_playlist"); fail("持续失败应抛出安全提示") }
        catch (error: MusicApiException) { assertEquals(SERVER_ERROR_MESSAGE, error.message) }
        assertEquals(3, calls)
    }

    // 发送短信和写入歌单可能已在服务端生效，网络故障不能自动重复提交。
    @Test fun nonIdempotentRequestsNeverReplayAfterFailure() = runBlocking {
        for (module in listOf("captcha_sent", "playlist_add", "playlist_tracks_add", "login_cellphone")) {
            var calls = 0
            val api = KugouApi(CoreTransport { _, _, _ -> calls++; error("EPIPE: secret request config") }) { null }
            try { api.request(module); fail("写入失败应结束") }
            catch (error: MusicApiException) { assertEquals(SERVER_ERROR_MESSAGE, error.message) }
            assertEquals(1, calls)
        }
    }

    // 登录失效和用户取消不等待重试，也不把旧账号凭据带到下一轮请求。
    @Test fun expiryCancellationAndAccountSwitchStopRetries() = runBlocking {
        var calls = 0
        val expired = KugouApi(CoreTransport { _, _, _ -> calls++; JSONObject().put("status", 0).put("error_code", 20017) }) { null }
        try { expired.request("user_playlist"); fail("失效会话应终止") }
        catch (error: MusicApiException) { assertTrue(error.expired) }
        assertEquals(1, calls)
        var account: AuthCredentials? = AuthCredentials("token", "owner")
        val switched = KugouApi(CoreTransport { _, _, _ -> calls++; account = null; networkFailure() }) { account }
        try { switched.request("top_song"); fail("切换账号应取消旧请求") }
        catch (_: CancellationException) { }
        assertEquals(2, calls)
        val cancelled = KugouApi(CoreTransport { _, _, _ -> calls++; throw CancellationException("cancelled") }) { null }
        try { cancelled.request("top_song"); fail("取消应立即传播") }
        catch (_: CancellationException) { }
        assertEquals(3, calls)
    }

    // 构造含敏感配置的异常对象，验证协议边界不会把对象串化给界面。
    private fun networkFailure(): JSONObject = JSONObject().put("status", 0).put("msg", JSONObject()
        .put("stack", "write EPIPE private stack").put("config", JSONObject().put("token", "private-token")))

    // 旧会话默认昵称不冒充真实资料，服务端确认同名后仍能正常展示。
    @Test fun legacyPlaceholderNicknameRemainsHiddenUntilVerified() {
        val legacy = AccountProfile.restored("test-user", JSONObject().put("nickname", "酷狗用户"))
        assertEquals("酷狗用户", legacy.nickname)
        assertEquals("", legacy.displayNickname)
        assertEquals("酷狗用户", legacy.copy(nicknameVerified = true).displayNickname)
        assertEquals("真实昵称", AccountProfile.restored("test-user", JSONObject().put("nickname", "真实昵称")).displayNickname)
    }

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

    // 联想只读取第一组真实提示，空值、重复项和其他分组不能混入展示。
    @Test fun searchSuggestionsMapOnlyUniqueKeywordHints() = runBlocking {
        var calls = 0
        val api = KugouApi(CoreTransport { module, parameters, _ ->
            calls++
            assertEquals("search_suggest", module)
            assertEquals("周杰", parameters.getString("keywords"))
            assertEquals(6, parameters.getInt("musicTipCount"))
            val hints = JSONArray()
            listOf(" 周杰伦 ", "周杰伦", "", "晴天", "稻香", "夜曲", "青花瓷", "七里香", "多余结果")
                .forEach { hints.put(JSONObject().put("HintInfo", it)) }
            JSONObject().put("status", 1).put("data", JSONArray()
                .put(JSONObject().put("RecordDatas", hints))
                .put(JSONObject().put("RecordDatas", JSONArray().put(JSONObject().put("HintInfo", "其他分组")))))
        }) { null }
        assertTrue(api.searchSuggestions("  ").isEmpty())
        assertEquals(listOf("周杰伦", "晴天", "稻香", "夜曲", "青花瓷", "七里香"),
            api.searchSuggestions(" 周杰 ").map(SearchSuggestion::keyword))
        assertEquals(1, calls)
    }

    // 上游缺少联想分组时应返回空列表，不能把空结构当作搜索失败。
    @Test fun searchSuggestionsHandleMissingGroups() = runBlocking {
        val api = KugouApi(CoreTransport { _, _, _ -> JSONObject().put("status", 1).put("data", JSONArray()) }) { null }
        assertTrue(api.searchSuggestions("晴天").isEmpty())
    }

    // 歌手统计缺失与真实零值必须区分，不能把未知数据展示为零。
    @Test fun artistStatisticsPreserveMissingAndRealZero() = runBlocking {
        val api = KugouApi(CoreTransport { _, _, _ -> JSONObject().put("status", 1).put("data", JSONObject().put("fansnums", 0)) }) { null }
        val profile = api.artistProfile(CatalogEntry("artist", "歌手", CatalogKind.Artist))
        assertEquals(0L, profile.followers)
        assertNull(profile.albumCount)
    }

    // 热搜只调用热搜接口，不从搜索第一首歌推断封面或歌手。
    @Test fun hotSearchDoesNotInventSongAssociation() = runBlocking {
        var calls = 0
        val api = KugouApi(CoreTransport { module, _, _ ->
            calls++
            assertEquals("search_hot", module)
            JSONObject().put("status", 1).put("data", JSONObject().put("list", JSONArray().put(JSONObject()
                .put("name", "热搜榜").put("keywords", JSONArray().put(JSONObject().put("keyword", "真实热词"))))))
        }) { null }
        assertEquals(listOf(HotSearch("真实热词")), api.hotSearches())
        assertEquals(1, calls)
    }

    // 搜索第一首不匹配时继续核对候选，真实图片统一展开尺寸占位符。
    @Test fun hotArtworkUsesMatchingCandidateInsteadOfTheFirstResult() = runBlocking {
        val api = KugouApi(CoreTransport { module, parameters, _ ->
            assertEquals("search", module)
            assertEquals("真实热词", parameters.getString("keywords"))
            assertEquals("song", parameters.getString("type"))
            searchResponse(track(1).put("name", "其他歌曲"), track(2).put("name", "真实热词")
                .put("Image", "https://example.com/{size}/matching.jpg"))
        }) { null }
        assertEquals("https://example.com/480/matching.jpg", api.hotSearchArtwork(HotSearch("真实热词")))
    }

    // 没有完整匹配时保留占位，不继续读取无关专辑的封面。
    @Test fun hotArtworkDoesNotUseUnrelatedSearchResults() = runBlocking {
        var calls = 0
        val api = KugouApi(CoreTransport { module, _, _ ->
            calls++
            assertEquals("search", module)
            searchResponse(track(1).put("name", "不同歌曲").put("Image", "https://example.com/wrong.jpg"))
        }) { null }
        assertNull(api.hotSearchArtwork(HotSearch("真实热词")))
        assertEquals(1, calls)
    }

    // 同名歌曲缺少图片时只补取自身专辑，复用原有的编号校验逻辑。
    @Test fun hotArtworkFallsBackToTheMatchedSongsAlbum() = runBlocking {
        val api = KugouApi(CoreTransport { module, parameters, _ -> when (module) {
            "search" -> searchResponse(track(1).put("name", "真实热词").put("album_id", 42))
            "album_detail" -> {
                assertEquals(42L, parameters.getLong("id"))
                JSONObject().put("status", 1).put("data", JSONArray().put(JSONObject()
                    .put("album_id", 42).put("cover", "https://example.com/album.jpg")))
            }
            else -> error("不应访问无关模块：$module")
        } }) { null }
        assertEquals("https://example.com/album.jpg", api.hotSearchArtwork(HotSearch("真实热词")))
    }

    // 测试搜索协议使用与真实列表相同的字段形状。
    private fun searchResponse(vararg tracks: JSONObject): JSONObject = JSONObject().put("status", 1)
        .put("data", JSONObject().put("lists", JSONArray(tracks.toList())))

    // 补取封面必须核对专辑编号，其他专辑的返回不能冒充当前歌曲封面。
    @Test fun artworkRejectsAnotherAlbumsCover() = runBlocking {
        val response = JSONObject().put("status", 1).put("data", JSONArray()
            .put(JSONObject().put("album_id", 99).put("cover", "https://example.com/wrong.jpg")))
        val api = KugouApi(CoreTransport { _, _, _ -> response }) { null }
        val track = requireNotNull(KugouMapper.track(track(1).put("album_id", 42)))
        assertNull(api.artwork(track))
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
