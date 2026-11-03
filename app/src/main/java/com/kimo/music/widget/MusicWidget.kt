package com.kimo.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kimo.music.MainActivity
import com.kimo.music.R
import com.kimo.music.data.AppearanceMode
import com.kimo.music.data.LibraryStore
import com.kimo.music.data.formatDuration
import com.kimo.music.playback.PlaybackCheckpointStore
import com.kimo.music.playback.PlaybackItems
import com.kimo.music.playback.PlaybackService

data class WidgetPlayback(val title: String = "Kimo", val artist: String = "选择一首喜欢的音乐", val playing: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0)

object MusicWidgetState {
    var current: WidgetPlayback? = null; private set

    // 服务将真实会话状态发送给桌面，不在控件中建立第二个播放器。
    fun update(context: Context, player: Player) {
        val item = player.currentMediaItem
        val track = PlaybackItems.track(item)
        current = WidgetPlayback(track?.title ?: "Kimo", track?.displayArtist?.ifBlank { "打开音乐" } ?: "选择一首喜欢的音乐",
            player.isPlaying, player.currentPosition.coerceAtLeast(0),
            player.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0)
        render(context, requireNotNull(current))
    }

    // 使用轻量 RemoteViews 更新所有已添加控件，图片与内容不会无限追加。
    fun render(context: Context, state: WidgetPlayback) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val dark = LibraryStore(context).appearance.let { mode -> mode == AppearanceMode.Dark ||
            mode == AppearanceMode.System && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
        val views = RemoteViews(context.packageName, R.layout.music_widget)
        views.setTextViewText(R.id.widget_title, state.title); views.setTextViewText(R.id.widget_artist, state.artist)
        views.setTextViewText(R.id.widget_time, "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}")
        views.setTextColor(R.id.widget_title, if (dark) 0xFFF2EDE4.toInt() else 0xFF20211D.toInt())
        views.setTextColor(R.id.widget_artist, if (dark) 0xFFABADA5.toInt() else 0xFF75766F.toInt())
        views.setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background_light)
        views.setImageViewResource(R.id.widget_toggle, if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        views.setContentDescription(R.id.widget_toggle, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))
        listOf(R.id.widget_previous to MusicWidgetProvider.PREVIOUS, R.id.widget_toggle to MusicWidgetProvider.TOGGLE,
            R.id.widget_next to MusicWidgetProvider.NEXT).forEach { (id, action) -> views.setOnClickPendingIntent(id, control(context, action)) }
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), FLAGS))
        manager.updateAppWidget(ids, views)
    }

    // 控件点击只向本应用的显式接收器发送不可变操作。
    private fun control(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(context, action.hashCode(),
        Intent(context, MusicWidgetProvider::class.java).setAction(action), FLAGS)

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}

class MusicWidgetProvider : AppWidgetProvider() {
    // 冷启动控件从持久队列读取暂停状态，不会因桌面刷新而自动播放。
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        MusicWidgetState.current?.let { MusicWidgetState.render(context, it); return }
        val pending = goAsync(); val future = PlaybackCheckpointStore.read(context)
        future.addListener({
            try {
                val checkpoint = future.get(); val track = checkpoint?.let { PlaybackItems.track(it.items.getOrNull(it.index)) }
                MusicWidgetState.render(context, WidgetPlayback(track?.title ?: "Kimo", track?.displayArtist ?: "选择一首喜欢的音乐",
                    false, checkpoint?.positionMs ?: 0, track?.durationMs ?: 0))
            } catch (_: Exception) { MusicWidgetState.render(context, WidgetPlayback()) }
            finally { pending?.finish() }
        }, ContextCompat.getMainExecutor(context))
    }

    // 只接受声明的三个播放动作，其余广播沿用 AppWidgetProvider 的系统处理。
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(PREVIOUS, TOGGLE, NEXT)) { super.onReceive(context, intent); return }
        val pending = goAsync()
        // 广播的受限 Context 禁止绑定服务，异步连接必须使用应用 Context。
        val application = context.applicationContext
        val future = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()
        future.addListener({
            try {
                val player = future.get()
                dispatch(context, player, intent.action.orEmpty()) {
                    Handler(Looper.getMainLooper()).postDelayed({ MediaController.releaseFuture(future) }, 300)
                    pending?.finish()
                }
            } catch (_: Exception) {
                MusicWidgetState.render(context, MusicWidgetState.current ?: WidgetPlayback())
                MediaController.releaseFuture(future); pending?.finish()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // 冷启动先恢复真实队列再处理动作，避免第一次点击切歌被空会话吞掉。
    private fun dispatch(context: Context, player: MediaController, action: String, complete: () -> Unit) {
        if (player.mediaItemCount > 0) { applyAction(context, player, action); complete(); return }
        val checkpoint = PlaybackCheckpointStore.read(context)
        checkpoint.addListener({
            try {
                checkpoint.get()?.let { saved ->
                    if (player.mediaItemCount == 0) {
                        player.repeatMode = saved.repeatMode; player.shuffleModeEnabled = saved.shuffle
                        player.setMediaItems(saved.items, saved.index, saved.positionMs)
                    }
                }
                applyAction(context, player, action)
            } catch (_: Exception) { MusicWidgetState.render(context, WidgetPlayback()) }
            finally { complete() }
        }, ContextCompat.getMainExecutor(context))
    }

    // 所有按钮操作同一媒体会话，暂停时切歌不会自行开始播放。
    private fun applyAction(context: Context, player: MediaController, action: String) {
        when (action) {
            TOGGLE -> if (player.isPlaying) player.pause() else { player.prepare(); player.play() }
            NEXT -> if (player.mediaItemCount > 0) player.seekTo((player.currentMediaItemIndex + 1) % player.mediaItemCount, 0)
            PREVIOUS -> if (player.mediaItemCount > 0)
                player.seekTo((player.currentMediaItemIndex - 1 + player.mediaItemCount) % player.mediaItemCount, 0)
        }
        MusicWidgetState.update(context, player)
    }

    companion object {
        const val PREVIOUS = "com.kimo.music.WIDGET_PREVIOUS"
        const val TOGGLE = "com.kimo.music.WIDGET_TOGGLE"
        const val NEXT = "com.kimo.music.WIDGET_NEXT"
    }
}
