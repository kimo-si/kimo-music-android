package com.kimo.music.core

import com.kimo.music.data.Track
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException

// 桌面与安卓共用上游核心模块，客户端只承担领域对象映射和分页编排。
class KugouApi(private val core: CoreTransport, private val auth: () -> AuthCredentials?) {
    // 每次请求读取当前账号，注销或切换账号后不复用旧凭据。
    suspend fun request(module: String, parameters: JSONObject = JSONObject()): JSONObject =
        core.execute(module, parameters, auth()?.json() ?: JSONObject()).requireSuccess()

    // 推荐歌单和广场共用同一分页接口，保留原始全局歌单标识。
    suspend fun playlists(page: PageRequest = PageRequest()): EntryPage {
        val data = payload(request("top_playlist", page.json().put("category_id", 0).put("withtag", 1)))
        val rows = data.rows("special_list", "info")
        val next = if (data.has("has_next")) (page.page + 1).takeIf { data.number("has_next") != 0L } else nextPage(rows.size, page)
        return EntryPage(rows.mapNotNull { KugouMapper.entry(it, CatalogKind.Playlist) }, next)
    }

    // 账号推荐和新歌分别请求，不让其中一个模块失败清空另一个模块。
    suspend fun recommendations(): List<Track> = payload(request("everyday_recommend"))
        .rows("song_list", "songs").mapNotNull(KugouMapper::track)

    // 新歌接口直接返回 data 数组，不套用推荐歌单的响应形状。
    suspend fun newSongs(page: PageRequest = PageRequest()): TrackPage {
        val response = request("top_song", page.json())
        val rows = response.optJSONArray("data")?.objects() ?: payload(response).rows("info", "songs")
        return TrackPage(rows.mapNotNull(KugouMapper::track), nextPage(rows.size, page))
    }

    // 排行榜与热门歌手保留单独的目录入口。
    suspend fun charts(): List<CatalogEntry> = payload(request("rank_list")).rows("info")
        .mapNotNull { KugouMapper.entry(it, CatalogKind.Rank) }

    // 仅提取热门分组，兼容歌手头像嵌套返回。
    suspend fun artists(): List<CatalogEntry> {
        val groups = payload(request("artist_lists", JSONObject().put("hotsize", 60))).rows("info")
        return groups.firstOrNull { it.text("title") == "热门" }?.rows("singer").orEmpty()
            .mapNotNull { KugouMapper.entry(it, CatalogKind.Artist) }.distinctBy(CatalogEntry::id)
    }

    // 搜索提供歌曲、歌单、歌手和专辑四类结果，分页不截断已加载内容。
    suspend fun search(keyword: String, kind: SearchKind, page: PageRequest): SearchResult {
        val data = payload(request("search", page.json().put("keywords", keyword).put("type", kind.apiType)))
        val rows = data.rows("lists", "info", "list")
        if (kind == SearchKind.Song) return SearchResult(tracks = rows.mapNotNull(KugouMapper::track), nextPage = nextPage(rows.size, page))
        val catalogKind = when (kind) {
            SearchKind.Playlist -> CatalogKind.Playlist
            SearchKind.Artist -> CatalogKind.Artist
            SearchKind.Album -> CatalogKind.Album
            else -> CatalogKind.Playlist
        }
        return SearchResult(entries = rows.mapNotNull { KugouMapper.entry(it, catalogKind) }, nextPage = nextPage(rows.size, page))
    }

    // 热搜顺序来自酷狗，首屏四项并行补齐真实歌曲封面与歌手信息。
    suspend fun hotSearches(): List<HotSearch> = coroutineScope {
        val groups = payload(request("search_hot")).rows("list")
        val rows = groups.firstOrNull { it.text("name") == "热搜榜" }?.rows("keywords")
            ?: groups.firstOrNull()?.rows("keywords").orEmpty()
        val terms = rows.mapNotNull { row -> row.text("keyword").takeIf(String::isNotBlank)?.let {
            HotSearch(it, row.text("reason"))
        } }.distinctBy(HotSearch::keyword)
        val enriched = terms.take(4).map { term -> async { enrichHotSearch(term) } }.awaitAll()
        enriched + terms.drop(4)
    }

