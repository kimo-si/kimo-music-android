package com.kimo.music.downloads

import android.content.Context
import com.kimo.music.core.*
import com.kimo.music.data.Track
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadRequest(val url: String, val offset: Long, val etag: String)
interface DownloadConnection : Closeable {
    val status: Int
    val etag: String
    val contentRange: String
    val contentType: String
    val length: Long
    val input: InputStream
}
fun interface DownloadTransport {
    // 网络边界使用明确的请求与响应对象，便于验证续传和取消行为。
    fun open(request: DownloadRequest): DownloadConnection
}
fun interface DownloadAddressResolver {
    // 每次传输独立校验账号权限，不能复用过期签名地址。
    suspend fun resolve(track: Track, quality: AudioQuality): StreamAddress
}

internal class OnlineDownloadResolver(context: Context) : DownloadAddressResolver {
    private val vault = SessionVault(context.applicationContext)
    private val playback = KugouPlaybackApi(KugouApi(EmbeddedCore.get(context)) { vault.load()?.credentials })

    // 与在线播放使用相同的权益检查和音质选择。
    override suspend fun resolve(track: Track, quality: AudioQuality): StreamAddress = playback.stream(track, quality)
}

internal object HttpDownloadTransport : DownloadTransport {
    // 超时与实体范围请求集中配置，返回的连接可被暂停操作主动断开。
    override fun open(request: DownloadRequest): DownloadConnection {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS; connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (request.offset > 0) {
            connection.setRequestProperty("Range", "bytes=${request.offset}-")
            connection.setRequestProperty("If-Range", request.etag)
        }
        return HttpDownloadConnection(connection)
    }

    private const val TIMEOUT_MS = 20_000
}

private class HttpDownloadConnection(private val connection: HttpURLConnection) : DownloadConnection {
    override val status: Int get() = connection.responseCode
    override val etag: String get() = connection.getHeaderField("ETag").orEmpty()
    override val contentRange: String get() = connection.getHeaderField("Content-Range").orEmpty()
    override val contentType: String get() = connection.contentType.orEmpty()
    override val length: Long get() = connection.contentLengthLong.coerceAtLeast(0)
    override val input: InputStream get() = connection.inputStream

    // 取消时关闭实际网络连接，让阻塞读取及时结束。
    override fun close() { connection.disconnect() }
}
