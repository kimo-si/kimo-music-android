package com.kimo.music.data

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val mediaUri: String,
    val durationMs: Long,
    val fileName: String = "$title.mp3",
    val local: Boolean = false,
    val hash: String = "",
    val albumId: Long = 0,
    val mixSongId: Long = 0,
    val coverUrl: String? = null,
    val cloudFileId: Long = 0,
    val cloudDrive: Boolean = false,
    val favoriteAddedAtMs: Long = 0,
    val metadataVerified: Boolean = true,
) {
    // 旧版本把缺失的本地标签写成固定文字，确认来源前不把它当成真实信息展示。
    val needsMetadataVerification: Boolean get() = local && !metadataVerified &&
        (artist == LEGACY_LOCAL_CREDIT || album == LEGACY_LOCAL_CREDIT)
    val displayArtist: String get() = displayCredit(artist)
    val displayAlbum: String get() = displayCredit(album)

    // 只隐藏来源不明的旧占位，不影响新导入的同名真实标签。
    private fun displayCredit(value: String): String =
        if (needsMetadataVerification && value == LEGACY_LOCAL_CREDIT) "" else value

    // 文件可读取时仅替换旧占位，其他已保存的信息原样保留。
    fun withVerifiedCredits(credits: AudioCredits): Track = copy(
        artist = if (artist == LEGACY_LOCAL_CREDIT) credits.artist else artist,
        album = if (album == LEGACY_LOCAL_CREDIT) credits.album else album,
        metadataVerified = true,
    )
}

private const val LEGACY_LOCAL_CREDIT = "本地音乐"

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

// 使用真实毫秒时长显示进度，并安全处理播放器尚未解析的时长。
fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

// 领域层统一搜索规则，供首页、发现页与搜索对话框复用。
fun List<Track>.matching(query: String): List<Track> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return this
    return filter { "${it.title} ${it.displayArtist} ${it.displayAlbum}".contains(normalized, ignoreCase = true) }
}
