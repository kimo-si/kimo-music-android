package com.kimo.music

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.LibraryStore
import org.junit.rules.ExternalResource
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.downloads.AudioDownloadService
import com.kimo.music.downloads.DownloadRepository
import com.kimo.music.playback.PlaybackCheckpointStore
import com.kimo.music.playback.PlaybackService
import kotlinx.coroutines.runBlocking

// 界面测试临时使用本机曲库，结束后恢复设备原有数据；账号容器独立不参与测试。
class LocalLibraryFixture(private val clearOnStart: Boolean = false, private val seedTracks: Boolean = false,
    private val seedLegacyDemo: Boolean = false) : ExternalResource() {
    private data class SavedPreference(val name: String, val value: Any?)
    private data class PreferenceFile(val name: String, val entries: List<SavedPreference>)
    private var saved = emptyList<PreferenceFile>()

    // 在 Activity 启动前保存所有实际条目，需要空库的流程才执行临时清理。
    override fun before() {
        stopTestServices()
        val preferences = preferences()
        saved = FILES.map { name -> PreferenceFile(name, preferences(name).all.map { SavedPreference(it.key, it.value) }) }
        FILES.filterNot { it == "kimo_library" }.forEach { preferences(it).edit().clear().commit() }
        if (clearOnStart) preferences.edit().clear().commit()
        runBlocking { DownloadRepository.get(ApplicationProvider.getApplicationContext()).reload() }
        if (seedTracks) {
            val store = LibraryStore(ApplicationProvider.getApplicationContext())
            store.saveLocalTracks((store.localTracks() + DemoCatalog.tracks).distinctBy { it.id })
        }
        if (seedLegacyDemo) {
            val store = LibraryStore(ApplicationProvider.getApplicationContext())
            store.saveFavorites(setOf(DemoCatalog.tracks.first().id))
            store.record(DemoCatalog.tracks.first())
        }
    }

    // Activity 释放后一次性恢复，避免测试留下播放历史或覆盖导入记录。
    override fun after() {
        stopTestServices()
        saved.forEach { file ->
            val editor = preferences(file.name).edit().clear()
            file.entries.forEach { restore(editor, it) }
            check(editor.commit()) { "本机测试快照恢复失败：${file.name}" }
        }
        runBlocking { DownloadRepository.get(ApplicationProvider.getApplicationContext()).reload() }
    }

    // 测试只操作音乐库偏好，不触碰账号密文或云端收藏。
    private fun preferences(name: String = "kimo_library"): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    // 等待媒体服务最后的串行写入完成，再读取或恢复测试快照。
    private fun stopTestServices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, AudioDownloadService::class.java))
        context.stopService(Intent(context, PlaybackService::class.java))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        PlaybackCheckpointStore.awaitWrites()
    }

    // 保留 SharedPreferences 的原始类型，未来新增的偏好也能完整恢复。
    private fun restore(editor: SharedPreferences.Editor, preference: SavedPreference) {
        when (val value = preference.value) {
            is String -> editor.putString(preference.name, value)
            is Boolean -> editor.putBoolean(preference.name, value)
            is Int -> editor.putInt(preference.name, value)
            is Long -> editor.putLong(preference.name, value)
            is Float -> editor.putFloat(preference.name, value)
            is Set<*> -> editor.putStringSet(preference.name, value.filterIsInstance<String>().toSet())
            null -> editor.remove(preference.name)
            else -> error("不支持的偏好类型")
        }
    }

    private companion object {
        val FILES = listOf("kimo_library", "kimo_playback", "kimo_listening", "kimo_downloads", "kimo_lyrics_position")
    }
}
