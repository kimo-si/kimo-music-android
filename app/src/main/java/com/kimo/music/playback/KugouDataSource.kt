package com.kimo.music.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.common.PlaybackException
import com.kimo.music.core.*
import com.kimo.music.data.Track
import kotlinx.coroutines.runBlocking
import com.kimo.music.downloads.DownloadRepository

// 音频地址只在加载线程按当前账号解析，长歌单无需提前请求所有即将过期的播放链接。
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object KugouDataSource {
    const val VIP_LOGIN_REQUIRED_ERROR_CODE = PlaybackException.CUSTOM_ERROR_CODE_BASE + 1

    // 保留 Media3 的本地 URI 支持，仅处理在线歌曲的 kimo 协议。
    fun factory(context: Context): DataSource.Factory {
        val vault = SessionVault(context)
        val playback = KugouPlaybackApi(KugouApi(EmbeddedCore.get(context)) { vault.load()?.credentials })
        val downloads = DownloadRepository.get(context)
        return ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { specification ->
            if (specification.uri.scheme != SCHEME) specification else {
                val track = decode(specification.uri)
                val quality = AudioQuality.entries.firstOrNull { it.code == specification.uri.getQueryParameter("quality") }
                    ?: AudioQuality.Standard
                // Media3 在自己的加载线程执行此同步解析，主线程不参与网络等待。
                val stream = resolveStream(playback, downloads, track, quality)
                specification.withUri(stream.url.toUri())
            }
        }
    }

    // 使用媒体会话可序列化的错误码传递业务原因，避免跨服务后丢失自定义异常类型。
    private fun resolveStream(playback: KugouPlaybackApi, downloads: DownloadRepository, track: Track, quality: AudioQuality): StreamAddress = try {
        runBlocking {
            downloads.cached(track, quality)?.let { StreamAddress(Uri.fromFile(it).toString(), 0) }
                ?: playback.stream(track, quality)
        }
    } catch (error: VipLoginRequiredException) {
        throw DataSourceException(error, VIP_LOGIN_REQUIRED_ERROR_CODE)
    }

    // 队列中保存稳定歌曲标识，切换歌曲时自动重新获取可播放地址。
    fun uri(track: Track, quality: AudioQuality): Uri = Uri.Builder().scheme(SCHEME).authority("track")
        .appendPath(track.hash).appendQueryParameter("album", track.albumId.toString())
        .appendQueryParameter("mix", track.mixSongId.toString()).appendQueryParameter("quality", quality.code)
        .appendQueryParameter("cloud", track.cloudDrive.toString()).appendQueryParameter("name", track.title).build()

    // 加载器仅恢复取流所需的字段，展示信息继续由原始 MediaItem 持有。
    private fun decode(uri: Uri): Track = Track(uri.lastPathSegment.orEmpty(), uri.getQueryParameter("name").orEmpty(), "", "", "", 0,
        hash = uri.lastPathSegment.orEmpty(),
        albumId = uri.getQueryParameter("album")?.toLongOrNull() ?: 0,
        mixSongId = uri.getQueryParameter("mix")?.toLongOrNull() ?: 0,
        cloudDrive = uri.getQueryParameter("cloud").toBoolean())

    private const val SCHEME = "kimo"
}
