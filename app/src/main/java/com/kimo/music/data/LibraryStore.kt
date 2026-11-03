package com.kimo.music.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

enum class AppearanceMode(val title: String, val description: String) {
    Light("暖纸浅色", "温暖米白，轻盈清爽"),
    Dark("午夜深色", "柔和炭黑，适合夜间聆听"),
    System("跟随系统", "随设备的深浅模式自动切换");

    companion object {
        // 旧版本和无效偏好沿用应用原有的浅色外观。
        fun fromStoredValue(value: String?): AppearanceMode = entries.firstOrNull { it.name == value } ?: Light
    }
}

// 轻量偏好与本地曲目元数据独立存储，不保存音频副本，也不申请整个媒体库权限。
class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences("kimo_library", Context.MODE_PRIVATE)
    val appearance: AppearanceMode get() = AppearanceMode.fromStoredValue(preferences.getString(APPEARANCE, null))
    val favorites: Set<String> get() = preferences.getStringSet("favorites", emptySet()).orEmpty().toSet()
    val recentIds: List<String> get() = readArray("recent").let { array ->
        List(array.length()) { array.optString(it) }.filter(String::isNotBlank)
    }
    val searchHistory: List<String> get() = readArray(SEARCH_HISTORY).let { array ->
        List(array.length()) { array.optString(it).trim() }.filter(String::isNotBlank).distinct().take(SEARCH_LIMIT)
    }

    // apply 立即更新内存并由系统异步写盘，切换皮肤无需阻塞界面。
    fun saveAppearance(mode: AppearanceMode) {
        preferences.edit { putString(APPEARANCE, mode.name) }
    }

    // 最近搜索仅在本机保存，去重并限制长度，不预填参考图中的虚构历史。
    fun saveSearchHistory(keywords: List<String>) {
        val values = keywords.map(String::trim).filter(String::isNotBlank).distinct().take(SEARCH_LIMIT)
        preferences.edit { putString(SEARCH_HISTORY, JSONArray(values).toString()) }
    }

    // 旧收藏没有时间时保留零值，兼容既有曲库而不伪造收藏日期。
    fun favoriteMarks(): List<FavoriteMark> {
        val values = readArray(FAVORITE_MARKS)
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            value.optString("trackId").takeIf(String::isNotBlank)?.let { FavoriteMark(it, value.optLong("addedAtMs")) }
        }
    }

    // 收藏集合和时间元数据同一次编辑写入，取消后重新收藏会获得新的排序时间。
    fun saveFavorites(ids: Set<String>) {
        val existingIds = favorites
        val existing = favoriteMarks().associateBy(FavoriteMark::trackId)
        var nextTime = maxOf(System.currentTimeMillis(), (existing.values.maxOfOrNull(FavoriteMark::addedAtMs) ?: 0) + 1)
        val values = JSONArray()
        ids.forEach { id ->
            val mark = existing[id] ?: FavoriteMark(id, if (id in existingIds) 0 else nextTime++)
            values.put(JSONObject().put("trackId", mark.trackId).put("addedAtMs", mark.addedAtMs))
        }
        preferences.edit { putStringSet("favorites", ids.toSet()); putString(FAVORITE_MARKS, values.toString()) }
    }

    // 最近播放去重后保持倒序，限制历史长度避免无界增长。
    fun record(track: Track) {
        val ids = (listOf(track.id) + recentIds).distinct().take(HISTORY_LIMIT)
        preferences.edit { putString("recent", JSONArray(ids).toString()) }
    }

    // 将存储边界的 JSON 映射回强类型歌曲对象，损坏记录单独跳过。
    fun localTracks(): List<Track> {
        val array = readArray("local_tracks")
        return (0 until array.length()).mapNotNull { index ->
            runCatching { decode(array.getJSONObject(index)) }.getOrNull()
        }
    }

    // 本地导入结果一次性写入，界面和播放器始终接收歌曲对象。
    fun saveLocalTracks(tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { track -> array.put(encode(track)) }
        preferences.edit { putString("local_tracks", array.toString()) }
    }

    // 播放历史保留在线歌曲元数据，重启后不会因尚未打开对应曲库而丢失曲目。
    fun onlineTracks(): List<Track> {
        val array = readArray("online_history_tracks")
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val value = array.getJSONObject(index)
                decode(value).copy(local = false)
            }.getOrNull()
        }
    }

    // 这里只缓存历史元数据，云收藏和账号歌单仍以服务端全量同步结果为准。
    fun rememberOnlineTrack(track: Track) {
        val tracks = (listOf(track) + onlineTracks()).distinctBy(Track::id).take(HISTORY_LIMIT)
        val values = JSONArray()
        tracks.forEach { item -> values.put(encode(item)) }
        preferences.edit { putString("online_history_tracks", values.toString()) }
    }

    // 无效偏好数据回退为空列表，不阻断播放器启动。
    private fun readArray(key: String): JSONArray =
        runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrDefault(JSONArray())

    // 仅在持久化边界序列化歌曲的稳定元数据。
    private fun encode(track: Track): JSONObject = TrackStorage.encode(track)

    // 本地文件统一使用静物封面，避免错误地显示另一首歌的专辑图。
    private fun decode(value: JSONObject): Track = TrackStorage.decode(value)

    private companion object {
        const val HISTORY_LIMIT = 100
        const val FAVORITE_MARKS = "favorite_marks"
        const val SEARCH_HISTORY = "search_history"
        const val SEARCH_LIMIT = 12
        const val APPEARANCE = "appearance_mode"
    }
}
