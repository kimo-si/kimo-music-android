package com.kimo.music

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.kimo.music.data.DemoCatalog
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.PlaybackService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// 原生时钟验收窗口供设备侧录制动画，前后快照保护实际本机收藏与播放历史。
class NativeMotionCaptureTest {
    @get:Rule val library = LocalLibraryFixture(seedTracks = true)

    // 使用系统真实帧时钟，避免 Compose 测试时钟自动推进影响动效验收。
    @Test fun captureSelectionAndQueueWithSystemClock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val session = InstrumentationRegistry.getArguments().getString("captureSession", "default")
            Log.i("KimoCapture", "ready-$session")
            SystemClock.sleep(30_000)
        } finally {
            scenario.close()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // 使用系统真实帧时钟验证唱片播放旋转、暂停静止，不依赖测试时钟模拟无限动画。
    @Test fun vinylRotatesWithRealPlaybackAndFreezesOnPause() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            lateinit var model: MusicViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            awaitCondition { model.playback.ready }
            runOnMain { model.play(DemoCatalog.tracks.first(), DemoCatalog.tracks) }
            awaitCondition { model.playback.playing }
            tapNode("打开播放页")
            nodeBounds("唱片封面：晚风经过")
            // 等展开动画和图片加载完成，再比较唱片所在区域。
            SystemClock.sleep(1_200)
            val before = captureRecordArea()
            SystemClock.sleep(1_200)
            val moving = captureRecordArea()
            assertFalse("播放时唱片应持续旋转", before.sameAs(moving))
            runOnMain { model.togglePlaying() }
            awaitCondition { !model.playback.playing }
            SystemClock.sleep(400)
            val paused = captureRecordArea()
            SystemClock.sleep(800)
            assertTrue("暂停后唱片应保持静止", paused.sameAs(captureRecordArea()))
        } finally { scenario.close(); context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    // 在主线程读取媒体状态，超时明确报错而不无限等待。
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var complete = false
            runOnMain { complete = condition() }
            if (complete) return
            SystemClock.sleep(50)
        }
        error("等待播放状态超时")
    }

    // 查找实际无障碍节点，避免假设手机尺寸和控件坐标。
    private fun nodeBounds(label: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = findNode(root, label)
            if (node != null) return Rect().also(node::getBoundsInScreen)
            SystemClock.sleep(50)
        }
        error("未找到界面元素：$label")
    }

    // 遍历可访问节点树，只返回名称完全一致的元素。
    private fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), label)?.let { return it }
        return null
    }

    // 在节点真实中心注入触摸事件，沿用实际用户操作路径。
    private fun tapNode(label: String) {
        val bounds = nodeBounds(label)
        val now = SystemClock.uptimeMillis()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try { automation.injectInputEvent(down, true); automation.injectInputEvent(up, true) }
        finally { down.recycle(); up.recycle() }
    }

    // 旋转图层的无障碍坐标可能偏移，截取竖屏唱片区并排除状态栏和播放进度。
    private fun captureRecordArea(): Bitmap {
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val left = screen.width / RECORD_SIDE_INSET_DIVISOR
        val top = screen.height / RECORD_TOP_INSET_DIVISOR
        return Bitmap.createBitmap(screen, left, top, screen.width - 2 * left, screen.height / 2 - top)
    }

    // 所有播放器交互均发送到 Android 主线程。
    private fun runOnMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private companion object {
        const val RECORD_SIDE_INSET_DIVISOR = 5
        const val RECORD_TOP_INSET_DIVISOR = 8
    }
}
