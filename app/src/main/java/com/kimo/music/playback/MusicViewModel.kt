package com.kimo.music.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.FavoriteOrdering
import com.kimo.music.data.FavoriteIndex
import com.kimo.music.data.LibraryStore
import com.kimo.music.data.LocalAudioReader
import com.kimo.music.data.Track
import com.kimo.music.data.online
import com.kimo.music.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.net.URL
import kotlin.coroutines.CoroutineContext

data class PlaybackState(
    val track: Track = DemoCatalog.tracks.first(),
    val playing: Boolean = false,
    val ready: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = track.durationMs,
    val queue: List<Track> = DemoCatalog.tracks,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val volume: Float = 0.8f,
    val sleepTimerRemainingMs: Long = 0,
)

private data class ImportResult(val tracks: List<Track>, val failed: Int)
private data class PlaybackRequest(val track: Track, val collection: List<Track>, val quality: AudioQuality)
private data class PreparedQueue(val tracks: List<Track>, val items: List<MediaItem>, val startIndex: Int)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private var controller: MediaController? = null
    private val connection = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private var recordedId: String? = null
    private var queueDirty = true
    private var queueIndex: Map<String, Track>? = null
    private var selectionJob: Job? = null
    private var pendingSelection: PlaybackRequest? = null
    var playback by mutableStateOf(PlaybackState()); private set
    private var localFavorites by mutableStateOf(store.favorites)
    private var localFavoriteMarks by mutableStateOf(store.favoriteMarks())
    var localTracks by mutableStateOf(store.localTracks()); private set
    var recentIds by mutableStateOf(store.recentIds); private set
    var searchHistory by mutableStateOf(store.searchHistory); private set
    private var searchHistoryWrite: Job? = null
    var message by mutableStateOf<String?>(null); private set
    val account = AccountModel(application, viewModelScope)
    val online = OnlineLibrary(account, viewModelScope, CloudCache(application))
    var quality by mutableStateOf(AudioQuality.Standard); private set
    var loginRequested by mutableStateOf(false)
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList()); private set
    var lyricsLoading by mutableStateOf(false); private set
    var lyricsError by mutableStateOf<String?>(null); private set
    private var lyricsTrackId: String? = null
    private var lyricsJob: Job? = null
    private var artworkTrackId: String? = null
    private var artworkJob: Job? = null
    val tracks: List<Track> by derivedStateOf { (DemoCatalog.tracks + localTracks + online.knownTracks).distinctBy(Track::id) }
    private val trackIndex by derivedStateOf { tracks.associateBy(Track::id) }
    // 全量同步完成后统一按收藏时间倒序，个人页主卡与播放全部共享同一顺序。
    val favoriteTracks: List<Track> by derivedStateOf { FavoriteOrdering.newestFirst(online.favoriteTracks,
        (DemoCatalog.tracks + localTracks).filter { it.id in localFavorites }, localFavoriteMarks) }
    val favorites: Set<String> by derivedStateOf {
        val index = FavoriteIndex(online.favoriteTracks)
        localFavorites + tracks.filter(index::contains).map(Track::id)
    }
    val recentTracks: List<Track> by derivedStateOf { recentIds.mapNotNull(trackIndex::get) }

    // 等待媒体服务就绪后建立初始队列，启动时保持暂停避免突然出声。
    private fun connect() {
        connection.addListener({
            runCatching { connection.get() }.onSuccess { player ->
                controller = player
                player.addListener(playerListener)
                if (player.mediaItemCount == 0) {
                    // 初始队列装载内置演示曲，音频完全离线可用。
                    player.setMediaItems(DemoCatalog.tracks.map { mediaItem(it) })
                    player.volume = playback.volume
                    player.prepare()
                }
                snapshot()
            }.onFailure { message = "播放器暂时无法启动，请重新打开应用" }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private val playerListener = object : Player.Listener {
        // 队列、播放模式和状态变化都映射为同一份不可变界面状态。
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueDirty = true
            snapshot()
        }

        // 实际播放失败时明确反馈，禁止让界面显示虚假的播放中状态。
        override fun onPlayerError(error: PlaybackException) {
            val apiError = generateSequence(error as Throwable) { it.cause }.filterIsInstance<MusicApiException>().firstOrNull()
            message = apiError?.message ?: if (playback.track.online) "在线音频加载失败，请检查网络、账号和音质" else "这首音频无法播放，请检查文件是否仍可读取"
            if (apiError?.expired == true) { account.sessionFailed(apiError.message.orEmpty()); loginRequested = true }
            if (playback.track.online && account.credentials == null) loginRequested = true
            snapshot()
        }
    }

    init {
        account.onSessionChanged = online::syncAccount
        account.onRestored = online::restoreCached
        online.register(store.onlineTracks())
        connect()
        observePosition()
    }

    // 仅在模型存活时刷新进度，所有播放器访问都在主线程执行。
    private fun observePosition() {
        viewModelScope.launch {
            while (isActive) { snapshot(); delay(POSITION_INTERVAL_MS) }
        }
    }

    // 读取媒体会话的真实时长与状态，未知时长回退到已解析的曲目元数据。
    private fun snapshot() {
        val player = controller ?: return
        val current = trackIndex[player.currentMediaItem?.mediaId] ?: playback.track
        val queue = queueTracks(player)
        playback = PlaybackState(current, player.isPlaying, true, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it > 0 } ?: current.durationMs, queue,
            player.shuffleModeEnabled, player.repeatMode, player.volume,
            (player.sessionExtras.getLong(PlaybackCommands.TIMER_DEADLINE) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        // 只有真正开始播放时才写入历史，反复进出页面不会伪造记录。
        if (player.isPlaying && recordedId != current.id) record(current)
        if (lyricsTrackId != current.id) loadLyrics(current)
        if (artworkTrackId != current.id) loadArtwork(current)
    }

    // 只有媒体时间线或歌曲元数据变化才重建队列，400ms 进度刷新复用已有对象。
    private fun queueTracks(player: Player): List<Track> {
        val index = trackIndex
        if (!queueDirty && queueIndex === index) return playback.queue
        queueIndex = index
        queueDirty = false
        return (0 until player.mediaItemCount).mapNotNull { index[player.getMediaItemAt(it).mediaId] }
    }

    // 播放一个强类型歌曲集合，防止空队列与无效起始索引。
    fun play(track: Track, collection: List<Track> = tracks) {
        val player = controller ?: return
        val request = PlaybackRequest(track, collection, quality)
        // 取消过期的准备任务，快速点歌只提交最后一次请求。
        cancelPendingSelection()
        pendingSelection = request
        selectionJob = viewModelScope.launch {
            try {
                // 长列表去重和媒体项创建交给后台线程，不阻塞音符和列表动画。
                val prepared = withContext(Dispatchers.Default) { prepareQueue(request, coroutineContext) }
                // 完整结果返回主线程后再提交，准备期间保持旧队列可用。
                commitQueue(player, prepared)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "播放队列准备失败，请重试" }
            finally { if (pendingSelection === request) pendingSelection = null }
        }
    }

    // 后台只构造不可变对象，音质取请求时的快照，不在工作线程访问媒体控制器。
    private fun prepareQueue(request: PlaybackRequest, context: CoroutineContext): PreparedQueue {
        val source = request.collection.takeIf { tracks -> tracks.any { it.id == request.track.id } } ?: listOf(request.track)
        val queue = source.distinctBy(Track::id)
        // 逐项响应取消，快速改选时不继续消耗 CPU 构建已经过期的媒体项。
        val items = queue.map { context.ensureActive(); mediaItem(it, request.quality) }
        return PreparedQueue(queue, items, queue.indexOfFirst { it.id == request.track.id })
    }

    // 媒体会话仅在主线程提交完整结果，随后由同一服务准备和播放真实音频。
    private fun commitQueue(player: MediaController, prepared: PreparedQueue) {
        online.register(prepared.tracks.filter(Track::online))
        queueDirty = true
        player.setMediaItems(prepared.items, prepared.startIndex, 0)
        player.prepare()
        player.play()
    }

    // 使已取消的后台结果无法覆盖新的播放操作。
    private fun cancelPendingSelection() { selectionJob?.cancel(); pendingSelection = null }

    // 切换媒体服务中的真实播放状态。
    fun togglePlaying() {
        val player = controller ?: return
        if (pendingSelection != null) {
            if (player.isPlaying) { cancelPendingSelection(); player.pause() }
            return
        }
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    // 切歌在列表首尾安全循环，单曲队列从头重新播放。
    fun skip(forward: Boolean) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        cancelPendingSelection()
        val delta = if (forward) 1 else -1
        val index = (player.currentMediaItemIndex + delta + player.mediaItemCount) % player.mediaItemCount
        player.seekTo(index, 0)
    }

    // 根据当前有效时长约束跳转位置。
    fun seek(positionMs: Long) {
        controller?.seekTo(positionMs.coerceIn(0, playback.durationMs.coerceAtLeast(0)))
        snapshot()
    }

    // 随机开关直接控制 Media3 的队列遍历顺序。
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    // 列表循环、单曲循环和顺序播放按固定顺序轮换。
    fun cycleRepeat() {
        controller?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_ALL
            }
        }
    }

    // 将有限的音量值传给会话，避免异常浮点输入。
    fun setVolume(volume: Float) {
        if (volume.isFinite()) controller?.volume = volume.coerceIn(0f, 1f)
    }

    // 收藏只保存歌曲标识，所有页面即时共享同一集合。
    fun toggleFavorite(track: Track) {
        if (track.online) {
            if (account.credentials == null) { loginRequested = true; return }
            online.toggleFavorite(track, ::notice)
            return
        }
        localFavorites = if (track.id in localFavorites) localFavorites - track.id else localFavorites + track.id
        // 同步持久化排序标记，取消后重新收藏会立即回到列表最前。
        store.saveFavorites(localFavorites)
        localFavoriteMarks = store.favoriteMarks()
    }

    // 去重后追加当前播放队列，并明确反馈添加结果。
    fun enqueue(track: Track) {
        val player = controller ?: return
        pendingSelection?.let { request ->
            val source = request.collection.takeIf { rows -> rows.any { it.id == request.track.id } } ?: listOf(request.track)
            if (source.any { it.id == track.id }) message = "已经在播放列表里了"
            else { play(request.track, source + track); message = "已加入播放列表" }
            return
        }
        if (playback.queue.any { it.id == track.id }) { message = "已经在播放列表里了"; return }
        queueDirty = true
        player.addMediaItem(mediaItem(track))
        message = "已加入播放列表"
    }

    // 保留现有队列，从所选歌曲继续播放。
    fun selectQueued(track: Track) {
        val index = playback.queue.indexOfFirst { it.id == track.id }
        if (index < 0) return
        cancelPendingSelection()
        controller?.run { seekTo(index, 0); play() }
    }

    // 记录已开始播放的歌曲，同时更新最近常听页面。
    private fun record(track: Track) {
        recordedId = track.id
        store.record(track)
        if (track.online) store.rememberOnlineTrack(track)
        recentIds = store.recentIds
    }

    // 将文件读取与媒体元数据解析放在 IO 线程中完成。
    fun importAudio(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { readAudio(uris) }
            val merged = (localTracks + result.tracks).distinctBy(Track::id)
            withContext(Dispatchers.IO) { store.saveLocalTracks(merged) }
            localTracks = merged
            message = if (result.failed == 0) "已导入 ${result.tracks.size} 首音乐"
                else "已导入 ${result.tracks.size} 首，${result.failed} 个文件无法读取"
        }
    }

    // 批量解析时隔离损坏文件，一个失败不影响其他歌曲导入。
    private fun readAudio(uris: List<Uri>): ImportResult {
        val reader = LocalAudioReader(getApplication())
        val results = uris.map { uri -> runCatching { reader.read(uri) } }
        return ImportResult(results.mapNotNull { it.getOrNull() }, results.count { it.isFailure })
    }

    // 通过系统文档选择器导出用户选定的音频，复制工作在 IO 线程执行。
    fun exportAudio(track: Track, destination: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { copyAudio(track, destination) } }
            message = if (result.isSuccess) "已保存到所选位置" else "保存失败，请选择可写入的位置"
        }
    }

    // 按媒体 URI 的来源打开流，保持导出文件的原始格式。
    private suspend fun copyAudio(track: Track, destination: Uri) {
        val context = getApplication<Application>()
        val uri = track.mediaUri.toUri()
        val input = when {
            track.online -> onlineStream(track)
            uri.scheme == "asset" -> context.assets.open(uri.path.orEmpty().trimStart('/'))
            else -> context.contentResolver.openInputStream(uri)
        }
        requireNotNull(input).use { source ->
            requireNotNull(context.contentResolver.openOutputStream(destination)).use { target -> source.copyTo(target) }
        }
    }

    // 消费一次性提示，避免页面重组重复弹出。
    fun clearMessage() { message = null }

    // 云端操作统一通过播放器已有提示通道反馈。
    fun notice(text: String) { message = text }

    // 实际提交搜索后才写入最近搜索，重复词移到最前，不影响账号收藏。
    fun rememberSearch(keyword: String) {
        val normalized = keyword.trim().takeIf(String::isNotEmpty) ?: return
        updateSearchHistory((listOf(normalized) + searchHistory).distinct().take(SEARCH_HISTORY_LIMIT))
    }

    // 清空仅处理本机搜索历史，不清除播放历史或云端数据。
    fun clearSearchHistory() { updateSearchHistory(emptyList()) }

    // 后台按顺序保存快照，避免快速搜索和清空操作互相覆盖。
    private fun updateSearchHistory(values: List<String>) {
        searchHistory = values
        val previous = searchHistoryWrite
        searchHistoryWrite = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            store.saveSearchHistory(values)
        }
    }

    // 设置通过媒体会话交给后台服务，界面销毁不会取消已经接受的停止任务。
    fun setSleepTimer(request: SleepTimerRequest) {
        val player = controller ?: return
        val arguments = Bundle().apply { putLong(PlaybackCommands.TIMER_DURATION, request.durationMs) }
        val response = player.sendCustomCommand(PlaybackCommands.SleepTimer, arguments)
        response.addListener({
            val accepted = runCatching { response.get().resultCode == SessionResult.RESULT_SUCCESS }.getOrDefault(false)
            if (!accepted) message = "定时设置失败，请重试"
            snapshot()
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    // 音质变化只替换当前媒体项，保留整条播放队列和进度。
    fun changeQuality(selected: AudioQuality) {
        quality = selected
        pendingSelection?.let { request -> play(request.track, request.collection); return }
        val player = controller ?: return
        if (!playback.track.online) return
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(playback.track))
        player.seekTo(position)
        player.prepare()
        player.playWhenReady = playing
    }

    // 切歌取消上一首歌词请求，空歌词与网络错误分别反馈。
    private fun loadLyrics(track: Track) {
        lyricsTrackId = track.id
        lyricsJob?.cancel()
        lyrics = emptyList(); lyricsError = null; lyricsLoading = track.online
        if (!track.online) return
        lyricsJob = viewModelScope.launch {
            try { lyrics = KugouPlaybackApi(account.api).lyrics(track) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { lyricsError = error.message ?: "歌词加载失败" }
            finally { lyricsLoading = false }
        }
    }

    // 歌词加载失败时允许用户对当前歌曲重试。
    fun retryLyrics() { loadLyrics(playback.track) }

    // 切歌取消旧封面任务；在线按专辑补图，本地只提取音频自己的内嵌图片。
    private fun loadArtwork(track: Track) {
        artworkTrackId = track.id
        artworkJob?.cancel()
        if (track.coverUrl != null || (!track.online && !track.local)) return
        artworkJob = viewModelScope.launch {
            try {
                val cover = if (track.local) withContext(Dispatchers.IO) {
                    LocalAudioReader(getApplication()).read(track.mediaUri.toUri()).coverUrl
                } else account.api.artwork(track)
                if (cover == null) return@launch
                if (track.local) {
                    localTracks = localTracks.map { if (it.id == track.id) it.copy(coverUrl = cover) else it }
                    val updated = localTracks
                    withContext(Dispatchers.IO) { store.saveLocalTracks(updated) }
                } else online.updateArtwork(track, cover)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 图片不可用时保留中性占位，不影响音频播放。 */ }
        }
    }

    // 导出在线音频仍遵循当前账号可取得的地址和音质。
    private suspend fun onlineStream(track: Track): java.io.InputStream {
        val stream = KugouPlaybackApi(account.api).stream(track, quality)
        val connection = URL(stream.url).openConnection().apply { connectTimeout = 15_000; readTimeout = 15_000 }
        return connection.getInputStream()
    }

    // 释放界面控制器连接，后台媒体服务仍按实际播放状态管理生命周期。
    override fun onCleared() {
        MediaController.releaseFuture(connection)
        super.onCleared()
    }

    // 系统媒体通知使用真实曲目信息和应用内封面资源。
    private fun mediaItem(track: Track, selectedQuality: AudioQuality = quality): MediaItem = MediaItem.Builder().setMediaId(track.id)
        .setUri(if (track.online) KugouDataSource.uri(track, selectedQuality) else track.mediaUri.toUri())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title)
            .setArtist(track.artist).setAlbumTitle(track.album)
            .setArtworkUri(artworkUri(track))
            .build()).build()

    // 缺少真实封面的在线或本地歌曲不向系统通知提供其他歌曲的图片。
    private fun artworkUri(track: Track): Uri? {
        track.coverUrl?.let { return it.toUri() }
        if (track.online || track.local) return null
        return "android.resource://${getApplication<Application>().packageName}/${track.artwork}".toUri()
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 400L
        const val SEARCH_HISTORY_LIMIT = 12
    }
}
