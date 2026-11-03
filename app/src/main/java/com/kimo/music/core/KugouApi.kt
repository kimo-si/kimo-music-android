package com.kimo.music.core

import com.kimo.music.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val SEARCH_SUGGESTION_LIMIT = 6
private const val HOT_ARTWORK_CANDIDATES = 5
private const val READ_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 250L
private val RETRYABLE_READ_MODULES = setOf(
    "top_playlist", "everyday_recommend", "top_song", "rank_list", "artist_lists", "search", "search_suggest", "search_hot",
    "rank_audio", "artist_audios", "album_songs", "playlist_track_all_new", "user_cloud", "fm_songs", "playlist_track_all",
    "user_playlist", "fm_recommend", "artist_albums", "artist_detail", "album_detail", "user_detail", "song_url",
    "user_cloud_url", "search_lyric", "lyric", "login_qr_key", "login_qr_create", "login_qr_check", "__health",
    "privilege_lite",
)

// 桌面与安卓共用上游核心模块，客户端只承担领域对象映射和分页编排。
class KugouApi(private val core: CoreTransport, private val auth: () -> AuthCredentials?) {
    // 取流前读取当前会话，不能用展示昵称或会员图标推断登录状态。
    internal fun hasSession(): Boolean = auth()?.let { it.token.isNotBlank() && it.userId.isNotBlank() } == true

    // 以资源权益接口核实标准音质，响应缺项时返回未知，不凭列表的通用付费字段猜测。
    suspend fun memberships(tracks: List<Track>): List<TrackMembership> {
        require(tracks.size <= MEMBERSHIP_BATCH_SIZE)
        if (tracks.isEmpty()) return emptyList()
        val parameters = JSONObject().put("hash", tracks.joinToString(",") { it.hash })
            .put("album_id", tracks.joinToString(",") { it.albumId.toString() }).put("qualities", MEMBERSHIP_STANDARD_QUALITY)
        // 批量读取真实资源权益，并逐首校验音频和专辑身份。
        val resources = request("privilege_lite", parameters).optJSONArray("data")?.objects().orEmpty()
        return tracks.map { MembershipMapper.resolve(it, resources) }
    }

    // 只对明确的只读请求重试；账号变化或协程取消后立即终止。
    suspend fun request(module: String, parameters: JSONObject = JSONObject()): JSONObject {
        val credentials = auth()
        val attempts = if (module in RETRYABLE_READ_MODULES) READ_ATTEMPTS else 1
        repeat(attempts) { index ->
            if (auth() != credentials) throw CancellationException("账号已切换")
            try { return execute(module, parameters, credentials) }
            catch (error: MusicApiException) {
                if (!error.retryable || index == attempts - 1) throw error
                // 短暂退避后再读取，避免瞬时故障造成密集请求。
                delay(RETRY_DELAY_MS * (index + 1))
            }
        }
        throw MusicApiException(SERVER_ERROR_MESSAGE)
    }

    // 只在协议边界转换异常，不让技术堆栈或请求参数进入界面。
    private suspend fun execute(module: String, parameters: JSONObject, credentials: AuthCredentials?): JSONObject = try {
        core.execute(module, parameters, credentials?.json() ?: JSONObject()).requireSuccess()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: MusicApiException) { throw error }
    catch (_: Exception) { throw MusicApiException(SERVER_ERROR_MESSAGE, retryable = true) }

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

    // 只读取通用联想词分组，过滤空值与重复项，避免将歌曲或专辑详情混入输入建议。
    suspend fun searchSuggestions(keyword: String): List<SearchSuggestion> {
        val word = keyword.trim().takeIf(String::isNotEmpty) ?: return emptyList()
        val parameters = JSONObject().put("keywords", word).put("musicTipCount", SEARCH_SUGGESTION_LIMIT)
        // 通过已内置的酷狗模块请求真实联想词，空输入不会发起远程调用。
        val response = request("search_suggest", parameters)
        val group = response.optJSONArray("data")?.optJSONObject(0) ?: return emptyList()
        return group.rows("RecordDatas").map { it.text("HintInfo").trim() }
            .filter(String::isNotEmpty).distinct().take(SEARCH_SUGGESTION_LIMIT).map(::SearchSuggestion)
    }

    // 热搜只展示接口直接返回的词和说明，不把搜索结果猜作对应歌曲。
    suspend fun hotSearches(): List<HotSearch> {
        val groups = payload(request("search_hot")).rows("list")
        val rows = groups.firstOrNull { it.text("name") == "热搜榜" }?.rows("keywords")
            ?: groups.firstOrNull()?.rows("keywords").orEmpty()
        return rows.mapNotNull { row -> row.text("keyword").takeIf(String::isNotBlank)?.let {
            HotSearch(it, row.text("reason"))
        } }.distinctBy(HotSearch::keyword)
    }

    // 只为同名歌曲或歌手与歌名完整匹配的热词提供真实封面，不使用无关的首条结果。
    suspend fun hotSearchArtwork(term: HotSearch): String? {
        if (term.keyword.isBlank()) return null
        // 搜索独立于热词列表加载，图片缺失不会阻止热搜文字展示。
        val candidates = search(term.keyword, SearchKind.Song, PageRequest(size = HOT_ARTWORK_CANDIDATES)).tracks
        val track = KugouMapper.hotSearchTrack(term, candidates) ?: return null
        // 搜索未带图片时，沿用已有的专辑编号校验后补取封面。
        return track.coverUrl ?: artwork(track)
    }

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
            data.optionalNumber("fansnums", "fanscount"), data.optionalNumber("album_count", "albumcount"))
    }

    // 曲目列表缺少图片时按真实专辑编号补取封面，不用固定照片冒充其他歌曲。
    suspend fun artwork(track: Track): String? {
        track.coverUrl?.let { return it }
        if (track.albumId <= 0) return null
        val response = request("album_detail", JSONObject().put("id", track.albumId))
        val entries = response.optJSONArray("data")?.objects() ?: payload(response).rows("info", "list")
        val album = entries.firstOrNull { it.number("album_id") == track.albumId } ?: return null
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
