package com.kimo.music.downloads

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.kimo.music.core.AudioQuality
import com.kimo.music.data.Track
import com.kimo.music.data.TrackStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class DownloadStatus(val title: String) {
    Queued("等待下载"), Downloading("下载中"), Paused("已暂停"), Failed("下载失败"), Complete("已下载")
}
data class DownloadItem(val id: String, val track: Track, val quality: AudioQuality,
    val status: DownloadStatus = DownloadStatus.Queued, val received: Long = 0, val total: Long = 0,
    val etag: String = "", val error: String = "", val createdAtMs: Long = System.currentTimeMillis())

class DownloadRepository private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("kimo_downloads", Context.MODE_PRIVATE)
    private val directory = File(context.applicationContext.filesDir, "audio-downloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = state
    private val ready = scope.async { state.value = readItems() }

    // 使用歌曲与音质组成稳定任务标识，同一资源不会创建重复任务。
    suspend fun enqueue(track: Track, quality: AudioQuality): DownloadItem {
        val id = "${track.id}-${quality.code}"
        change { rows ->
            val existing = rows.firstOrNull { it.id == id }
            if (existing != null) rows else listOf(DownloadItem(id, track, quality)) + rows
        }
        return requireNotNull(state.value.firstOrNull { it.id == id })
    }

    // 状态变化串行持久化，取消、重试与完成不会互相覆盖。
    suspend fun status(id: String, status: DownloadStatus, error: String = "") {
        update(id) { it.copy(status = status, error = error) }
    }

    // 进度高频更新仅保留内存，暂停或完成时再写入完整检查点。
    suspend fun progress(id: String, received: Long, total: Long) {
        update(id, persist = false) { it.copy(received = received, total = total) }
    }

    // 保存服务端实体标识，只有验证同一实体后才允许续传已有片段。
    suspend fun metadata(id: String, received: Long, total: Long, etag: String) {
        update(id) { it.copy(received = received, total = total, etag = etag) }
    }

    // 删除只触及应用管理的下载目录，不删除用户导入的原文件。
    suspend fun remove(id: String) {
        change { rows -> partialFile(id).delete(); audioFile(id).delete(); rows.filterNot { it.id == id } }
    }

    // 读取单个任务时等待初始化，避免启动阶段出现空数据覆盖。
    suspend fun item(id: String): DownloadItem? { ready.await(); return state.value.firstOrNull { it.id == id } }

    // 测试快照恢复或外部清理偏好后重新读取索引，现有观察者继续使用同一仓库。
    suspend fun reload() { ready.await(); withContext(Dispatchers.IO) { mutex.withLock { state.value = readItems() } } }

    // 已下载资源优先使用对应音质，离线时也可使用该歌曲其他已下载音质。
    suspend fun cached(track: Track, quality: AudioQuality): File? {
        ready.await()
        val candidates = state.value.filter { it.status == DownloadStatus.Complete &&
            it.track.hash.equals(track.hash, true) && it.track.albumId == track.albumId }
        val item = candidates.firstOrNull { it.quality == quality } ?: candidates.firstOrNull() ?: return null
        return audioFile(item.id).takeIf { it.isFile && it.length() > 0 }
    }

    // 完成的任务以本地曲目交给曲库，播放器不再请求在线地址。
    fun localTrack(item: DownloadItem): Track = item.track.copy(local = true,
        mediaUri = Uri.fromFile(audioFile(item.id)).toString(), folderName = "Kimo 下载")

    // 资源标识先取摘要再构造路径，外部歌曲字段不能控制本机文件路径。
    fun audioFile(id: String): File = File(directory, "${digest(id)}.audio")

    // 临时片段与正式文件分离，完成前不会作为可离线播放资源展示。
    fun partialFile(id: String): File = File(directory, "${digest(id)}.part")

    // 列表变更与磁盘提交共用同一互斥区，持久化在 IO 线程完成。
    private suspend fun change(persist: Boolean = true, operation: (List<DownloadItem>) -> List<DownloadItem>) {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            state.value = operation(state.value)
            if (persist) preferences.edit(commit = true) { putString("items", encode(state.value).toString()) }
        } }
    }

    // 只修改仍存在的任务，已删除任务的迟到进度不会重新出现。
    private suspend fun update(id: String, persist: Boolean = true, operation: (DownloadItem) -> DownloadItem) {
        change(persist) { rows -> rows.map { if (it.id == id) operation(it) else it } }
    }

    // 应用重启后将未结束的任务恢复为暂停，等待用户继续而非偷偷启动下载。
    private fun readItems(): List<DownloadItem> = runCatching {
        val rows = JSONArray(preferences.getString("items", "[]"))
        (0 until rows.length()).mapNotNull { index -> runCatching { decode(rows.getJSONObject(index)) }.getOrNull() }
    }.getOrDefault(emptyList())

    // 恢复时校验正式文件存在，进度以实际临时文件长度为准。
    private fun decode(value: JSONObject): DownloadItem {
        val id = value.getString("id")
        val saved = DownloadStatus.entries.firstOrNull { it.name == value.optString("status") } ?: DownloadStatus.Paused
        val status = when {
            saved == DownloadStatus.Complete && !audioFile(id).isFile -> DownloadStatus.Failed
            saved in listOf(DownloadStatus.Queued, DownloadStatus.Downloading) -> DownloadStatus.Paused
            else -> saved
        }
        return DownloadItem(id, TrackStorage.decode(value.getJSONObject("track")),
            AudioQuality.entries.firstOrNull { it.name == value.optString("quality") } ?: AudioQuality.Standard,
            status, if (status == DownloadStatus.Complete) audioFile(id).length() else partialFile(id).length(),
            value.optLong("total"), value.optString("etag"),
            if (saved == DownloadStatus.Complete && status == DownloadStatus.Failed) "下载文件已不存在，请重新下载" else value.optString("error"),
            value.optLong("created"))
    }

    // 任务索引只包含歌曲和进度，临时音频 URL 与登录凭据不会落盘。
    private fun encode(rows: List<DownloadItem>): JSONArray = JSONArray().apply {
        rows.forEach { item -> put(JSONObject().put("id", item.id).put("track", TrackStorage.encode(item.track))
            .put("quality", item.quality.name).put("status", item.status.name).put("received", item.received)
            .put("total", item.total).put("etag", item.etag).put("error", item.error).put("created", item.createdAtMs)) }
    }

    // 固定长度摘要使文件名不受 URL 编码或目录分隔符影响。
    private fun digest(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        @Volatile private var instance: DownloadRepository? = null

        // 播放加载器、下载服务与界面共享同一任务仓库。
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context).also { instance = it }
        }
    }
}
