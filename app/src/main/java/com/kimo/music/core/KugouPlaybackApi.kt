package com.kimo.music.core

import com.kimo.music.data.Track
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

const val VIP_LOGIN_REQUIRED_MESSAGE = "这首歌需要 VIP 权益，请先登录"
class VipLoginRequiredException : Exception(VIP_LOGIN_REQUIRED_MESSAGE)

enum class AudioQuality(val title: String, val code: String) {
    Standard("标准", "128"), High("高品", "320"), Lossless("无损", "flac")
}

class KugouPlaybackApi(private val api: KugouApi) {
    // 按实际账号权益请求音质，地址不可用时只向较低音质降级。
    suspend fun stream(track: Track, quality: AudioQuality): StreamAddress {
        if (track.cloudDrive) return cloudStream(track)
        // 未登录时先核对真实标准音质权益，避免进入注定被拒绝的会员取流流程。
        requireGuestAccess(track)
        val hashes = mutableListOf(track.hash)
        var cursor = 0
        while (cursor < hashes.size) {
            val hash = hashes[cursor++]
            // 按音质及备用 hash 查找地址，账号失效等服务端错误直接向调用方传播。
            streamForHash(hash, track, quality, hashes)?.let { return it }
        }
        throw MusicApiException("当前账号没有这首歌的可用播放地址，请检查登录状态或音质")
    }

    // 只拦截资源接口明确核实的 VIP；免费、未知权益及已登录账号继续按实际取流结果处理。
    private suspend fun requireGuestAccess(track: Track) {
        if (api.hasSession()) return
        val status = try { api.memberships(listOf(track)).singleOrNull()?.status }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MusicApiException) { return }
        if (!api.hasSession() && status == MembershipStatus.Vip) throw VipLoginRequiredException()
    }

    // 单个 hash 内从所选音质向下请求，同时收集服务端提供的标准 hash。
    private suspend fun streamForHash(hash: String, track: Track, quality: AudioQuality, hashes: MutableList<String>): StreamAddress? {
        for (candidate in AudioQuality.entries.take(quality.ordinal + 1).reversed()) {
            for (mix in listOf(track.mixSongId, 0L).distinct()) {
                val response = api.request("song_url", JSONObject().put("hash", hash).put("album_id", track.albumId)
                    .put("album_audio_id", mix).put("quality", candidate.code))
                firstAddress(response)?.let { return StreamAddress(it, response.number("bitRate", "bitrate").toInt()) }
                val standard = response.text("std_hash")
                if (standard.length >= 32 && hashes.none { it.equals(standard, true) }) hashes.add(standard)
            }
        }
        return null
    }

    // 用户上传的云盘音频走专用取流接口，保留原始文件，不能套用平台曲库的权益地址。
    private suspend fun cloudStream(track: Track): StreamAddress {
        val response = api.request("user_cloud_url", JSONObject().put("hash", track.hash)
            .put("name", track.title).put("album_audio_id", track.mixSongId))
        val data = response.optJSONObject("data") ?: response
        val address = firstAddress(data) ?: throw MusicApiException("云盘未返回可用的音频地址，请重新同步或稍后重试")
        return StreamAddress(address, data.number("bitRate", "bitrate").toInt())
    }

    // 歌词搜索与下载分两步完成，不把其他歌曲的文案作为同步歌词。
    suspend fun lyrics(track: Track): List<LyricLine> {
        val response = api.request("search_lyric", JSONObject().put("hash", track.hash))
        val data = response.optJSONObject("data") ?: response
        val candidate = data.rows("candidates").firstOrNull() ?: return emptyList()
        val lyric = api.request("lyric", JSONObject().put("id", candidate.text("id"))
            .put("accesskey", candidate.text("accesskey")).put("decode", true).put("fmt", "lrc"))
        val payload = lyric.optJSONObject("data") ?: lyric
        return parseLrc(payload.text("decodeContent", "content"))
    }

    // 取流接口兼容主地址和备用地址数组，拒绝非法 URI。
    private fun firstAddress(response: JSONObject): String? {
        val candidates = mutableListOf(response.text("play_url", "playUrl"), response.optString("url"))
        for (key in listOf("url", "backupUrl")) response.optJSONArray(key)?.let { array ->
            repeat(array.length()) { candidates.add(array.optString(it)) }
        }
        return candidates.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }?.replace("\\/", "/")
    }

    companion object {
        // LRC 支持一行多个时间戳、任意换行与全局偏移。
        fun parseLrc(source: String): List<LyricLine> {
            val timestamp = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]")
            val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
                .find(source)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            return source.lineSequence().flatMap { line ->
                val stamps = timestamp.findAll(line).toList()
                val text = timestamp.replace(line, "").trim()
                if (text.isBlank()) emptySequence() else stamps.asSequence().map { stamp ->
                    LyricLine((stamp.groupValues[1].toLong() * 60_000 +
                        (stamp.groupValues[2].toDouble() * 1000).toLong() + offset).coerceAtLeast(0), text)
                }
            }.sortedBy(LyricLine::timeMs).toList()
        }
    }
}
