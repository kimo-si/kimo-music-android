package com.kimo.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// 只读公开曲库检查，不发送短信、不代替用户授权账号，也不进行云端写入。
class KugouLiveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val core = EmbeddedCore.get(context)
    private val api = KugouApi(core) { null }

    // 热搜来自真实接口，首屏使用对应歌曲的真实图片，不伪造参考图中的排名。
    @Test fun hotSearchesKeepRealKeywordsAndArtwork() = runBlocking {
        val terms = api.hotSearches()
        assertTrue("应读取实时热搜列表", terms.size >= 4)
        assertEquals(terms.size, terms.map(HotSearch::keyword).distinct().size)
        assertTrue("热搜封面应来自真实歌曲", terms.take(4).any { it.track?.coverUrl?.startsWith("http") == true })
    }

    // 两首不同专辑的歌曲缺少列表图片时，也能补取各自真实封面。
    @Test fun missingArtworkUsesEachSongsAlbum() = runBlocking {
        val first = api.search("晴天", SearchKind.Song, PageRequest(size = 1)).tracks.first().copy(coverUrl = null)
        val second = api.search("稻香", SearchKind.Song, PageRequest(size = 1)).tracks.first().copy(coverUrl = null)
        capture("album_detail", JSONObject().put("id", first.albumId))
        val firstCover = api.artwork(first)
        val secondCover = api.artwork(second)
        assertTrue("应补齐第一首专辑封面", firstCover?.startsWith("http") == true)
        assertTrue("应补齐第二首专辑封面", secondCover?.startsWith("http") == true)
        if (first.albumId != second.albumId) assertNotEquals(firstCover, secondCover)
    }

    // 验证手机独立访问真实推荐歌单、新歌与榜单。
    @Test fun publicCatalogsReturnRealMusic() = runBlocking {
        capture("top_song", JSONObject().put("pagesize", 10))
        capture("top_playlist", JSONObject().put("pagesize", 10))
        capture("rank_list", JSONObject())
        assertTrue("新歌曲库不应为空", api.newSongs(PageRequest(size = 10)).tracks.isNotEmpty())
        assertTrue("推荐歌单不应为空", api.playlists(PageRequest(size = 10)).entries.isNotEmpty())
        assertTrue("榜单目录不应为空", api.charts().isNotEmpty())
    }

    // 验证歌手、电台、在线搜索和真实二维码生成接口。
    @Test fun artistRadioSearchAndQrUseEmbeddedCore() = runBlocking {
        capture("artist_lists", JSONObject().put("hotsize", 10))
        capture("fm_recommend", JSONObject())
        assertTrue("热门歌手不应为空", api.artists().isNotEmpty())
        assertTrue("搜索应返回在线歌曲", api.search("周杰伦", SearchKind.Song, PageRequest(size = 5)).tracks.isNotEmpty())
        val keyResponse = api.request("login_qr_key")
        val key = (keyResponse.optJSONObject("data") ?: keyResponse).optString("qrcode")
        assertTrue("真实二维码应有 key", key.isNotBlank())
        assertTrue("电台目录不应为空", api.radios().isNotEmpty())
    }

    // 逐个验证详情协议与四类搜索，避免仅目录有内容而点击后丢失曲库。
    @Test fun detailCatalogsAndSearchTypesKeepTheirContents() = runBlocking {
        val playlist = api.playlists(PageRequest(size = 5)).entries.first()
        val rank = api.charts().first()
        val artist = api.artists().first()
        val radio = api.radios().first()
        for (entry in listOf(playlist, rank, artist, radio)) {
            val parameters = JSONObject().put("page", 1).put("pagesize", 5)
            val module = when (entry.kind) {
                CatalogKind.Rank -> "rank_audio".also { parameters.put("rankid", entry.id) }
                CatalogKind.Artist -> "artist_audios".also { parameters.put("id", entry.id) }
                CatalogKind.Radio -> "fm_songs".also { parameters.put("fmid", entry.id) }
                else -> "playlist_track_all".also { parameters.put("id", entry.id) }
            }
            capture(module, parameters)
            assertTrue("详情曲库不能为空：${entry.kind}", api.tracks(entry, PageRequest(size = 5)).tracks.isNotEmpty())
        }
        for (kind in SearchKind.entries) {
            val raw = core.execute("search", JSONObject().put("keywords", "周杰伦").put("type", kind.apiType).put("pagesize", 5), JSONObject())
            File(context.filesDir, "smoke-search-${kind.apiType}.json").writeText(raw.toString())
            val result = api.search("周杰伦", kind, PageRequest(size = 5))
            assertTrue("搜索类型应有真实结果：$kind", result.tracks.isNotEmpty() || result.entries.isNotEmpty())
            if (kind == SearchKind.Album || kind == SearchKind.Playlist) {
                assertTrue("搜索结果详情应可读取：$kind", api.tracks(result.entries.first(), PageRequest(size = 5)).tracks.isNotEmpty())
            }
        }
    }

    // 验证艺人详情、专辑目录与真实歌词下载，不使用样例文案冒充接口内容。
    @Test fun artistAlbumsProfilesAndLyricsRemainAvailable() = runBlocking {
        val artist = api.artists().first()
        assertTrue(api.artistProfile(artist).name.isNotBlank())
        capture("artist_albums", JSONObject().put("id", artist.id).put("pagesize", 5))
        val albums = api.artistAlbums(artist, PageRequest(size = 5))
        assertTrue("歌手专辑不应为空", albums.entries.isNotEmpty())
        assertTrue(api.tracks(albums.entries.first(), PageRequest(size = 5)).tracks.isNotEmpty())
        val track = api.search("晴天", SearchKind.Song, PageRequest(size = 1)).tracks.first()
        val lyricSearch = capture("search_lyric", JSONObject().put("hash", track.hash))
        val candidate = (lyricSearch.optJSONObject("data") ?: lyricSearch).getJSONArray("candidates").getJSONObject(0)
        capture("lyric", JSONObject().put("id", candidate.getString("id")).put("accesskey", candidate.getString("accesskey"))
            .put("decode", true).put("fmt", "lrc"))
        assertTrue("应能读取真实歌词", KugouPlaybackApi(api).lyrics(track).isNotEmpty())
    }

    // 仅保存无账号请求的公开响应，用于核对不同曲库的实际协议形状。
    private suspend fun capture(module: String, parameters: JSONObject): JSONObject {
        val response = core.execute(module, parameters, JSONObject())
        File(context.filesDir, "smoke-$module.json").writeText(response.toString())
        response.requireSuccess()
        return response
    }
}
