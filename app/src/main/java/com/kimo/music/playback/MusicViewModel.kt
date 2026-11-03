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
import com.kimo.music.data.FavoriteOrdering
import com.kimo.music.data.FavoriteIndex
import com.kimo.music.data.AudioCredits
import com.kimo.music.data.LibraryStore
import com.kimo.music.data.AppearanceMode
import com.kimo.music.data.ListeningPreferences
import com.kimo.music.data.LocalAudioReader
import com.kimo.music.data.Track
import com.kimo.music.data.online
import com.kimo.music.core.*
import com.kimo.music.downloads.AudioDownloadService
import com.kimo.music.downloads.DownloadRepository
import com.kimo.music.downloads.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL
import kotlin.coroutines.CoroutineContext

data class PlaybackState(
    val track: Track? = null,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<Track> = emptyList(),
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val volume: Float = 0.8f,
    val sleepTimerRemainingMs: Long = 0,
)

private data class ImportResult(val tracks: List<Track>, val failed: Int)
private data class PlaybackRequest(val track: Track, val collection: List<Track>, val quality: AudioQuality)
private data class PreparedQueue(val tracks: List<Track>, val items: List<MediaItem>, val startIndex: Int)
enum class NoticeAction(val title: String) { Retry("重试"), Login("去登录"), Downloads("查看下载") }

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val localLibraryLock = Mutex()
    val listening = ListeningPreferences(application)
    val downloads = DownloadRepository.get(application)
    val updates = ReleaseUpdates(application, viewModelScope)
    var downloadedTracks by mutableStateOf<List<Track>>(emptyList()); private set
    var downloadsRequested by mutableStateOf(false)
    var localLibraryRequested by mutableStateOf(false)
    var soundSettingsRequested by mutableStateOf(false)
    var aboutRequested by mutableStateOf(false)
    var notificationPromptRequested by mutableStateOf(false)
    var trackActionsRequested by mutableStateOf<Track?>(null)
    var equalizer by mutableStateOf(EqualizerStatus()); private set
    private var equalizerSource: String? = null
    private var controller: MediaController? = null
    private val connection = MediaController.Builder(application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
    private var recordedId: String? = null
    private var queueDirty = true
    private var queueIndex: Map<String, Track>? = null
    private var selectionJob: Job? = null
    private var pendingSelection by mutableStateOf<PlaybackRequest?>(null)
    val preparingTrack: Track? get() = pendingSelection?.track
    var playback by mutableStateOf(PlaybackState()); private set
    private var localFavorites by mutableStateOf(store.favorites)
    private var localFavoriteMarks by mutableStateOf(store.favoriteMarks())
    var localTracks by mutableStateOf(store.localTracks()); private set
    private var sessionTracks by mutableStateOf<List<Track>>(emptyList())
    var recentIds by mutableStateOf(store.recentIds); private set
    var searchHistory by mutableStateOf(store.searchHistory); private set
    private var searchHistoryWrite: Job? = null
    private var messageValue by mutableStateOf<String?>(null)
    var noticeAction by mutableStateOf<NoticeAction?>(null); private set
    var message: String?
        get() = messageValue
        private set(value) { messageValue = value; noticeAction = null }
    val account = AccountModel(application, viewModelScope)
    val online = OnlineLibrary(account, viewModelScope, CloudCache(application))
    var quality by mutableStateOf(listening.options.quality); private set
    var appearance by mutableStateOf(store.appearance); private set
    var loginRequested by mutableStateOf(false)
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList()); private set
    var lyricsLoading by mutableStateOf(false); private set
    var lyricsError by mutableStateOf<String?>(null); private set
    private var lyricsTrackId: String? = null
    private var lyricsJob: Job? = null
    private var artworkTrackId: String? = null
    private var artworkJob: Job? = null
    val tracks: List<Track> by derivedStateOf { (localTracks + downloadedTracks + online.knownTracks + sessionTracks).distinctBy(Track::id) }
    private val trackIndex by derivedStateOf { tracks.associateBy(Track::id) }
    // 全量同步完成后统一按收藏时间倒序，个人页主卡与播放全部共享同一顺序。
    val favoriteTracks: List<Track> by derivedStateOf { FavoriteOrdering.newestFirst(online.favoriteTracks,
        (localTracks + downloadedTracks).distinctBy(Track::id).filter { it.id in localFavorites }, localFavoriteMarks) }
    val favorites: Set<String> by derivedStateOf {
        val index = FavoriteIndex(online.favoriteTracks)
        localFavorites + tracks.filter(index::contains).map(Track::id)
    }
    val recentTracks: List<Track> by derivedStateOf { recentIds.mapNotNull(trackIndex::get) }

    // 全应用共用外观状态，保存后旋转和重新启动仍恢复用户选择。
    fun changeAppearance(mode: AppearanceMode) {
        if (appearance == mode) return
        // 持久化外观偏好，再通知各页面采用相同主题。
        store.saveAppearance(mode)
        appearance = mode
    }

    // 连接已有媒体会话，空队列保持为空，不预设歌曲或触发播放。
    private fun connect() {
        connection.addListener({
            runCatching { connection.get() }.onSuccess { player ->
                controller = player
                player.addListener(playerListener)
                snapshot()
                // 连接尚未完成时的点歌请求，在会话就绪后继续执行。
                pendingSelection?.let { startSelection(player, it) }
            }.onFailure { pendingSelection = null; message = "播放器暂时无法启动，请重新打开应用" }
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
            val onlineTrack = playback.track?.online == true
            val vipLoginRequired = error.errorCode == KugouDataSource.VIP_LOGIN_REQUIRED_ERROR_CODE
            message = if (vipLoginRequired) VIP_LOGIN_REQUIRED_MESSAGE else apiError?.userMessage()
                ?: if (onlineTrack) SERVER_ERROR_MESSAGE else "这首音频无法播放，请检查文件是否仍可读取"
            if (apiError?.expired == true) { account.sessionFailed(apiError.userMessage()); loginRequested = true }
            if (vipLoginRequired || (onlineTrack && account.credentials == null)) loginRequested = true
            noticeAction = if (vipLoginRequired || apiError?.expired == true) NoticeAction.Login else NoticeAction.Retry
            snapshot()
        }
    }

    init {
        account.onSessionChanged = online::syncAccount
        account.onRestored = online::restoreCached
        online.register(store.onlineTracks())
        connect()
        observePosition()
        // 下载完成后即时合入本地曲库，离线播放无需重新导入文件。
        viewModelScope.launch { downloads.items.collect { rows ->
            downloadedTracks = rows.filter { it.status == DownloadStatus.Complete }.map(downloads::localTrack)
        } }
        // 旧缓存中的固定占位先隐藏，再从用户文件核对真实标签。
        verifyLegacyLocalMetadata()
    }

    // 逐个在后台读取旧本地音频；无法读取时保留原记录和空显示。
    private fun verifyLegacyLocalMetadata() {
        val pending = localTracks.filter(Track::needsMetadataVerification)
        if (pending.isEmpty()) return
        viewModelScope.launch {
            val reader = LocalAudioReader(getApplication())
            pending.forEach { track ->
                val credits = withContext(Dispatchers.IO) { runCatching { reader.readCredits(track.mediaUri.toUri()) }.getOrNull() }
                if (credits != null) applyVerifiedCredits(track.id, credits)
            }
        }
    }

    // 核对完成时只更新仍待确认的条目，避免覆盖过程中发生的其他修改。
    private suspend fun applyVerifiedCredits(id: String, credits: AudioCredits) {
        localLibraryLock.withLock { localTracks = localTracks.map { track ->
            if (track.id == id && track.needsMetadataVerification) track.withVerifiedCredits(credits) else track
        } }
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
        val current = trackIndex[player.currentMediaItem?.mediaId] ?: PlaybackItems.track(player.currentMediaItem)
        val queue = queueTracks(player)
        playback = PlaybackState(current, current != null && player.isPlaying, true, if (current != null) player.currentPosition.coerceAtLeast(0) else 0,
            player.duration.takeIf { current != null && it > 0 } ?: current?.durationMs ?: 0, queue,
            player.shuffleModeEnabled, player.repeatMode, listening.options.volume,
            (player.sessionExtras.getLong(PlaybackCommands.TIMER_DEADLINE) - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        updateEqualizer(player)
        // 只有真正开始播放时才写入历史，反复进出页面不会伪造记录。
        if (current == null) { clearCurrentTrack(); return }
        if (player.isPlaying && recordedId != current.id) record(current)
        if (lyricsTrackId != current.id) loadLyrics(current)
        if (artworkTrackId != current.id) loadArtwork(current)
    }

    // 会话没有当前歌曲时清除上首歌词和封面任务，防止旧数据留在界面。
    private fun clearCurrentTrack() {
        if (lyricsTrackId == null && artworkTrackId == null) return
        lyricsJob?.cancel(); artworkJob?.cancel()
        lyricsTrackId = null; artworkTrackId = null; recordedId = null
        lyrics = emptyList(); lyricsError = null; lyricsLoading = false
    }

    // 只有媒体时间线或歌曲元数据变化才重建队列，400ms 进度刷新复用已有对象。
    private fun queueTracks(player: Player): List<Track> {
        val index = trackIndex
        if (!queueDirty && queueIndex === index) return playback.queue
        queueIndex = index
        queueDirty = false
        return (0 until player.mediaItemCount).mapNotNull { index[player.getMediaItemAt(it).mediaId]
            ?: PlaybackItems.track(player.getMediaItemAt(it)) }
    }

    // 音效状态仅在服务的能力快照变化时解析，进度刷新复用同一对象。
    private fun updateEqualizer(player: MediaController) {
        val source = player.sessionExtras.getString(EqualizerStatusCodec.SESSION_KEY)
        if (source == equalizerSource) return
        equalizerSource = source; equalizer = EqualizerStatusCodec.decode(source)
    }

    // 播放一个强类型歌曲集合，防止空队列与无效起始索引。
    fun play(track: Track, collection: List<Track> = tracks) {
        clearMessage()
        val request = PlaybackRequest(track, collection, quality)
        // 取消过期的准备任务，快速点歌只提交最后一次请求。
        cancelPendingSelection()
        pendingSelection = request
        val player = controller ?: return
        startSelection(player, request)
    }

    // 开始点歌动画时先停止旧声音和过期准备任务，落点回调到达后才提交新播放。
    fun beginSongSelection() {
        cancelPendingSelection()
        controller?.pause()
    }

    // 会话就绪后在后台准备队列，并只提交最新一次点歌。
    private fun startSelection(player: MediaController, request: PlaybackRequest) {
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
        // 先登记本次真实选择，媒体会话事件到达时即可按 ID 找到歌曲。
        sessionTracks = prepared.tracks
        online.register(prepared.tracks.filter(Track::online))
        queueDirty = true
        player.setMediaItems(prepared.items, prepared.startIndex, 0)
        player.prepare()
        player.play()
        // 立即交接真实会话曲目，队列准备结束时播放条不会短暂闪回上一首。
        snapshot()
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
        val failed = player.playerError != null
        player.seekTo(index, 0)
        if (failed) { player.prepare(); player.play() }
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
        if (volume.isFinite()) listening.update(listening.options.copy(volume = volume.coerceIn(0f, 1f)))
        snapshot()
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
        sessionTracks = (sessionTracks + track).distinctBy(Track::id)
        queueDirty = true
        player.addMediaItem(mediaItem(track))
        message = "已加入播放列表"
    }

    // 已在队列中的歌曲移动到下一首，新歌曲只插入一次。
    fun playNext(track: Track) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) { play(track, listOf(track)); return }
        if (player.currentMediaItem?.mediaId == track.id) { notice("这首歌正在播放"); return }
        cancelPendingSelection()
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == track.id }
        val target = (player.currentMediaItemIndex + 1 - if (existing != null && existing < player.currentMediaItemIndex) 1 else 0)
        sessionTracks = (sessionTracks + track).distinctBy(Track::id); queueDirty = true
        if (existing == null) player.addMediaItem(target.coerceAtMost(player.mediaItemCount), mediaItem(track))
        else player.moveMediaItem(existing, target.coerceIn(0, player.mediaItemCount - 1))
        notice("已设为下一首播放")
    }

    // 通过稳定歌曲标识移动队列，拖动与无障碍操作共用真实媒体会话。
    fun moveQueued(sourceId: String, targetId: String) {
        val player = controller ?: return
        val source = playback.queue.indexOfFirst { it.id == sourceId }
        val target = playback.queue.indexOfFirst { it.id == targetId }
        if (source < 0 || target < 0 || source == target) return
        queueDirty = true; player.moveMediaItem(source, target); snapshot()
    }

    // 移除当前曲目时由 Media3 连续选择后继项，不修改云歌单或原文件。
    fun removeQueued(trackId: String) {
        val player = controller ?: return
        val index = playback.queue.indexOfFirst { it.id == trackId }
        if (index < 0) return
        cancelPendingSelection(); queueDirty = true; player.removeMediaItem(index); snapshot()
    }

    // 清空只作用于临时播放队列，同时取消尚未提交的选曲准备任务。
    fun clearQueue() {
        cancelPendingSelection(); queueDirty = true
        controller?.run { pause(); stop(); clearMediaItems() }
        snapshot()
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
            // 串行提交曲库变更，导入与移除同时进行也不会相互覆盖。
            mutateLocalTracks { (it + result.tracks).distinctBy(Track::id) }
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

    // 提示操作使用明确枚举分发，不把界面回调或账号信息保存在消息中。
    fun handleNotice(action: NoticeAction) {
        when (action) {
            NoticeAction.Login -> loginRequested = true
            NoticeAction.Downloads -> downloadsRequested = true
            NoticeAction.Retry -> controller?.run { prepare(); play() }
        }
    }

    // 下载只对在线资源创建任务，本地文件仍可通过原有另存为入口导出。
    fun download(track: Track) {
        if (!track.online) { notice("这首歌已在本地，可以使用另存为"); return }
        runCatching { AudioDownloadService.enqueue(getApplication(), track, quality) }
            .onSuccess {
                notificationPromptRequested = true
                notice("已加入下载队列"); noticeAction = NoticeAction.Downloads
            }.onFailure { notice("暂时无法启动下载，请保持应用在前台后重试") }
    }

    // 管理页命令由同一个前台服务接受，启动受系统限制时给出可理解反馈。
    fun downloadCommand(id: String, action: String) {
        runCatching { AudioDownloadService.command(getApplication(), id, action) }
            .onFailure { notice("暂时无法管理下载，请保持应用在前台后重试") }
    }

    // 失效扫描在线程池中完成，不阻塞搜索或批量选择界面。
    suspend fun unreadableLocalTracks(): List<Track> {
        val imported = localTracks
        return withContext(Dispatchers.IO) { imported.filter { track ->
            runCatching {
                requireNotNull(getApplication<Application>().contentResolver.openAssetFileDescriptor(track.mediaUri.toUri(), "r")).use {
                    check(it.length != 0L)
                }
            }.isFailure
        } }
    }

    // 批量收藏本地歌曲一次提交，避免逐首更新导致大量偏好写入。
    fun favoriteLocal(ids: Set<String>) {
        localFavorites = localFavorites + ids
        store.saveFavorites(localFavorites); localFavoriteMarks = store.favoriteMarks()
        notice("已收藏 ${ids.size} 首本地音乐")
    }

    // 从导入曲库移除记录，不删除设备中的原始文件或已下载音频。
    fun removeLocal(ids: Set<String>) {
        viewModelScope.launch {
            // 基于取得锁后的最新曲库计算移除结果。
            mutateLocalTracks { tracks -> tracks.filterNot { it.id in ids } }
            notice("已移除 ${ids.size} 条曲库记录，原文件已保留")
        }
    }

    // 曲库的读取、变换和持久化作为同一次串行操作执行。
    private suspend fun mutateLocalTracks(transform: (List<Track>) -> List<Track>) = localLibraryLock.withLock {
        val next = transform(localTracks)
        withContext(Dispatchers.IO) { store.saveLocalTracks(next) }
        localTracks = next
    }

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
        listening.update(listening.options.copy(quality = selected))
        pendingSelection?.let { request -> play(request.track, request.collection); return }
        val player = controller ?: return
        val track = playback.track?.takeIf(Track::online) ?: return
        val position = player.currentPosition
        val playing = player.playWhenReady
        player.replaceMediaItem(player.currentMediaItemIndex, mediaItem(track))
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
            catch (error: Exception) { lyricsError = error.userMessage() }
            finally { lyricsLoading = false }
        }
    }

    // 歌词加载失败时允许用户对当前歌曲重试。
    fun retryLyrics() { playback.track?.let(::loadLyrics) }

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
                    mutateLocalTracks { tracks -> tracks.map { if (it.id == track.id) it.copy(coverUrl = cover) else it } }
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
        listening.close()
        MediaController.releaseFuture(connection)
        super.onCleared()
    }

    // 系统媒体通知只使用当前歌曲的真实元数据和封面。
    private fun mediaItem(track: Track, selectedQuality: AudioQuality = quality): MediaItem = PlaybackItems.create(track, selectedQuality)

    private companion object {
        const val POSITION_INTERVAL_MS = 400L
        const val SEARCH_HISTORY_LIMIT = 12
    }
}