    // 单项图片补取失败不影响真实热搜列表，取消请求时继续传播取消信号。
    private suspend fun enrichHotSearch(term: HotSearch): HotSearch = try {
        val track = search(term.keyword, SearchKind.Song, PageRequest(size = 1)).tracks.firstOrNull()
        val cover = track?.coverUrl ?: track?.let { artwork(it) }
        term.copy(track = track?.copy(coverUrl = cover))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { term }

    // 不同目录按各自协议读取，云歌单必须使用 listid 与 fileid。
    suspend fun tracks(entry: CatalogEntry, page: PageRequest, order: ArtistOrder = ArtistOrder.Hot): TrackPage {
        val query = trackQuery(entry, page, order)
        // 调用当前目录对应的核心模块，协议解析在边界完成。
        val response = request(query.module, query.parameters)
        val rows = trackRows(response, entry.kind)
        val tracks = rows.mapNotNull(KugouMapper::track).map { track ->
            if (entry.kind == CatalogKind.CloudDrive) track.copy(id = "cloud:${track.id}", cloudDrive = true) else track
        }.distinctBy(Track::id)
        return TrackPage(tracks, nextPage(rows.size, page), payload(response).number("count", "total").toInt().coerceAtLeast(tracks.size))
    }

    // 将目录类型转换为单一接口请求，不在界面层拼接上游参数。
    private fun trackQuery(entry: CatalogEntry, page: PageRequest, order: ArtistOrder): CatalogQuery {
        val parameters = page.json()
        val module = when (entry.kind) {
            CatalogKind.Rank -> "rank_audio".also { parameters.put("rankid", entry.id) }
            CatalogKind.Artist -> "artist_audios".also { parameters.put("id", entry.id).put("sort", order.code) }
            CatalogKind.Album -> "album_songs".also { parameters.put("id", entry.id) }
            CatalogKind.CloudPlaylist, CatalogKind.Favorites -> "playlist_track_all_new".also { parameters.put("listid", entry.listId).put("type", entry.listType) }
            CatalogKind.CloudDrive -> "user_cloud"
            CatalogKind.Radio -> "fm_songs".also { parameters.put("fmid", entry.id).put("size", page.size).put("offset", (page.page - 1) * page.size) }
            else -> "playlist_track_all".also { parameters.put("id", entry.id) }
        }
        return CatalogQuery(module, parameters)
    }

    // 兼容列表、数组和电台分组响应，分页依据上游原始条目数。
    private fun trackRows(response: JSONObject, kind: CatalogKind): List<JSONObject> {
        val raw = response.opt("data")
        val rows = if (raw is JSONArray) raw.objects() else payload(response).rows("songs", "songlist", "info", "list", "file_list", "song_list")
        return if (kind == CatalogKind.Radio) rows.flatMap { it.rows("songlist", "songs").ifEmpty { listOf(it) } } else rows
    }

    // 读取账号全部歌单，分页持续到服务端返回空页或完整末页。
    suspend fun userPlaylists(): List<CatalogEntry> {
        val result = mutableListOf<CatalogEntry>()
        val seen = mutableSetOf<Long>()
        var page = 1
        while (true) {
            val rows = payload(request("user_playlist", PageRequest(page, 100).json())).rows("info")
            val fresh = rows.mapNotNull { row -> KugouMapper.entry(row, if (row.number("is_def") == 2L || row.text("name") == "我喜欢")
                CatalogKind.Favorites else CatalogKind.CloudPlaylist) }
                .filter { entry -> seen.add(entry.listId) }
            result.addAll(fresh)
            if (rows.size < 100 || fresh.isEmpty()) break
            page++
        }
        return result
    }

    // 同步收藏时逐页拉取，不使用固定的前几百首作为完整曲库。
    suspend fun allTracks(entry: CatalogEntry): List<Track> {
        val result = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        var next: Int? = 1
        while (next != null) {
            val page = tracks(entry, PageRequest(next, 300))
            val fresh = page.tracks.filter { incoming -> seen.add(incoming.id) }
            result.addAll(fresh)
            if (fresh.isEmpty()) break
            next = page.nextPage
        }
        return result
    }

    // 服务端成功后才由上层更新收藏界面，失败时保持原有同步状态。
    suspend fun addTrack(entry: CatalogEntry, track: Track) {
        request("playlist_tracks_add", JSONObject().put("listid", entry.listId)
            .put("data", "-|${track.hash}|${track.albumId}|${track.mixSongId}"))
    }

    // 删除使用云端 fileid，禁止把音频 hash 当作歌曲记录编号。
    suspend fun removeTrack(entry: CatalogEntry, track: Track) {
        require(track.cloudFileId > 0) { "尚未取得云端歌曲编号，请先同步" }
        request("playlist_tracks_del", JSONObject().put("listid", entry.listId).put("fileids", track.cloudFileId.toString()))
    }

    // 保留创建、重命名与删除歌单的真实云端操作。
    suspend fun createPlaylist(name: String) { request("playlist_add", JSONObject().put("name", name).put("type", 0)) }

    // 修改既有歌单时显式传入其类别，避免修改另一个云端集合。
    suspend fun renamePlaylist(entry: CatalogEntry, name: String) {
        request("playlist_update", JSONObject().put("listid", entry.listId).put("type", entry.listType).put("name", name))
    }

    // 删除歌单只处理调用方明确选定的对象。
    suspend fun deletePlaylist(entry: CatalogEntry) { request("playlist_del", JSONObject().put("listid", entry.listId)) }

    // 电台目录使用真实推荐，不以演示音频代替在线曲库。
    suspend fun radios(): List<CatalogEntry> = payload(request("fm_recommend")).rows("info", "data", "list", "fm_list")
        .mapNotNull { KugouMapper.entry(it, CatalogKind.Radio) }

    // 艺人专辑保留完整分页入口。
    suspend fun artistAlbums(entry: CatalogEntry, page: PageRequest): EntryPage {
        val response = request("artist_albums", page.json().put("id", entry.id))
        val rows = response.optJSONArray("data")?.objects() ?: payload(response).rows("info", "list", "lists")
        return EntryPage(rows.mapNotNull { KugouMapper.entry(it, CatalogKind.Album) }, nextPage(rows.size, page))
    }

    // 歌手简介、头像和统计来自真实详情接口，不从列表项猜测。
    suspend fun artistProfile(entry: CatalogEntry): ArtistProfile {
        val data = payload(request("artist_detail", JSONObject().put("id", entry.id)))
        val introduction = data.rows("long_intro").joinToString("\n") { it.text("content") }.ifBlank { data.text("intro") }
        return ArtistProfile(data.text("author_name", "singername").ifBlank { entry.title },
            KugouMapper.sizedImage(data.text("sizable_avatar", "avatar")), introduction,
            data.number("fansnums", "fanscount"), data.number("album_count", "albumcount"))
    }

    // 曲目列表缺少图片时按真实专辑编号补取封面，不用固定照片冒充其他歌曲。
    suspend fun artwork(track: Track): String? {
        track.coverUrl?.let { return it }
        if (track.albumId <= 0) return null
        val response = request("album_detail", JSONObject().put("id", track.albumId))
        val entries = response.optJSONArray("data")?.objects() ?: payload(response).rows("info", "list")
        val album = entries.firstOrNull { it.number("album_id") == track.albumId } ?: entries.firstOrNull() ?: return null
        return KugouMapper.sizedImage(album.text("sizable_cover", "cover", "img"))
    }

    // 提取协议 data 节点，仅在原始接口没有该节点时回退根对象。
    private fun payload(response: JSONObject): JSONObject = response.optJSONObject("data") ?: response

    // 只有满页才继续请求下一页，最后一页不凭总数猜测缺失条目。
    private fun nextPage(size: Int, page: PageRequest): Int? = if (size >= page.size) page.page + 1 else null

    // 分页参数在协议边界编码，领域层不传递任意键值字典。
    private fun PageRequest.json(): JSONObject = JSONObject().put("page", page).put("pagesize", size)

    private data class CatalogQuery(val module: String, val parameters: JSONObject)
}
