package com.kimo.music.core

import android.content.Context
import android.util.AtomicFile
import com.kimo.music.data.TrackStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// 每个账号单独保存同步快照，原子替换避免中途退出留下半份曲库。
class CloudCache(context: Context) {
    private val directory = File(context.filesDir, "cloud-library-cache")

    // 缓存不可用只表示没有可恢复快照，不会删除服务器或其他账号的数据。
    fun load(owner: String): CloudLibraryState? = runCatching {
        val value = JSONObject(file(owner).readFully().toString(Charsets.UTF_8))
        require(value.getString("owner") == owner)
        CloudLibraryState(value.getJSONArray("playlists").objects().map(::decodeEntry),
            value.getJSONArray("favorites").objects().map(TrackStorage::decode), ownerId = owner, fromCache = true, loaded = true)
    }.getOrNull()

    // 只有服务端完整同步成功的结果可以覆盖上次完整快照。
    fun save(state: CloudLibraryState) {
        val owner = requireNotNull(state.ownerId)
        val value = JSONObject().put("owner", owner).put("playlists", JSONArray(state.playlists.map(::encodeEntry)))
            .put("favorites", JSONArray(state.favorites.map(TrackStorage::encode)))
        val target = file(owner)
        val stream = target.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) { target.failWrite(stream); throw error }
    }

    // 用户编号先摘要化，不能成为文件系统路径片段。
    private fun file(owner: String): AtomicFile {
        directory.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }

    // 歌单保留云端编号与类别，恢复后仍能进行正确的同步操作。
    private fun encodeEntry(entry: CatalogEntry): JSONObject = JSONObject().put("id", entry.id).put("title", entry.title)
        .put("kind", entry.kind.name).put("cover", entry.coverUrl).put("count", entry.count)
        .put("listId", entry.listId).put("listType", entry.listType).put("description", entry.description)

    // 解码时校验目录类型，不把损坏条目误当成另一个歌单。
    private fun decodeEntry(value: JSONObject): CatalogEntry = CatalogEntry(value.getString("id"), value.getString("title"),
        CatalogKind.valueOf(value.getString("kind")), value.optString("cover").takeIf { it.isNotBlank() && it != "null" },
        value.optInt("count"), value.optLong("listId"), value.optInt("listType"), value.optString("description"))
}
