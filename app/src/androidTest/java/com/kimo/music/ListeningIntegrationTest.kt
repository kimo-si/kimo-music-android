package com.kimo.music

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.ListeningPreferences
import com.kimo.music.playback.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ListeningIntegrationTest {
    @get:Rule val library = LocalLibraryFixture(clearOnStart = true, seedTracks = true)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // 销毁页面和实际服务后重新打开，队列、位置和模式恢复，但不自行播放。
    @Test fun serviceRestartRestoresQueuePositionAndModesWithoutAutoplay() {
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        var model = model(scenario)
        waitFor { model.playback.ready }
        main { model.play(DemoCatalog.tracks[1], DemoCatalog.tracks) }
        waitFor { model.playback.playing }
        main { model.togglePlaying(); model.seek(30_000); model.toggleShuffle(); model.cycleRepeat(); model.setVolume(0.45f) }
        waitFor { !model.playback.playing && model.playback.positionMs >= 29_500 }
        val savedMode = model.playback.repeatMode
        scenario.close()
        context.stopService(Intent(context, PlaybackService::class.java))
        waitFor { serviceStopped() }
        PlaybackCheckpointStore.awaitWrites()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            model = model(scenario)
            waitFor { model.playback.queue.size == 3 }
            assertEquals(DemoCatalog.tracks[1].id, model.playback.track?.id)
            assertTrue(model.playback.positionMs in 29_500..31_500)
            assertTrue(model.playback.shuffle); assertEquals(savedMode, model.playback.repeatMode)
            assertEquals(0.45f, model.playback.volume, 0.01f); assertFalse(model.playback.playing)
            main { model.togglePlaying() }; waitFor { model.playback.playing }
        } finally { scenario.close() }
    }

    // 对真实播放器采样音量，验证淡入、曲末淡出及关闭后的基础音量。
    @Test fun fadesChangeActualAudioEnvelopeAndEqualizerReadsNativeBands() {
        val preferences = ListeningPreferences(context)
        preferences.update(preferences.options.copy(fadeMs = 2000, volume = 0.8f, equalizerEnabled = true,
            equalizerGains = listOf(200, -100, 0, 100, 200)))
        lateinit var player: ExoPlayer
        lateinit var effects: AudioEffectsController
        var status = EqualizerStatus()
        main {
            player = ExoPlayer.Builder(context).build()
            effects = AudioEffectsController(context, player) { status = it }
            player.setMediaItem(PlaybackItems.create(DemoCatalog.tracks.first(), com.kimo.music.core.AudioQuality.Standard))
            player.prepare(); player.play()
        }
        try {
            waitFor { player.isPlaying }
            var beginning = 1f; main { beginning = player.volume }
            assertTrue("淡入起点应低于基础音量，实际 $beginning", beginning < 0.4f)
            waitFor { player.volume > 0.75f }
            main { player.seekTo(player.duration - 1200) }
            waitFor { player.volume in 0.01f..0.5f }
            main { preferences.update(preferences.options.copy(fadeMs = 0)) }
            waitFor { player.volume > 0.75f }
            if (status.available) {
                assertTrue("实际音效器应已启用", status.enabled)
                assertTrue(status.bands.isNotEmpty())
                status.bands.forEach { band ->
                    val expected = listOf(200, -100, 0, 100, 200).getOrElse(band.index) { 0 }.coerceIn(band.minimum, band.maximum)
                    assertEquals("设备应读回相同增益", expected, band.level)
                }
            } else assertTrue(status.message.isNotBlank())
        } finally { main { effects.release(); player.release(); preferences.close() } }
    }

    // 通过 Activity 自身的 ViewModelStore 获取模型，避免测试创建第二份播放状态。
    private fun model(scenario: ActivityScenario<MainActivity>): MusicViewModel {
        lateinit var result: MusicViewModel
        scenario.onActivity { result = ViewModelProvider(it)[MusicViewModel::class.java] }
        return result
    }

    // 播放器及其状态只能在主线程访问。
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    // 等待真实系统状态，超时明确失败而不依赖固定时序。
    private fun waitFor(timeout: Long = 15_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var ready = false; main { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("等待真实播放状态超时")
    }

    // 仅用于独立测试设备，确认服务已销毁后再验证恢复。
    @Suppress("DEPRECATION")
    private fun serviceStopped(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).none { it.service.className == PlaybackService::class.java.name }
}
