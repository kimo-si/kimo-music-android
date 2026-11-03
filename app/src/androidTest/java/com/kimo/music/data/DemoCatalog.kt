package com.kimo.music.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

// 测试曲目只存在于设备测试包，运行时复制到目标应用缓存，不进入正式安装包。
object DemoCatalog {
    val tracks: List<Track> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            track(context, "evening-wind", "晚风经过", "DUSK NOTES", "evening_wind.mp3", "sunset_coast.png", 252_000),
            track(context, "mountain-letter", "山谷来信", "山野手记", "mountain_letter.mp3", "forest_letter.png", 198_000),
            track(context, "slow-afternoon", "午后三点", "慢半拍", "slow_afternoon.mp3", "slow_afternoon.png", 216_000),
        )
    }

    // 创建指向测试音频和测试封面的本地曲目，供真实媒体服务读取。
    private fun track(context: Context, id: String, title: String, album: String, audio: String, cover: String, duration: Long): Track =
        Track(id, title, "林间回声", album, assetFile(context, audio), duration,
            local = true, coverUrl = assetFile(context, cover))

    // 测试资源由测试 APK 提供，目标应用只读取自身缓存目录内的副本。
    private fun assetFile(context: Context, name: String): String {
        val directory = File(context.cacheDir, "kimo-instrumentation-fixtures").apply { mkdirs() }
        val file = File(directory, name)
        if (!file.exists()) InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(file).toString()
    }
}
