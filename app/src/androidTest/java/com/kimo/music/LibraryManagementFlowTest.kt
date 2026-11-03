package com.kimo.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.data.DemoCatalog
import com.kimo.music.playback.MusicViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LibraryManagementFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true, seedTracks = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 真实长按拖动会改变服务队列顺序，同时保留当前曲目与收藏。
    @Test fun queueDragRemoveAndClearOperateOnRealSession() {
        val model = preparePlayback()
        compose.runOnIdle { model.toggleFavorite(DemoCatalog.tracks.first()) }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithContentDescription("播放列表").performClick()
        val start = compose.onNodeWithContentDescription("拖动排序：晚风经过").fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithContentDescription("拖动排序：午后三点").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("拖动排序：晚风经过").performTouchInput {
            down(center); advanceEventTime(650)
            repeat(8) { moveBy(Offset(0f, (end.center.y - start.center.y) / 8), 70) }
            up()
        }
        compose.waitUntil(10_000) { model.playback.queue.lastOrNull()?.id == DemoCatalog.tracks.first().id }
        assertEquals(DemoCatalog.tracks.first().id, model.playback.track?.id)
        compose.onNodeWithContentDescription("移出队列：午后三点").performClick()
        compose.waitUntil { model.playback.queue.size == 2 }
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("清空队列").performClick()
        compose.waitUntil { model.playback.queue.isEmpty() }
        assertFalse(model.playback.playing)
        assertTrue(DemoCatalog.tracks.first().id in model.favorites)
    }

    // 下一首移动已有曲目而不重复追加，反复点击也只保留一份。
    @Test fun playNextMovesExistingTrackWithoutDuplication() {
        val model = preparePlayback()
        compose.runOnIdle { model.playNext(DemoCatalog.tracks[2]); model.playNext(DemoCatalog.tracks[2]) }
        compose.waitUntil { model.playback.queue.getOrNull(1)?.id == DemoCatalog.tracks[2].id }
        assertEquals(3, model.playback.queue.size)
        assertEquals(DemoCatalog.tracks.first().id, model.playback.track?.id)
    }

    // 文件短暂缺失后恢复，提示中的重试必须重新加载音频并保留原队列。
    @Test fun playbackFailureTipRetriesTheActualAudio() {
        val model = model()
        compose.waitUntil(15_000) { model.playback.ready }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(context.cacheDir, "retry-audio-test.mp3").apply { delete() }
        val track = DemoCatalog.tracks.first().copy(id = "retry-audio-test", mediaUri = android.net.Uri.fromFile(temporary).toString())
        try {
            compose.runOnIdle { model.play(track, listOf(track)) }
            compose.waitUntil(15_000) { model.noticeAction == com.kimo.music.playback.NoticeAction.Retry }
            compose.onNodeWithText("重试", substring = false).assertIsDisplayed()
            File(android.net.Uri.parse(DemoCatalog.tracks.first().mediaUri).path!!).copyTo(temporary, overwrite = true)
            compose.onNodeWithText("重试", substring = false).performClick()
            compose.waitUntil(15_000) { model.playback.playing }
            assertEquals(track.id, model.playback.track?.id)
            assertEquals(listOf(track.id), model.playback.queue.map { it.id })
            compose.runOnIdle { model.clearQueue() }
        } finally { temporary.delete() }
    }

    // 搜索后的全选仅操作筛选结果，移除记录后磁盘原文件仍可读取。
    @Test fun localSearchGroupingAndBatchActionsKeepOriginalFiles() {
        val model = model()
        compose.runOnIdle { model.localLibraryRequested = true }
        compose.onNodeWithText("搜索歌曲、歌手、专辑或文件夹").performTextInput("山谷")
        compose.onNodeWithText("歌手", substring = false).performClick()
        compose.onNodeWithText("林间回声 · 1").assertIsDisplayed()
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("收藏 1 首").performClick()
        compose.waitUntil { DemoCatalog.tracks[1].id in model.favorites }
        compose.onNodeWithText("移除导入记录").performClick()
        compose.onNodeWithText("移除记录", substring = false).performClick()
        compose.waitUntil { model.localTracks.size == 2 }
        assertFalse(model.localTracks.any { it.id == DemoCatalog.tracks[1].id })
        assertTrue(File(android.net.Uri.parse(DemoCatalog.tracks[1].mediaUri).path!!).canRead())
    }

    // 新增设置页面真实可打开，并且全局提示仍显示在当前窗口最上层。
    @Test fun soundDownloadAndAboutPagesAreReachableAndThemed() {
        val model = preparePlayback()
        compose.runOnIdle { model.soundSettingsRequested = true }
        compose.onNodeWithText("歌曲渐入渐出").assertIsDisplayed()
        compose.onNodeWithText("2 秒").performClick()
        compose.runOnIdle { assertEquals(2000L, model.listening.options.fadeMs) }
        screenshot("sound-settings")
        compose.onNodeWithContentDescription("返回音效与桌面").performClick()
        compose.runOnIdle { model.downloadsRequested = true }
        compose.onNodeWithText("下载管理").assertIsDisplayed()
        screenshot("download-center")
        compose.onNodeWithContentDescription("返回下载管理").performClick()
        compose.runOnIdle { model.aboutRequested = true }
        compose.waitUntil(25_000) { !model.updates.state.checking && model.updates.state.checked }
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        screenshot("about")
    }

    // 统一启动本地测试音频并暂停，避免网络和账号状态影响队列操作。
    private fun preparePlayback(): MusicViewModel {
        val model = model()
        compose.waitUntil(15_000) { model.playback.ready }
        compose.runOnIdle { model.play(DemoCatalog.tracks.first(), DemoCatalog.tracks) }
        compose.waitUntil(15_000) { model.playback.playing }
        compose.runOnIdle { model.togglePlaying() }
        compose.waitUntil { !model.playback.playing }
        return model
    }

    // 页面和测试共用 Activity 的模型。
    private fun model(): MusicViewModel {
        lateinit var result: MusicViewModel
        compose.runOnIdle { result = ViewModelProvider(compose.activity)[MusicViewModel::class.java] }
        return result
    }

    // 截取系统实际显示内容，便于核查对话框和主题遮挡。
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "kimo-expanded-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
