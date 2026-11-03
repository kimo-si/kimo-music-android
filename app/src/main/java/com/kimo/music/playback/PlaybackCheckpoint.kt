package com.kimo.music.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kimo.music.core.AudioQuality
import com.kimo.music.data.Track
import com.kimo.music.data.TrackStorage
import com.kimo.music.data.online
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Executor

data class PlaybackCheckpoint(val items: List<MediaItem>, val index: Int, val positionMs: Long,
    val shuffle: Boolean, val repeatMode: Int)

object PlaybackItems {
    private const val TRACK = "kimo_track_metadata"

    // 媒体项携带稳定歌曲信息，服务重建后无需依赖已打开过的界面曲库。
    fun create(track: Track, quality: AudioQuality): MediaItem = MediaItem.Builder().setMediaId(track.id)
        .setUri(if (track.online) KugouDataSource.uri(track, quality) else track.mediaUri.toUri())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.displayArtist)
            .setAlbumTitle(track.displayAlbum).setArtworkUri(track.coverUrl?.toUri())
            .setExtras(Bundle().apply { putString(TRACK, TrackStorage.encode(track).toString()) }).build()).build()

    // 损坏或旧版媒体元数据只返回空值，不伪造歌曲与文件位置。
    fun track(item: MediaItem?): Track? = runCatching {
        item?.mediaMetadata?.extras?.getString(TRACK)?.let { TrackStorage.decode(JSONObject(it)) }
    }.getOrNull()
}

object PlaybackCheckpointStore {
    private const val PREFERENCES = "kimo_playback"
    private const val CHECKPOINT = "checkpoint"
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Kimo-Playback-Storage") }

    // 所有检查点读写串行在线程池执行，避免较旧的进度覆盖新队列。
    fun read(context: Context): ListenableFuture<PlaybackCheckpoint?> {
        val result = SettableFuture.create<PlaybackCheckpoint?>()
        worker.execute { result.set(load(context.applicationContext)) }
        return result
    }

    // 同一偏好项一次提交完整队列、位置和模式，清空队列也会同步清空恢复记录。
    fun save(context: Context, checkpoint: PlaybackCheckpoint) {
        worker.execute {
            val data = encode(checkpoint).toString()
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(CHECKPOINT, data) }
        }
    }

    // 测试与生命周期清理可等待先前写入完成，此函数不得在界面线程调用。
    fun awaitWrites() { worker.submit {}.get() }

    // 在线地址不落盘，只编码歌曲稳定信息及其选择的音质。
    private fun encode(value: PlaybackCheckpoint): JSONObject = JSONObject().apply {
        put("index", value.index); put("position", value.positionMs)
        put("currentId", value.items.getOrNull(value.index)?.mediaId.orEmpty())
        put("shuffle", value.shuffle); put("repeat", value.repeatMode)
        put("tracks", JSONArray().apply {
            value.items.forEach { item -> PlaybackItems.track(item)?.let { track ->
                put(JSONObject().put("track", TrackStorage.encode(track))
                    .put("quality", item.localConfiguration?.uri?.getQueryParameter("quality")))
            } }
        })
    }

    // 单条损坏记录独立跳过，非法位置和循环值按有效队列约束。
    private fun load(context: Context): PlaybackCheckpoint? = runCatching {
        val source = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(CHECKPOINT, null)
            ?: return null
        val data = JSONObject(source)
        val tracks = data.optJSONArray("tracks") ?: return null
        val items = (0 until tracks.length()).mapNotNull { index -> decodeItem(tracks.optJSONObject(index)) }
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.mediaId == data.optString("currentId") }
            .takeIf { it >= 0 } ?: data.optInt("index").coerceIn(items.indices)
        PlaybackCheckpoint(items, index, data.optLong("position").coerceAtLeast(0),
            data.optBoolean("shuffle"), data.optInt("repeat", Player.REPEAT_MODE_ALL).coerceIn(0, 2))
    }.getOrNull()

    // 每首歌独立恢复，音质值不存在时使用标准音质。
    private fun decodeItem(value: JSONObject?): MediaItem? = runCatching {
        value ?: return null
        val quality = AudioQuality.entries.firstOrNull { it.code == value.optString("quality") } ?: AudioQuality.Standard
        PlaybackItems.create(TrackStorage.decode(value.getJSONObject("track")), quality)
    }.getOrNull()
}

class PlaybackPersistence(private val context: Context, private val player: Player,
    private val onChanged: () -> Unit) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var restoring = true
    private var closed = false
    private val tick = object : Runnable {
        // 定期保留真实进度；暂停和队列变化还会额外立即保存。
        override fun run() { persist(); onChanged(); handler.postDelayed(this, SAVE_INTERVAL_MS) }
    }

    init {
        player.addListener(this)
        val future = PlaybackCheckpointStore.read(context)
        future.addListener({ restore(runCatching { future.get() }.getOrNull()) }, Executor { handler.post(it) })
        handler.postDelayed(tick, SAVE_INTERVAL_MS)
    }

    // 恢复期间用户已经点歌时，以用户的新选择为准；重开应用只恢复暂停状态。
    private fun restore(value: PlaybackCheckpoint?) {
        if (closed) return
        if (restoring && player.mediaItemCount == 0 && value != null) {
            player.repeatMode = value.repeatMode; player.shuffleModeEnabled = value.shuffle
            player.setMediaItems(value.items, value.index, value.positionMs)
            player.pause()
        }
        restoring = false
        onChanged()
    }

    // 事件发生后保存完整状态，写入由独立工作线程完成。
    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) && !restoring) {
            persist()
        }
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) onChanged()
    }

    // 从播放器主线程读取不可变媒体项，文件序列化留在线程池。
    private fun persist() {
        if (restoring) return
        PlaybackCheckpointStore.save(context, PlaybackCheckpoint((0 until player.mediaItemCount).map(player::getMediaItemAt),
            player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0),
            player.shuffleModeEnabled, player.repeatMode))
    }

    // 服务销毁前保留最后位置，并防止恢复回调访问已释放的播放器。
    fun release() { persist(); closed = true; restoring = false; handler.removeCallbacks(tick); player.removeListener(this) }

    private companion object { const val SAVE_INTERVAL_MS = 5_000L }
}
