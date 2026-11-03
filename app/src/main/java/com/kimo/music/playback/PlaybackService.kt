package com.kimo.music.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kimo.music.widget.MusicWidgetState

data class SleepTimerRequest(val durationMs: Long)

// 会话协议字段集中定义，界面不直接操作服务的播放器或线程。
object PlaybackCommands {
    const val TIMER_DURATION = "sleep_timer_duration_ms"
    const val TIMER_DEADLINE = "sleep_timer_deadline_elapsed_ms"
    const val MAX_TIMER_MS = 8 * 60 * 60 * 1000L
    val SleepTimer = SessionCommand("com.kimo.music.SET_SLEEP_TIMER", Bundle.EMPTY)
}

// 播放器生命周期独立于 Activity，系统通知、锁屏与耳机共享同一个媒体会话。
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var sleepTimer: PlaybackSleepTimer? = null
    private var persistence: PlaybackPersistence? = null
    private var audioEffects: AudioEffectsController? = null
    private var desktopLyrics: DesktopLyricsController? = null

    private val commands = object : MediaSession.Callback {
        // 系统或蓝牙请求恢复时返回上次队列，读取由串行存储线程完成。
        @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.transform(PlaybackCheckpointStore.read(this@PlaybackService), { value ->
                MediaSession.MediaItemsWithStartPosition(value?.items.orEmpty(), value?.index ?: 0, value?.positionMs ?: 0)
            }, MoreExecutors.directExecutor())

        // 自定义定时命令只授权给本应用，系统媒体控制仍使用默认能力。
        @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (controller.packageName == packageName) available.add(PlaybackCommands.SleepTimer)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available.build()).build()
        }

        // 校验命令、来源与时长后，才更改服务持有的定时器。
        @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                     command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (command != PlaybackCommands.SleepTimer) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val duration = args.getLong(PlaybackCommands.TIMER_DURATION)
            if (duration !in 0..PlaybackCommands.MAX_TIMER_MS) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            sleepTimer?.schedule(SleepTimerRequest(duration))
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // 配置音频焦点、耳机拔出暂停及后台播放的前台服务。
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(DefaultMediaSourceFactory(KugouDataSource.factory(this))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            repeatMode = Player.REPEAT_MODE_ALL
        }
        // 会话服务统一托管播放器，由 Media3 维护系统媒体通知。
        session = MediaSession.Builder(this, player).setCallback(commands).build()
        // 定时器属于媒体服务，Activity 关闭后仍能停止实际音频。
        sleepTimer = PlaybackSleepTimer(player) { deadline ->
            session?.let { it.setSessionExtras(Bundle(it.sessionExtras).apply { putLong(PlaybackCommands.TIMER_DEADLINE, deadline) }) }
        }
        // 恢复只建立暂停队列，新的主动点歌优先于尚未完成的恢复请求。
        persistence = PlaybackPersistence(this, player) { MusicWidgetState.update(this, player) }
        // 音效绑定真实音频会话，能力和参数通过媒体会话反馈给界面。
        audioEffects = AudioEffectsController(this, player) { status ->
            session?.let { it.setSessionExtras(Bundle(it.sessionExtras).apply {
                putString(EqualizerStatusCodec.SESSION_KEY, EqualizerStatusCodec.encode(status))
            }) }
        }
        // 桌面歌词共用当前播放器与权限状态，不启动另一份音频服务。
        desktopLyrics = DesktopLyricsController(this, player)
    }

    // 仅向本应用及受信任的系统媒体控制方提供会话。
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) return session
        return null
    }

    // 清理播放器与会话，避免服务销毁后继续占用音频资源。
    override fun onDestroy() {
        persistence?.release(); persistence = null
        audioEffects?.release(); audioEffects = null
        desktopLyrics?.release(); desktopLyrics = null
        sleepTimer?.release()
        sleepTimer = null
        session?.run { player.pause(); MusicWidgetState.update(this@PlaybackService, player); player.release(); release() }
        session = null
        super.onDestroy()
    }
}

// 使用单调时间和播放器主线程管理倒计时，避免修改系统时间改变停止时刻。
private class PlaybackSleepTimer(private val player: Player, private val onChanged: (Long) -> Unit) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private val expiration = Runnable { expire() }

    init { player.addListener(this) }

    // 替换已有任务只保留一个截止时刻，零时长表示取消。
    fun schedule(request: SleepTimerRequest) {
        require(request.durationMs in 0..PlaybackCommands.MAX_TIMER_MS)
        handler.removeCallbacks(expiration)
        deadline = if (request.durationMs > 0) SystemClock.elapsedRealtime() + request.durationMs else 0
        onChanged(deadline)
        if (deadline > 0) handler.postDelayed(expiration, request.durationMs)
    }

    // 暂停期间设备休眠后，恢复播放前再次验证截止时刻。
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && deadline > 0 && SystemClock.elapsedRealtime() >= deadline) expire()
    }

    // 到时真正暂停媒体播放器，并清除会话中的剩余时间。
    private fun expire() {
        schedule(SleepTimerRequest(0))
        player.pause()
    }

    // 服务销毁时同时释放回调和监听器，不把过期任务留给下一次播放。
    fun release() {
        schedule(SleepTimerRequest(0))
        player.removeListener(this)
    }
}
