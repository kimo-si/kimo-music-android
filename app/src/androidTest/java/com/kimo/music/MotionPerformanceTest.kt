package com.kimo.music

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.data.DemoCatalog
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.PlaybackService
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.system.measureNanoTime
import java.io.File

class MotionPerformanceTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 使用自有离线音频构造长队列，不请求伪造歌曲、不修改云端收藏。
    private fun largeQueue(model: MusicViewModel) {
        val tracks = (0 until 600).map { DemoCatalog.tracks.first().copy(id = "motion-test-$it", title = "性能检查 $it") }
        model.online.register(tracks)
        val setupMs = measureNanoTime { model.play(tracks.first(), tracks) } / 1_000_000.0
        Log.i("KimoPerformance", "queue=600 selectionSetupMs=$setupMs")
    }

    // 进度变化不应重建目录、收藏集合或整条队列，避免持续分配和平方级扫描。
    @Test fun positionUpdatesReuseLibraryAndQueue() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready && !model.account.state.restoring }
        compose.runOnIdle { largeQueue(model) }
        compose.waitUntil(15_000) { model.playback.queue.size == 600 }
        compose.runOnIdle {
            val tracks = model.tracks
            val queue = model.playback.queue
            val favorites = model.favorites
            val snapshotMs = measureNanoTime { repeat(3) { model.seek(1_000) } } / 3_000_000.0
            val favoritesMs = measureNanoTime { repeat(5) { model.favorites } } / 5_000_000.0
            Log.i("KimoPerformance", "queue=600 snapshotMs=$snapshotMs favoritesMs=$favoritesMs")
            assertTrue("进度变化应复用歌曲索引", tracks === model.tracks)
            assertTrue("进度变化应复用整条队列", queue === model.playback.queue)
            assertTrue("逐行读取收藏应复用索引", favorites === model.favorites)
        }
    }

    // 长队列准备尚未完成时改选歌曲，后台旧结果不能覆盖最后一次选择。
    @Test fun rapidSelectionsOnlyCommitLatestQueue() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready }
        val selected = DemoCatalog.tracks[1]
        compose.runOnIdle {
            largeQueue(model)
            model.play(selected, DemoCatalog.tracks.drop(1))
        }
        compose.waitUntil(15_000) { model.playback.track.id == selected.id && model.playback.queue.size == 2 }
        android.os.SystemClock.sleep(500)
        compose.runOnIdle {
            assertEquals(selected.id, model.playback.track.id)
            assertEquals(DemoCatalog.tracks.drop(1).map { it.id }, model.playback.queue.map { it.id })
        }
    }

    // 长队列展开后能滚到末尾，进度刷新不重置滚动，选择末曲仍保留全部歌曲。
    @Test fun longQueueKeepsScrollPositionWhilePlaybackAdvances() {
        lateinit var model: MusicViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        compose.waitUntil(15_000) { model.playback.ready }
        compose.runOnIdle { largeQueue(model) }
        compose.waitUntil(15_000) { model.playback.queue.size == 600 }
        compose.onNodeWithContentDescription("播放列表").performClick()
        compose.onNodeWithText("播放列表 · 600").assertIsDisplayed()
        compose.onNodeWithTag("queue-song-list").performScrollToIndex(580)
        compose.onNodeWithText("性能检查 580").assertIsDisplayed()
        compose.runOnIdle { repeat(5) { model.seek(it * 1_000L) } }
        compose.onNodeWithText("性能检查 580").assertIsDisplayed()
        compose.onNodeWithTag("queue-song-list").performScrollToIndex(599)
        compose.onNodeWithText("性能检查 599").performClick()
        compose.waitUntil(10_000) { model.playback.track.id == "motion-test-599" }
        compose.runOnIdle { assertEquals(600, model.playback.queue.size) }
    }

    // 留在原列表连续点歌时只有一个音符，动画结束后仍可从迷你条展开播放器。
    @Test fun selectingSongFliesOneNoteAndKeepsLibraryVisible() {
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("音乐设置").performClick()
        compose.onNodeWithText("离线演示").performClick()
        val song = compose.onNodeWithContentDescription("播放歌曲：山谷来信").performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            song.performClick()
            compose.mainClock.advanceTimeBy(176)
            compose.onNodeWithTag("song-flight").assertIsDisplayed()
            compose.onNodeWithText("正在播放").assertDoesNotExist()
            saveFlightImage()
            song.performClick()
            compose.mainClock.advanceTimeBy(160)
            compose.onAllNodesWithTag("song-flight").assertCountEquals(1)
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithTag("song-flight").assertDoesNotExist()
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("mini-player-artwork", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
    }

    // 保存时钟暂停处的真实中间帧，验证音符与源歌曲、目标播放条的空间关系。
    private fun saveFlightImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "song-flight.png").outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    // 测试结束释放媒体服务，原本机库由外层快照完整恢复。
    @After fun stopPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, PlaybackService::class.java))
    }
}
