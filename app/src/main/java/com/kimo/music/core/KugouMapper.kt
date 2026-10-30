package com.kimo.music.core

import com.kimo.music.R
import com.kimo.music.data.Track
import org.json.JSONObject

object KugouMapper {
    // 兼容搜索、榜单、歌单与云收藏的嵌套字段，优先使用标准音频 hash。
    fun track(value: JSONObject): Track? {
        val audio = value.optJSONObject("audio_info") ?: value.optJSONObject("deprecated") ?: JSONObject()
        val album = value.optJSONObject("album_info") ?: value.optJSONObject("albuminfo") ?: JSONObject()
        val hash = value.text("hash", "FileHash").ifBlank { audio.text("hash_128", "hash_320", "hash") }
        if (hash.isBlank()) return null
        val rawTitle = value.text("songname", "audio_name", "FileName", "filename", "name", "ori_audio_name")
        val artist = artistName(value).takeIf { it != "未知歌手" }
            ?: rawTitle.substringBefore(" - ", "未知歌手")
        val duration = value.number("time_length", "timelength", "timelen", "Duration", "duration_128")
            .takeIf { it > 0 } ?: audio.number("duration_128", "duration_320", "duration")
        return Track(id = hash.lowercase(), hash = hash, title = cleanTitle(rawTitle, artist), artist = artist,
            album = value.text("album_name", "AlbumName").ifBlank { album.text("name", "albumname", "album_name") },
            mediaUri = "", durationMs = if (duration > 10_000) duration else duration * 1000,
            artwork = R.drawable.sunset_coast, albumId = value.number("album_id", "AlbumID"),
            mixSongId = value.number("album_audio_id", "mixsongid", "MixSongID"),
            coverUrl = cover(value, album), cloudFileId = value.number("fileid", "file_id"),
            favoriteAddedAtMs = favoriteTime(value))
    }

    // 只读取收藏时间，不把专辑发行日期当作加入时间；兼容秒与毫秒时间戳。
    private fun favoriteTime(value: JSONObject): Long {
        val timestamp = value.number("collecttime", "collect_time", "addtime", "add_time").coerceAtLeast(0)
        return if (timestamp < 1_000_000_000_000L) timestamp * 1000 else timestamp
    }

    // 艺人数组与文本字段统一为可读歌手名。
    private fun artistName(value: JSONObject): String = value.text("author_name", "singername", "SingerName")
        .ifBlank { value.rows("singerinfo", "authors").joinToString("、") { it.text("author_name", "name") } }
        .ifBlank { "未知歌手" }

    // 云歌单的文件名移除扩展名与重复歌手前缀。
    private fun cleanTitle(raw: String, artist: String): String {
        val withoutExtension = raw.replace(Regex("\\.(mp3|flac|ogg|m4a|wav|ape|wma)$", RegexOption.IGNORE_CASE), "")
        return withoutExtension.removePrefix("$artist - ").trim().ifBlank { "未知歌曲" }
    }

    // 封面尺寸占位符由统一入口替换，所有页面使用同一张真实封面。
    private fun cover(value: JSONObject, album: JSONObject): String? {
        val raw = value.text("sizable_cover", "album_sizable_cover", "cover", "img", "album_img", "Image")
            .ifBlank { album.text("sizable_cover", "cover") }
            .ifBlank { value.optJSONObject("trans_param")?.text("union_cover").orEmpty() }
        return sizedImage(raw)
    }

    // 歌单必须优先保留全局收藏标识，避免把展示 ID 误用于取曲接口。
    fun entry(value: JSONObject, kind: CatalogKind): CatalogEntry? {
        val id = when (kind) {
            CatalogKind.Rank -> value.text("rankid", "id")
            CatalogKind.Artist -> value.text("author_id", "singerid", "id", "AuthorId")
            CatalogKind.Album -> value.text("album_id", "albumid", "AlbumID", "id")
            CatalogKind.Radio -> value.text("fmid", "fm_id", "id", "classid")
            else -> value.text("global_collection_id", "gid", "specialid", "specialId", "listid", "id")
        }
        if (id.isBlank()) return null
        return CatalogEntry(id, value.text("specialname", "rankname", "author_name", "AuthorName", "singername", "SingerName", "album_name", "AlbumName", "albumname", "fmname", "name", "title")
            .ifBlank { "未命名" }, kind,
            sizedImage(value.text("flexible_cover", "imgurl", "img_cover", "cover", "sizable_cover", "sizable_avatar", "avatar", "Avatar", "img_9", "img", "pic")),
            value.number("percount", "songcount", "song_count", "count", "total").toInt(),
            value.number("listid"), value.number("type").toInt(), value.text("intro", "description", "update_frequency"))
    }

    // 只接受网络图片地址，空封面保留本地占位，不伪造其他专辑图片。
    fun sizedImage(raw: String): String? = raw.replace("{size}", "480").replace("{size:}", "480")
        .takeIf { it.startsWith("https://") || it.startsWith("http://") }
}
