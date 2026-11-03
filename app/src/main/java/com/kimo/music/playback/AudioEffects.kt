package com.kimo.music.playback

import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kimo.music.data.ListeningOptions
import com.kimo.music.data.ListeningPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

data class EqualizerBand(val index: Int, val frequencyHz: Int, val level: Int, val minimum: Int, val maximum: Int)
data class EqualizerStatus(val available: Boolean = false, val bands: List<EqualizerBand> = emptyList(),
    val message: String = "播放歌曲后可调节均衡器", val enabled: Boolean = false)

object EqualizerStatusCodec {
    const val SESSION_KEY = "kimo_equalizer_state"

    // 只在媒体会话边界编码实际设备能力，不假定所有手机都拥有五段均衡器。
    fun encode(value: EqualizerStatus): String = JSONObject().put("available", value.available)
        .put("message", value.message).put("enabled", value.enabled).put("bands", JSONArray().apply {
            value.bands.forEach { band -> put(JSONObject().put("index", band.index).put("frequency", band.frequencyHz)
                .put("level", band.level).put("min", band.minimum).put("max", band.maximum)) }
        }).toString()

    // 旧会话与不支持音效的设备返回可说明的空状态。
    fun decode(source: String?): EqualizerStatus = runCatching {
        if (source.isNullOrBlank()) return EqualizerStatus()
        val data = JSONObject(source); val bands = data.optJSONArray("bands") ?: JSONArray()
        EqualizerStatus(data.optBoolean("available"), (0 until bands.length()).map { index ->
            val band = bands.getJSONObject(index)
            EqualizerBand(band.getInt("index"), band.getInt("frequency"), band.getInt("level"),
                band.getInt("min"), band.getInt("max"))
        }, data.optString("message"), data.optBoolean("enabled"))
    }.getOrDefault(EqualizerStatus())
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AudioEffectsController(context: Context, private val player: ExoPlayer,
    private val onStatus: (EqualizerStatus) -> Unit) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var equalizer: Equalizer? = null
    private var fadeStartedAt = 0L
    private var needsFadeIn = true
    private var closed = false
    private val settings = ListeningPreferences(context) { applyOptions(it) }
    private val tick = object : Runnable {
        // 仅改变音频包络，不改变用户保存的基础音量。
        override fun run() { applyEnvelope(); scheduleEnvelope() }
    }

    init { player.addListener(this); applyOptions(settings.options) }

    // 每次音频会话变化重新绑定效果器，禁止将音效绑定到全局输出会话。
    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        equalizer?.release(); equalizer = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching { equalizer = Equalizer(0, audioSessionId); applyEqualizer(settings.options) }
            .onFailure { onStatus(EqualizerStatus(message = "此设备暂不支持应用内均衡器")) }
    }

    // 新曲目从淡入起点开始，暂停状态不自行开启声音。
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        needsFadeIn = true
        if (player.isPlaying) startFadeIn()
    }

    // 缓冲完成后以实际开始发声的时刻计算淡入。
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && needsFadeIn) startFadeIn()
        if (!isPlaying) needsFadeIn = true
        scheduleEnvelope()
    }

    // 记录淡入起点，实际音量更新始终由统一的包络函数执行。
    private fun startFadeIn() { fadeStartedAt = SystemClock.elapsedRealtime(); needsFadeIn = false; applyEnvelope() }

    // 偏好变化立即应用到真实音频会话，异常只影响音效能力。
    private fun applyOptions(options: ListeningOptions) {
        if (closed) return
        applyEnvelope()
        scheduleEnvelope()
        runCatching { applyEqualizer(options) }
            .onFailure { onStatus(EqualizerStatus(message = "音效暂时不可用，请重新开始播放后重试")) }
    }

    // 只有启用渐变并实际播放时才采样，暂停或关闭时不占用主线程定时器。
    private fun scheduleEnvelope() {
        handler.removeCallbacks(tick)
        if (!closed && player.isPlaying && settings.options.fadeMs > 0) handler.postDelayed(tick, ENVELOPE_INTERVAL_MS)
    }

    // 自动曲目结束时淡出，下一曲淡入；关闭时恢复基础音量。
    private fun applyEnvelope() {
        val options = settings.options
        val fade = options.fadeMs
        val entering = if (fade > 0 && fadeStartedAt > 0 && player.isPlaying)
            ((SystemClock.elapsedRealtime() - fadeStartedAt).toFloat() / fade).coerceIn(0f, 1f) else 1f
        val remaining = player.duration - player.currentPosition
        val leaving = if (fade > 0 && player.isPlaying && player.duration > 0)
            (remaining.toFloat() / fade).coerceIn(0f, 1f) else 1f
        val target = options.volume * min(entering, leaving)
        if (abs(player.volume - target) > 0.002f) player.volume = target
    }

    // 按设备报告的频段及增益范围应用用户设置，失去控制权时明确反馈。
    private fun applyEqualizer(options: ListeningOptions) {
        val effect = equalizer ?: return
        if (!effect.hasControl()) { onStatus(EqualizerStatus(message = "均衡器正由其他音频应用控制")); return }
        val range = effect.bandLevelRange
        val bands = (0 until effect.numberOfBands).map { index ->
            val level = options.equalizerGains.getOrElse(index) { 0 }.coerceIn(range[0].toInt(), range[1].toInt())
            effect.setBandLevel(index.toShort(), level.toShort())
            EqualizerBand(index, effect.getCenterFreq(index.toShort()) / 1000, effect.getBandLevel(index.toShort()).toInt(), range[0].toInt(), range[1].toInt())
        }
        effect.enabled = options.equalizerEnabled
        onStatus(EqualizerStatus(true, bands, "", effect.enabled))
    }

    // 服务销毁时同时释放效果器、偏好监听和音量定时器。
    fun release() {
        closed = true; handler.removeCallbacks(tick); player.removeListener(this)
        equalizer?.release(); equalizer = null; settings.close()
    }

    private companion object { const val ENVELOPE_INTERVAL_MS = 50L }
}
