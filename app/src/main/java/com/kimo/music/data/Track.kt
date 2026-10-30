package com.kimo.music.data

import androidx.annotation.DrawableRes
import com.kimo.music.R

enum class ListeningMood(val title: String, val subtitle: String, @DrawableRes val artwork: Int) {
    Sunset("日落电台", "把日落，听慢一点", R.drawable.sunset_coast),
    Forest("山野来信", "风穿过山谷，也穿过你", R.drawable.forest_letter),
    Slow("慢半拍", "在温柔的节奏里，找到自己", R.drawable.slow_afternoon),
}

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val mediaUri: String,
    val durationMs: Long,
    @DrawableRes val artwork: Int,
    val mood: ListeningMood = ListeningMood.Sunset,
    val fileName: String = "$title.mp3",
    val local: Boolean = false,
    val hash: String = "",
    val albumId: Long = 0,
    val mixSongId: Long = 0,
    val coverUrl: String? = null,
    val cloudFileId: Long = 0,
    val cloudDrive: Boolean = false,
    val favoriteAddedAtMs: Long = 0,
)

data class FavoriteMark(val trackId: String, val addedAtMs: Long)

class FavoriteIndex(tracks: List<Track>) {
    private val hashes = tracks.map { it.hash.lowercase() }.filter(String::isNotBlank).toHashSet()
    private val songIds = tracks.map(Track::mixSongId).filter { it > 0 }.toHashSet()

    // 同一首歌兼容不同音质 hash 和云端 mixsongid，每次查询只做集合查找。
    fun contains(track: Track): Boolean = track.hash.lowercase() in hashes ||
        (track.mixSongId > 0 && track.mixSongId in songIds)
}

object FavoriteOrdering {
    // 完整云收藏先倒序，再按真实收藏时间稳定排序；旧缓存无时间仍保持新的在前。
    fun newestFirst(cloud: List<Track>, local: List<Track>, marks: List<FavoriteMark>): List<Track> {
        val dates = marks.associateBy(FavoriteMark::trackId)
        val localTracks = local.asReversed().map { it.copy(favoriteAddedAtMs = dates[it.id]?.addedAtMs ?: 0) }
        return (cloud.asReversed() + localTracks).distinctBy(Track::id).sortedByDescending(Track::favoriteAddedAtMs)
    }
}

// 在线歌曲只保存稳定标识，播放地址在播放时按当前账号权益重新获取。
val Track.online: Boolean get() = hash.isNotBlank() && !local

object DemoCatalog {
    val tracks = listOf(
        Track("evening-wind", "晚风经过", "林间回声", "DUSK NOTES", "asset:///evening_wind.mp3",
            252_000, R.drawable.sunset_coast),
        Track("mountain-letter", "山谷来信", "林间回声", "山野手记", "asset:///mountain_letter.mp3",
            198_000, R.drawable.forest_letter, ListeningMood.Forest),
        Track("slow-afternoon", "午后三点", "林间回声", "慢半拍", "asset:///slow_afternoon.mp3",
            216_000, R.drawable.slow_afternoon, ListeningMood.Slow),
    )
}

// 使用真实毫秒时长显示进度，并安全处理播放器尚未解析的时长。
fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

// 领域层统一搜索规则，供首页、发现页与搜索对话框复用。
fun List<Track>.matching(query: String): List<Track> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return this
    return filter { "${it.title} ${it.artist} ${it.album}".contains(normalized, ignoreCase = true) }
}
