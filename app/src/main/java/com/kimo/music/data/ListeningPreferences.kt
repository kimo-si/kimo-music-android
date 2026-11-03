package com.kimo.music.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kimo.music.core.AudioQuality
import org.json.JSONArray
import org.json.JSONObject

data class ListeningOptions(val volume: Float = 0.8f, val fadeMs: Long = 0,
    val desktopLyrics: Boolean = false, val equalizerEnabled: Boolean = false,
    val equalizerGains: List<Int> = emptyList(), val quality: AudioQuality = AudioQuality.Standard)

class ListeningPreferences(context: Context, private val onChanged: (ListeningOptions) -> Unit = {}) {
    private val preferences = context.applicationContext.getSharedPreferences("kimo_listening", Context.MODE_PRIVATE)
    var options by mutableStateOf(read()); private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> options = read(); onChanged(options) }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    // 设置以一个完整对象写入，服务与界面读取相同配置。
    fun update(value: ListeningOptions) {
        val safe = value.copy(volume = value.volume.coerceIn(0f, 1f), fadeMs = value.fadeMs.coerceIn(0, 5_000),
            equalizerGains = value.equalizerGains.map { it.coerceIn(-1_500, 1_500) })
        options = safe
        preferences.edit { putString("options", encode(safe).toString()) }
    }

    // 读取旧设置或损坏内容时保留可播放的默认配置。
    private fun read(): ListeningOptions = runCatching {
        val data = JSONObject(preferences.getString("options", "{}").orEmpty())
        val gains = data.optJSONArray("gains") ?: JSONArray()
        ListeningOptions(data.optDouble("volume", 0.8).toFloat().coerceIn(0f, 1f),
            data.optLong("fade").coerceIn(0, 5_000), data.optBoolean("lyrics"), data.optBoolean("eq"),
            (0 until gains.length()).map { gains.optInt(it).coerceIn(-1_500, 1_500) },
            AudioQuality.entries.firstOrNull { it.name == data.optString("quality") } ?: AudioQuality.Standard)
    }.getOrDefault(ListeningOptions())

    // 只保存用户设置，不存音频数据或账号凭据。
    private fun encode(value: ListeningOptions): JSONObject = JSONObject().put("volume", value.volume)
        .put("fade", value.fadeMs).put("lyrics", value.desktopLyrics).put("eq", value.equalizerEnabled)
        .put("gains", JSONArray(value.equalizerGains)).put("quality", value.quality.name)

    // 持有者结束后移除监听，避免服务反复重建留下引用。
    fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}
