package com.kimo.music.data

import org.json.JSONObject

// 各持久化容器共享同一歌曲编码，账号收藏与历史不会丢失播放或删除所需标识。
object TrackStorage {
    // 一次性编码稳定元数据，不持久化临时播放地址或鉴权令牌。
    fun encode(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id); put("title", track.title); put("artist", track.artist); put("album", track.album)
        put("uri", if (track.online) "" else track.mediaUri); put("duration", track.durationMs); put("fileName", track.fileName)
        put("local", track.local); put("hash", track.hash); put("albumId", track.albumId)
        put("mixSongId", track.mixSongId); put("coverUrl", track.coverUrl); put("cloudFileId", track.cloudFileId)
        put("cloudDrive", track.cloudDrive)
        put("favoriteAddedAtMs", track.favoriteAddedAtMs)
        put("metadataVerified", track.metadataVerified)
        put("folderName", track.folderName)
    }

    // 兼容早期本地库记录，在线曲目始终保留 hash、mixsongid 与云端 fileid。
    fun decode(value: JSONObject): Track {
        val local = value.optBoolean("local", !value.has("hash"))
        return Track(id = value.getString("id"), title = value.getString("title"), artist = value.getString("artist"),
            album = value.optString("album"), mediaUri = value.optString("uri"), durationMs = value.optLong("duration"),
            fileName = value.optString("fileName"), local = local, hash = value.optString("hash"),
            albumId = value.optLong("albumId"), mixSongId = value.optLong("mixSongId"),
            coverUrl = value.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
            cloudFileId = value.optLong("cloudFileId"), cloudDrive = value.optBoolean("cloudDrive"),
            favoriteAddedAtMs = value.optLong("favoriteAddedAtMs"), metadataVerified = value.optBoolean("metadataVerified", false),
            folderName = value.optString("folderName"))
    }
}
