package com.kimo.music.core

import com.kimo.music.data.Track
import org.json.JSONArray
import org.json.JSONObject

enum class CatalogKind { Playlist, Rank, Artist, Album, Radio, CloudPlaylist, CloudDrive, Favorites }
data class CatalogEntry(val id: String, val title: String, val kind: CatalogKind,
    val coverUrl: String? = null, val count: Int = 0, val listId: Long = 0, val listType: Int = 0,
    val description: String = "")
data class PageRequest(val page: Int = 1, val size: Int = 30) {
    init { require(page > 0 && size in 1..300) }
}
data class TrackPage(val tracks: List<Track>, val nextPage: Int?, val total: Int = tracks.size)
data class EntryPage(val entries: List<CatalogEntry>, val nextPage: Int?)
data class LyricLine(val timeMs: Long, val text: String)
data class StreamAddress(val url: String, val bitrate: Int)
data class ArtistProfile(val name: String, val avatar: String?, val introduction: String,
    val followers: Long, val albumCount: Long)
enum class ArtistOrder(val title: String, val code: String) { Hot("热门单曲", "hot"), New("最新单曲", "new") }
enum class SearchKind(val title: String, val apiType: String) {
    Song("歌曲", "song"), Playlist("歌单", "special"), Artist("歌手", "author"), Album("专辑", "album")
}
data class SearchResult(val tracks: List<Track> = emptyList(), val entries: List<CatalogEntry> = emptyList(), val nextPage: Int? = null)
data class HotSearch(val keyword: String, val reason: String = "", val track: Track? = null)
class MusicApiException(message: String, val expired: Boolean = false) : Exception(message)

fun interface CoreTransport {
    // 强类型核心协议允许设备实测与隔离的分页、鉴权回归检查共用同一客户端。
    suspend fun execute(module: String, parameters: JSONObject, credentials: JSONObject): JSONObject
}

// 只在协议边界兼容上游命名差异，业务层始终使用对象。
internal fun JSONObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
}.orEmpty()

// 数值字段可能以字符串返回，统一做安全转换。
internal fun JSONObject.number(vararg keys: String): Long = keys.mapNotNull { key ->
    opt(key)?.toString()?.toDoubleOrNull()?.toLong()
}.firstOrNull { it != 0L } ?: 0

// 从多个合法字段中找到列表；字段不存在时返回空列表。
internal fun JSONObject.rows(vararg keys: String): List<JSONObject> {
    val array = keys.firstNotNullOfOrNull { optJSONArray(it) } ?: return emptyList()
    return array.objects()
}

// JSON 数组只在反序列化阶段展开，不向界面透传无类型数据。
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

// 保留原始响应的错误含义，不将登录失败或网络错误误判成空曲库。
internal fun JSONObject.requireSuccess(): JSONObject {
    if (length() == 0) throw MusicApiException("音乐接口未返回有效响应，请重试")
    val code = number("error_code", "errcode")
    val status = number("status")
    val lyricSuccess = status == 200L && code == 200L
    if (code == 20017L || code == 20018L) throw MusicApiException("登录已失效，请重新登录", true)
    if ((code != 0L && !lyricSuccess) || (has("status") && status !in listOf(1L, 200L))) {
        val message = text("error", "errmsg", "error_msg", "msg").ifBlank {
            optString("data").takeIf { opt("data") is String }.orEmpty().ifBlank { "接口请求失败（$code）" }
        }
        throw MusicApiException(message)
    }
    return this
}
