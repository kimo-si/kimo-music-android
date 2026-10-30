package com.kimo.music

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.playback.PlaybackService
import org.junit.Rule
import org.junit.Test

// 原生时钟验收窗口供设备侧录制动画，前后快照保护实际本机收藏与播放历史。
class NativeMotionCaptureTest {
    @get:Rule val library = LocalLibraryFixture()

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
}
