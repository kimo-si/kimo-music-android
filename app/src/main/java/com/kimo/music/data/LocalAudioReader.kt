package com.kimo.music.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

class LocalAudioReader(private val context: Context) {
    // 此同步读取函数由 ViewModel 放入 IO 调度器，解析失败时交由调用方报告。
    fun read(uri: Uri): Track {
        // 持有系统文件选择器授予的读取权限，重启后仍能播放用户导入的文件。
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            createTrack(uri, retriever, displayName(uri))
        } finally {
            retriever.release()
        }
    }

    // 从媒体元数据构造歌曲，缺失标题时使用文件名。
    private fun createTrack(uri: Uri, reader: MediaMetadataRetriever, name: String): Track = Track(
        id = uri.toString(), title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.takeIf(String::isNotBlank) ?: name.substringBeforeLast('.'),
        artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "本地音乐",
        album = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "本地音乐",
        mediaUri = uri.toString(), durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0, artwork = ListeningMood.Slow.artwork, fileName = name, local = true,
        coverUrl = runCatching { cacheArtwork(reader.embeddedPicture) }.getOrNull(),
    )

    // 保存音频自身携带的专辑图，按内容摘要去重，原子写入避免残缺图片。
    private fun cacheArtwork(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(context.filesDir, "cover-cache/$digest.img")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val target = AtomicFile(file)
            val output = target.startWrite()
            try { output.write(bytes); target.finishWrite(output) }
            catch (error: Exception) { target.failWrite(output); throw error }
        }
        return Uri.fromFile(file).toString()
    }

    // 向文档提供方读取展示文件名，不把内容 URI 当成文件系统路径。
    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return "本地音频"
    }

    private companion object { const val MAX_ARTWORK_BYTES = 12 * 1024 * 1024 }
}
