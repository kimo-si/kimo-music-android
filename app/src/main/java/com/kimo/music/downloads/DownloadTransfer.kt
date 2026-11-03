package com.kimo.music.downloads

import android.content.Context
import android.media.MediaMetadataRetriever
import com.kimo.music.core.EmbeddedCore
import com.kimo.music.core.KugouApi
import com.kimo.music.core.KugouPlaybackApi
import com.kimo.music.core.SessionVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import android.os.StatFs
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private data class TransferResponse(val connection: DownloadConnection, val start: Long, val total: Long, val etag: String)

class DownloadTransfer(context: Context, private val repository: DownloadRepository,
    private val resolver: DownloadAddressResolver = OnlineDownloadResolver(context),
    private val transport: DownloadTransport = HttpDownloadTransport) {
    private val connections = ConcurrentHashMap<String, DownloadConnection>()

    // 每次继续任务都重新按账号权益取得地址，已有文件仅在 ETag 验证通过后续传。
    suspend fun run(item: DownloadItem) = withContext(Dispatchers.IO) {
        val partial = repository.partialFile(item.id)
        partial.parentFile?.mkdirs()
        if (item.total > 0 && partial.length() == item.total) { finish(item, partial); return@withContext }
        val stream = resolver.resolve(item.track, item.quality)
        val offset = if (item.etag.isNotBlank() && !item.etag.startsWith("W/", ignoreCase = true)) partial.length() else 0L
        try {
            val response = connect(item.id, stream.url, offset, item.etag)
            repository.metadata(item.id, response.start, response.total, response.etag)
            copy(item.id, response, partial)
            currentCoroutineContext().ensureActive()
            finish(item, partial)
        } finally { connections.remove(item.id)?.close() }
    }

    // 可中断正在等待网络的连接，暂停无需等待整首音频或读超时。
    fun interrupt(id: String) { connections.remove(id)?.close() }

    // 服务销毁时释放全部连接，不遗留后台下载。
    fun close() { connections.keys.toList().forEach(::interrupt) }

    // 不可信的续传响应会从头重新请求，避免将不同实体或错误偏移拼接成损坏音频。
    private fun connect(id: String, address: String, offset: Long, etag: String): TransferResponse {
        val connection = open(id, address, offset, etag)
        val code = connection.status
        if (code == HttpURLConnection.HTTP_PARTIAL && offset > 0) {
            val range = RANGE.matchEntire(connection.contentRange)
            if (range?.groupValues?.get(1)?.toLongOrNull() == offset && connection.etag == etag)
                return TransferResponse(connection, offset, range.groupValues[2].toLong(), etag)
            connection.close()
            return connect(id, address, 0, "")
        }
        if (code != HttpURLConnection.HTTP_OK) throw IOException("下载服务器返回异常，请重试")
        val mime = connection.contentType.lowercase()
        if (mime.startsWith("text/") || mime.contains("json")) throw IOException("服务器未返回有效音频")
        return TransferResponse(connection, 0, connection.length, connection.etag)
    }

    // 连接信息只在本次任务内存在，不将签名 URL 持久化。
    private fun open(id: String, address: String, offset: Long, etag: String): DownloadConnection {
        val connection = transport.open(DownloadRequest(address, offset, etag))
        connections.put(id, connection)?.close()
        return connection
    }

    // 分块写入时检查取消与磁盘空间，进度节流后交给共享仓库。
    private suspend fun copy(id: String, response: TransferResponse, target: File) {
        var received = response.start
        var reportedAt = 0L
        response.connection.input.use { input ->
            java.io.FileOutputStream(target, response.start > 0).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    if (StatFs(target.parentFile!!.path).availableBytes < count + RESERVED_BYTES) throw IOException("存储空间不足，请清理后重试")
                    output.write(buffer, 0, count); received += count
                    if (System.nanoTime() - reportedAt >= PROGRESS_INTERVAL_NS) {
                        repository.progress(id, received, response.total); reportedAt = System.nanoTime()
                    }
                }
                output.fd.sync()
            }
        }
        currentCoroutineContext().ensureActive()
        if (response.total > 0 && received != response.total) throw IOException("下载尚未完成，请继续下载")
        repository.progress(id, received, response.total.takeIf { it > 0 } ?: received)
    }

    // 完整可解析的音频才从临时文件原子移动为正式离线文件。
    private suspend fun finish(item: DownloadItem, partial: File) {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(partial.path)
            require(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it > 0 } == true) {
                "下载的音频无法解析，请重新下载"
            }
        } finally { reader.release() }
        currentCoroutineContext().ensureActive()
        check(partial.renameTo(repository.audioFile(item.id))) { "无法保存下载文件，请检查存储空间" }
        repository.status(item.id, DownloadStatus.Complete)
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val RESERVED_BYTES = 2 * 1024 * 1024
        const val PROGRESS_INTERVAL_NS = 400_000_000L
        val RANGE = Regex("bytes (\\d+)-\\d+/(\\d+)")
    }
}
