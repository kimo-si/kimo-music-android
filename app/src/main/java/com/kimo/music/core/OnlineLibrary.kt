package com.kimo.music.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kimo.music.data.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class SearchRequest(val keyword: String, val kind: SearchKind, val page: PageRequest,
    val append: Boolean, val generation: Int)
private data class FavoriteEdit(val track: Track, val desired: Boolean, val version: Long, val addedAtMs: Long,
    val onResult: (String) -> Unit, val awaitingConfirmation: Boolean? = null)
private enum class FavoriteResult { Obsolete, Unchanged, Synchronized, AwaitingConfirmation }
private const val HOT_ARTWORK_CONCURRENCY = 3

data class CatalogDetail(val entry: CatalogEntry, val songs: PagedFeed<Track>, val albums: PagedFeed<CatalogEntry>?,
    val profile: PagedFeed<ArtistProfile>? = null, val order: ArtistOrder = ArtistOrder.Hot)
data class CloudLibraryState(val playlists: List<CatalogEntry> = emptyList(), val favorites: List<Track> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val ownerId: String? = null, val fromCache: Boolean = false,
    val loaded: Boolean = false)

class OnlineLibrary internal constructor(private val api: KugouApi, private val scope: CoroutineScope,
    private val cache: CloudCache, private val currentOwner: () -> String?, private val sessionFailed: (String) -> Unit) {
    // 生产环境使用当前账号提供接口、会话身份和失效反馈。
    constructor(account: AccountModel, scope: CoroutineScope, cache: CloudCache) :
        this(account.api, scope, cache, { account.credentials?.userId }, account::sessionFailed)
    var knownTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var cloud by mutableStateOf(CloudLibraryState()); private set
    var detail by mutableStateOf<CatalogDetail?>(null); private set
    var search by mutableStateOf(SearchResult()); private set
    var searchLoading by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    private var searchJob: Job? = null
    private var searchKey: String? = null
    private var searchGeneration = 0
    private var syncJob: Job? = null
    private var mutationJob: Job? = null
    private val cloudMutex = Mutex()
    private var favoriteVersion = 0L
    private var favoriteEdits by mutableStateOf<List<FavoriteEdit>>(emptyList())
    private var lastSyncedOwner: String? = null
    val favoriteTracks by derivedStateOf { projectFavorites(cloud.favorites, favoriteEdits) }
    var loadingAll by mutableStateOf(false); private set
    val playlists = PagedFeed(scope, CatalogEntry::id) { page -> api.playlists(page).let { FeedPage(it.entries, it.nextPage) } }
    val newSongs = trackFeed { api.newSongs(it) }
    val recommendations = trackFeed { TrackPage(api.recommendations(), null) }
    val hotSearches = PagedFeed(scope, HotSearch::keyword) { FeedPage(api.hotSearches()) }
    private val hotArtworkCache = mutableMapOf<HotSearch, String?>()
    private val hotArtworkPermits = Semaphore(HOT_ARTWORK_CONCURRENCY)
    val charts = PagedFeed(scope) { FeedPage(api.charts()) }
    val artists = PagedFeed(scope) { FeedPage(api.artists()) }
    val radios = PagedFeed(scope) { FeedPage(api.radios()) }
    val memberships = TrackMemberships(api, scope)

    // 可见热词才补图；命中与无匹配结果均缓存，网络失败保留稍后重试机会。
    suspend fun hotSearchArtwork(term: HotSearch): String? = hotArtworkPermits.withPermit {
        if (hotArtworkCache.containsKey(term)) return@withPermit hotArtworkCache[term]
        try {
            // 限制并发后再访问远端，取消页面请求时不吞掉协程取消信号。
            api.hotSearchArtwork(term).also { hotArtworkCache[term] = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    // 歌曲以稳定标识合并，播放器持有的对象不因切换栏目丢失。
    fun register(tracks: List<Track>) { knownTracks = (knownTracks + tracks).distinctBy(Track::id) }

    // 封面补齐只修改对应曲目的图片，云端歌曲编号与收藏集合保持完整。
    fun updateArtwork(track: Track, cover: String) {
        knownTracks = knownTracks.map { if (it.id == track.id) it.copy(coverUrl = cover) else it }
        cloud = cloud.copy(favorites = cloud.favorites.map { if (it.id == track.id) it.copy(coverUrl = cover) else it })
    }

    // 详情按目录类型共用分页，同时保留歌手的专辑入口。
    fun open(entry: CatalogEntry, order: ArtistOrder = ArtistOrder.Hot) {
        closeDetail()
        val songs = trackFeed { page -> api.tracks(entry, page, order) }
        val albums = if (entry.kind == CatalogKind.Artist) PagedFeed(scope, CatalogEntry::id) { page ->
            api.artistAlbums(entry, page).let { FeedPage(it.entries, it.nextPage) }
        } else null
        val profile = if (entry.kind == CatalogKind.Artist) PagedFeed(scope) { FeedPage(listOf(api.artistProfile(entry))) } else null
        detail = CatalogDetail(entry, songs, albums, profile, order)
        songs.load()
        albums?.load()
        profile?.load()
    }

    // 离开详情取消旧分页，避免上个目录的响应覆盖新目录。
    fun closeDetail() { detail?.songs?.reset(); detail?.albums?.reset(); detail?.profile?.reset(); detail = null }

    // 展开歌单拥有独立分页，切换账号后的迟到内容不得进入共享曲库。
    fun playlistSongs(entry: CatalogEntry): PagedFeed<Track> {
        val owner = currentOwner()
        return PagedFeed(scope, Track::id) { page ->
            if (owner == null || currentOwner() != owner) return@PagedFeed FeedPage(emptyList())
            val result = api.tracks(entry, page)
            if (currentOwner() != owner) return@PagedFeed FeedPage(emptyList())
            register(result.tracks)
            FeedPage(result.tracks, result.nextPage)
        }
    }

    // 首页刷新互不依赖，重登后推荐使用新账号重新请求。
    fun refreshHome() { playlists.load(true); recommendations.load(true) }

    // 切换账号立即清除上个账号数据，完整同步成功后一次性替换云收藏与歌单。
    fun syncAccount() {
        syncJob?.cancel()
        val owner = currentOwner()
        // 同账号手动同步不能取消正在提交的收藏，切换账号时才丢弃旧操作。
        if (cloud.ownerId != owner) clearFavoriteEdits()
        if (cloud.ownerId != owner && detail?.entry?.kind in setOf(CatalogKind.CloudPlaylist, CatalogKind.Favorites, CatalogKind.CloudDrive)) closeDetail()
        if (owner == null) { lastSyncedOwner = null; cloud = CloudLibraryState(); return }
        if (cloud.ownerId != owner) { lastSyncedOwner = null; cloud = CloudLibraryState(ownerId = owner) }
        syncJob = scope.launch { synchronize(owner) }
        refreshHome()
    }

    // 启动时先恢复当前账号的快照，网络验证失败仍能看到此前同步的曲库。
    fun restoreCached() {
        val owner = currentOwner() ?: return
        scope.launch {
            val cached = withContext(Dispatchers.IO) { cache.load(owner) }
            if (cached != null && currentOwner() == owner && lastSyncedOwner != owner) {
                register(cached.favorites)
                cloud = cached.copy(loading = cloud.loading, error = cloud.error)
            }
        }
    }

    // 播放全部先取得完整分页结果，不把当前页冒充完整歌单。
    fun playAll(entry: CatalogEntry, onReady: (List<Track>) -> Unit, onResult: (String) -> Unit) {
        if (loadingAll) return
        scope.launch {
            loadingAll = true
            try {
                val tracks = api.allTracks(entry)
                register(tracks)
                if (tracks.isEmpty()) onResult("这个目录还没有可播放的歌曲") else onReady(tracks)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onResult(error.userMessage()) }
            finally { loadingAll = false }
        }
    }

    // 同步失败保留当前账号已有内容，并公开错误和重试入口。
    private suspend fun synchronize(owner: String): Boolean {
        // 全量读取与收藏写入串行化，避免旧快照覆盖正在提交的变更。
        val synchronized = cloudMutex.withLock { synchronizeLocked(owner) }
        if (synchronized) reconcileConfirmedEdits(owner)
        return synchronized
    }

    // 持锁读取完整歌单和收藏，只有成功结果才能替换账号快照。
    private suspend fun synchronizeLocked(owner: String): Boolean {
        if (currentOwner() != owner) return false
        cloud = cloud.copy(loading = true, error = null)
        try {
            val lists = api.userPlaylists()
            val favoriteList = lists.firstOrNull { it.kind == CatalogKind.Favorites }
            val favorites = favoriteList?.let { api.allTracks(it) }.orEmpty()
            if (currentOwner() != owner) return false
            register(favorites)
            val synchronized = CloudLibraryState(lists, favorites, ownerId = owner, loaded = true)
            lastSyncedOwner = owner
            cloud = synchronized
            withContext(Dispatchers.IO) { cache.save(synchronized) }
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (currentOwner() != owner) return false
            cloud = cloud.copy(loading = false, error = error.userMessage())
            if ((error as? MusicApiException)?.expired == true) sessionFailed(error.userMessage())
            return false
        }
    }

    // hash 可能在云收藏里变化，使用 mixsongid 辅助匹配同一首歌。
    fun liked(track: Track): Boolean = favoriteTracks.any { matches(it, track) }

    // 点按先更新共享界面状态，后台按顺序提交酷狗，失败后恢复原状态。
    fun toggleFavorite(track: Track, onResult: (String) -> Unit) {
        val owner = currentOwner()
        if (owner == null || cloud.ownerId != owner || cloud.playlists.none { it.kind == CatalogKind.Favorites }) {
            onResult("请先登录，稍后重试"); return
        }
        val previous = favoriteEdit(track)
        val edit = FavoriteEdit(track, !liked(track), ++favoriteVersion, System.currentTimeMillis(), onResult,
            previous?.awaitingConfirmation)
        register(listOf(track))
        replaceFavoriteEdit(edit)
        // 已写入但未取回云端编号时，先重新读取，再决定是否执行后续点按。
        if (edit.awaitingConfirmation != null) requestFavoriteRefresh(owner) else startFavoriteWorker(owner)
    }

    // 收藏视图叠加待提交操作；云端快照只保存服务端确认的完整结果。
    private fun projectFavorites(base: List<Track>, edits: List<FavoriteEdit>): List<Track> = edits.fold(base) { tracks, edit ->
        val stored = tracks.firstOrNull { matches(it, edit.track) }
        when {
            !edit.desired -> tracks.filterNot { matches(it, edit.track) }
            stored == null -> tracks + edit.track.copy(favoriteAddedAtMs = edit.addedAtMs)
            else -> tracks
        }
    }

    // 同一首歌的多次点击合并为最新意图，其他歌曲仍按顺序提交。
    private fun replaceFavoriteEdit(edit: FavoriteEdit) {
        favoriteEdits = favoriteEdits.filterNot { matches(it.track, edit.track) } + edit
    }

    // 仅启动一个队列处理器，避免重复写入或丢弃其他歌曲的点击。
    private fun startFavoriteWorker(owner: String) {
        if (mutationJob?.isActive == true) return
        mutationJob = scope.launch { processFavoriteEdits(owner) }
    }

    // 等待确认的歌曲暂停写入，队列中的其他歌曲仍可继续同步。
    private suspend fun processFavoriteEdits(owner: String) {
        while (currentOwner() == owner) {
            val edit = favoriteEdits.firstOrNull { it.awaitingConfirmation == null } ?: return
            processFavoriteEdit(owner, edit)
        }
    }

    // 失败只回滚当前版本，不能撤销用户在请求期间做出的新选择。
    private suspend fun processFavoriteEdit(owner: String, edit: FavoriteEdit) {
        try {
            val result = commitFavoriteEdit(owner, edit)
            finishFavoriteEdit(owner, edit, result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (currentOwner() != owner) return
            if (favoriteEdit(edit.track)?.version == edit.version) {
                removeFavoriteEdit(edit.track)
                edit.onResult(error.userMessage())
            }
            if ((error as? MusicApiException)?.expired == true) sessionFailed(error.userMessage())
        }
    }

    // 写入与后续完整读取处于同一互斥区，取消收藏始终使用服务端 fileid。
    private suspend fun commitFavoriteEdit(owner: String, edit: FavoriteEdit): FavoriteResult = cloudMutex.withLock {
        if (currentOwner() != owner) throw CancellationException("账号已切换")
        if (favoriteEdit(edit.track)?.version != edit.version) return@withLock FavoriteResult.Obsolete
        val stored = cloud.favorites.firstOrNull { matches(it, edit.track) }
        if ((stored != null) == edit.desired) return@withLock FavoriteResult.Unchanged
        val entry = cloud.playlists.firstOrNull { it.kind == CatalogKind.Favorites }
            ?: throw MusicApiException("请先登录，稍后重试")
        // 将当前选择写入酷狗；成功前不覆盖服务端确认的曲库快照。
        if (edit.desired) api.addTrack(entry, edit.track) else api.removeTrack(entry, requireNotNull(stored))
        // 写入已确认即可反馈成功，不让完整曲库刷新拖延本次操作的提示。
        notifyFavoriteCommitted(owner, edit)
        // 获取服务端权威列表及真实 fileid，供下一次取消收藏使用。
        val synced = synchronizeLocked(owner)
        if (synced && cloud.favorites.any { matches(it, edit.track) } == edit.desired)
            FavoriteResult.Synchronized else FavoriteResult.AwaitingConfirmation
    }

    // 只反馈当前账号仍然有效的最新意图，快速改选时不展示过时操作的成功提示。
    private fun notifyFavoriteCommitted(owner: String, edit: FavoriteEdit) {
        if (currentOwner() != owner || favoriteEdit(edit.track)?.version != edit.version) return
        edit.onResult(if (edit.desired) "已加入我喜欢" else "已取消喜欢")
    }

    // 服务端已写入但列表暂未确认时保留界面状态，避免把成功操作误当失败。
    private fun finishFavoriteEdit(owner: String, edit: FavoriteEdit, result: FavoriteResult) {
        if (currentOwner() != owner || result == FavoriteResult.Obsolete) return
        val latest = favoriteEdit(edit.track) ?: return
        if (result == FavoriteResult.AwaitingConfirmation) {
            replaceFavoriteEdit(latest.copy(awaitingConfirmation = edit.desired))
            latest.onResult("收藏已提交，列表待确认")
        } else if (latest.version == edit.version) {
            removeFavoriteEdit(edit.track)
        }
        if (result == FavoriteResult.Synchronized) reconcileConfirmedEdits(owner)
    }

    // 重新同步确认服务器状态后再处理用户在等待期间追加的操作。
    private fun reconcileConfirmedEdits(owner: String) {
        if (currentOwner() != owner) return
        favoriteEdits = favoriteEdits.mapNotNull { edit ->
            val confirmed = edit.awaitingConfirmation ?: return@mapNotNull edit
            val stored = cloud.favorites.any { matches(it, edit.track) }
            if (stored != confirmed) edit else if (stored == edit.desired) null else edit.copy(awaitingConfirmation = null)
        }
        startFavoriteWorker(owner)
    }

    // 待确认时再次点击会触发重新读取，失败则提示用户稍后手动同步。
    private fun requestFavoriteRefresh(owner: String) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            if (!synchronize(owner) && currentOwner() == owner)
                favoriteEdits.firstOrNull { it.awaitingConfirmation != null }?.onResult(SERVER_ERROR_MESSAGE)
        }
    }

    // 账号切换后取消旧请求并清除临时界面状态，避免旧账号结果污染新账号。
    private fun clearFavoriteEdits() { mutationJob?.cancel(); mutationJob = null; favoriteEdits = emptyList() }

    // 使用音频身份而非页面行对象定位待同步操作。
    private fun favoriteEdit(track: Track): FavoriteEdit? = favoriteEdits.firstOrNull { matches(it.track, track) }

    // 移除已完成或失败的操作后，界面自然回到已确认的云端快照。
    private fun removeFavoriteEdit(track: Track) { favoriteEdits = favoriteEdits.filterNot { matches(it.track, track) } }

    // 在线搜索通过请求代次隔离，后输入的关键词不会被旧结果覆盖。
    fun search(keyword: String, kind: SearchKind, more: Boolean = false) {
        if (keyword.isBlank()) return
        val key = "${kind.name}:${keyword.trim()}"
        val append = more && searchKey == key
        val page = if (append) search.nextPage ?: return else 1
        searchJob?.cancel()
        searchKey = key
        val request = SearchRequest(keyword.trim(), kind, PageRequest(page), append, ++searchGeneration)
        if (!append) search = SearchResult()
        searchJob = scope.launch { performSearch(request) }
    }

    // 旧请求的结果、错误与结束事件都不能覆盖新的关键词或分类。
    private suspend fun performSearch(request: SearchRequest) {
        searchLoading = true; searchError = null
        try {
            val result = api.search(request.keyword, request.kind, request.page)
            if (request.generation != searchGeneration) return
            register(result.tracks)
            search = if (request.append) SearchResult((search.tracks + result.tracks).distinctBy(Track::id),
                (search.entries + result.entries).distinctBy(CatalogEntry::id), result.nextPage) else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (request.generation == searchGeneration) searchError = error.userMessage() }
        finally { if (request.generation == searchGeneration) searchLoading = false }
    }

    // 歌单操作统一完成云端写入和后续同步，界面无需自行维护影子歌单。
    fun changePlaylist(entry: CatalogEntry?, name: String?, delete: Boolean = false, onResult: (String) -> Unit) {
        scope.launch {
            try {
                when {
                    delete && entry != null -> api.deletePlaylist(entry)
                    entry != null && !name.isNullOrBlank() -> api.renamePlaylist(entry, name.trim())
                    entry == null && !name.isNullOrBlank() -> api.createPlaylist(name.trim())
                    else -> throw MusicApiException("请输入歌单名称")
                }
                val synced = currentOwner()?.let { synchronize(it) } == true
                onResult(if (synced) "歌单已更新" else "歌单已更新，列表暂未刷新")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onResult(error.userMessage()) }
        }
    }

    // 添加到指定云歌单后刷新账号目录，曲目集合仍由服务端确认。
    fun addToPlaylist(entry: CatalogEntry, track: Track, onResult: (String) -> Unit) {
        scope.launch {
            try { api.addTrack(entry, track); onResult("已加入${entry.title}"); syncAccount() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { onResult(error.userMessage()) }
        }
    }

    // 各个曲目栏目复用加载器和播放器对象登记。
    private fun trackFeed(loader: suspend (PageRequest) -> TrackPage): PagedFeed<Track> = PagedFeed(scope, Track::id) { page ->
        loader(page).let { register(it.tracks); FeedPage(it.tracks, it.nextPage) }
    }

    // 同一音频的多个云端 hash 使用有效 mixsongid 对齐。
    private fun matches(first: Track, second: Track): Boolean = (first.hash.isNotBlank() && first.hash.equals(second.hash, true)) ||
        (first.mixSongId > 0 && first.mixSongId == second.mixSongId)
}
