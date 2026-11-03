package com.kimo.music

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.data.DemoCatalog
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.widget.MusicWidgetProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DesktopIntegrationTest {
    @get:Rule val library = LocalLibraryFixture(clearOnStart = true, seedTracks = true)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // 真正绑定系统小组件并点击 RemoteViews，确认按钮控制现有音频会话。
    @Test fun boundWidgetDisplaysAndControlsActualPlayback() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = prepare(scenario)
        val host = AppWidgetHost(context, 87152)
        val manager = AppWidgetManager.getInstance(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        var widgetId = 0
        try {
            main { widgetId = host.allocateAppWidgetId() }
            assertTrue(manager.bindAppWidgetIdIfAllowed(widgetId, ComponentName(context, MusicWidgetProvider::class.java)))
            lateinit var hostView: android.appwidget.AppWidgetHostView
            scenario.onActivity { activity ->
                host.startListening()
                hostView = host.createView(activity, widgetId, manager.getAppWidgetInfo(widgetId))
                activity.addContentView(hostView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 450))
            }
            waitFor { hostView.findViewById<TextView>(R.id.widget_title)?.text == DemoCatalog.tracks.first().title }
            main { assertTrue(hostView.findViewById<android.view.View>(R.id.widget_toggle).performClick()) }
            waitFor { model.playback.playing }
            main { hostView.findViewById<android.view.View>(R.id.widget_next).performClick() }
            waitFor { model.playback.track?.id == DemoCatalog.tracks[1].id }
            waitFor { hostView.findViewById<TextView>(R.id.widget_title)?.text == DemoCatalog.tracks[1].title }
            screenshot("widget")
            main { hostView.findViewById<android.view.View>(R.id.widget_toggle).performClick() }
            waitFor { !model.playback.playing }
        } finally {
            main { host.stopListening(); if (widgetId != 0) host.deleteAppWidgetId(widgetId) }
            instrumentation.uiAutomation.dropShellPermissionIdentity(); scenario.close()
        }
    }

    // 服务完全退出后点击小组件下一首，第一次点击也必须恢复并切换持久队列。
    @Test fun coldWidgetNextRestoresAndChangesThePausedQueue() {
        val first = ActivityScenario.launch(MainActivity::class.java)
        prepare(first); first.close()
        context.stopService(android.content.Intent(context, com.kimo.music.playback.PlaybackService::class.java))
        waitFor {
            @Suppress("DEPRECATION")
            val services = context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            services.none { it.service.className == com.kimo.music.playback.PlaybackService::class.java.name }
        }
        com.kimo.music.playback.PlaybackCheckpointStore.awaitWrites()
        context.sendBroadcast(android.content.Intent(context, MusicWidgetProvider::class.java).setAction(MusicWidgetProvider.NEXT))
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var nextId: String? = null
        while (SystemClock.elapsedRealtime() < deadline && nextId != DemoCatalog.tracks[1].id) {
            val saved = com.kimo.music.playback.PlaybackCheckpointStore.read(context).get()
            nextId = saved?.items?.getOrNull(saved.index)?.mediaId
            SystemClock.sleep(50)
        }
        assertEquals(DemoCatalog.tracks[1].id, nextId)
        val reopened = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: MusicViewModel
            reopened.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
            waitFor { model.playback.track?.id == DemoCatalog.tracks[1].id }
            assertFalse(model.playback.playing)
        } finally { reopened.close() }
    }

    // 应用退到后台后出现真实系统悬浮窗，关闭按钮会同步关闭偏好并移除窗口。
    @Test fun desktopLyricsAppearInBackgroundAndCanBeClosed() {
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = prepare(scenario)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            scenario.onActivity { activity ->
                model.listening.update(model.listening.options.copy(desktopLyrics = true))
                activity.moveTaskToBack(true)
            }
            waitFor { closeLyricsNode() != null }
            assertTrue("桌面歌词应展示真实歌曲名", automation.windows.any { window ->
                window.root?.findAccessibilityNodeInfosByText(DemoCatalog.tracks.first().title)?.isNotEmpty() == true
            })
            screenshot("desktop-lyrics")
            dragLyricsPanel()
            assertTrue(context.getSharedPreferences("kimo_lyrics_position", Context.MODE_PRIVATE).contains("y"))
            val close = closeLyricsNode()!!
            assertTrue(close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor { !model.listening.options.desktopLyrics && closeLyricsNode() == null }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            scenario.close(); shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }

    // 向真实悬浮窗注入拖动手势，位置必须在松手时保存。
    private fun dragLyricsPanel() {
        val root = instrumentation.uiAutomation.windows.first { findClose(it.root) != null }.root
        val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val x = bounds.left + bounds.width() / 3f; val y = bounds.centerY().toFloat()
        val started = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN to 0f, android.view.MotionEvent.ACTION_MOVE to 90f,
            android.view.MotionEvent.ACTION_UP to 90f).forEachIndexed { index, (action, offset) ->
            val event = android.view.MotionEvent.obtain(started, started + index * 100, action, x, y + offset, 0)
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    // 从系统可访问窗口寻找关闭按钮，验证的不是仅存在于内存的歌词状态。
    private fun closeLyricsNode(): android.view.accessibility.AccessibilityNodeInfo? =
        instrumentation.uiAutomation.windows.firstNotNullOfOrNull { findClose(it.root) }

    // 悬浮窗原生控件保留内容说明，逐层查找实际可点击节点。
    private fun findClose(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == "关闭桌面歌词") return node
        return (0 until node.childCount).firstNotNullOfOrNull { findClose(node.getChild(it)) }
    }

    // 先准备并暂停同一媒体会话，桌面操作必须使该会话状态发生变化。
    private fun prepare(scenario: ActivityScenario<MainActivity>): MusicViewModel {
        lateinit var model: MusicViewModel
        scenario.onActivity { model = ViewModelProvider(it)[MusicViewModel::class.java] }
        waitFor { model.playback.ready }
        main { model.play(DemoCatalog.tracks.first(), DemoCatalog.tracks) }
        waitFor { model.playback.playing }
        main { model.togglePlaying() }; waitFor { !model.playback.playing }
        return model
    }

    // 所有播放器和原生视图访问在主线程执行。
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    // 测试设备独立设置悬浮权限，结束时恢复系统默认值。
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
    } }

    // 等待系统广播与服务回报，避免依赖固定延迟。
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false; main { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("等待真实桌面状态超时")
    }

    // 保存含系统窗口的截图，供视觉核查。
    private fun screenshot(name: String) {
        val directory = File(context.cacheDir, "kimo-expanded-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
