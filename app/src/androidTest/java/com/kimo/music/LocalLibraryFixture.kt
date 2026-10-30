package com.kimo.music

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

// 界面测试临时使用本机曲库，结束后恢复设备原有数据；账号容器独立不参与测试。
class LocalLibraryFixture(private val clearOnStart: Boolean = false) : ExternalResource() {
    private data class SavedPreference(val name: String, val value: Any?)
    private var saved = emptyList<SavedPreference>()

    // 在 Activity 启动前保存所有实际条目，需要空库的流程才执行临时清理。
    override fun before() {
        val preferences = preferences()
        saved = preferences.all.map { SavedPreference(it.key, it.value) }
        if (clearOnStart) preferences.edit().clear().commit()
    }

    // Activity 释放后一次性恢复，避免测试留下播放历史或覆盖导入记录。
    override fun after() {
        val editor = preferences().edit().clear()
        saved.forEach { restore(editor, it) }
        check(editor.commit()) { "本机曲库测试快照恢复失败" }
    }

    // 测试只操作音乐库偏好，不触碰账号密文或云端收藏。
    private fun preferences(): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("kimo_library", Context.MODE_PRIVATE)

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
}
