package com.kimo.music

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.data.LibraryStore
import com.kimo.music.data.LocalAudioReader
import com.kimo.music.data.DemoCatalog
import com.kimo.music.data.FavoriteOrdering
import com.kimo.music.data.TrackStorage
import com.kimo.music.core.KugouMapper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import com.kimo.music.R
import java.nio.ByteBuffer

class LocalAudioTest {
    @get:Rule val library = LocalLibraryFixture()

    // 收藏时间写入后可恢复，取消再收藏会重新排到第一；不写入云端账号。
    @Test fun favoritesKeepNewestFirstAfterReloadAndReliking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LibraryStore(context)
        val tracks = DemoCatalog.tracks.take(2)
        store.saveFavorites(emptySet())
        store.saveFavorites(setOf(tracks[0].id))
        store.saveFavorites(tracks.map { it.id }.toSet())
        val restored = LibraryStore(context)
        assertEquals(tracks.reversed().map { it.id },
            FavoriteOrdering.newestFirst(emptyList(), tracks, restored.favoriteMarks()).map { it.id })
        restored.saveFavorites(setOf(tracks[1].id))
        restored.saveFavorites(tracks.map { it.id }.toSet())
        assertEquals(tracks.map { it.id },
            FavoriteOrdering.newestFirst(emptyList(), tracks, LibraryStore(context).favoriteMarks()).map { it.id })
    }

    // 云收藏的秒/毫秒日期正确归一并保存，旧缓存缺少新字段仍保留曲目标识。
    @Test fun cloudFavoriteDatesSurviveCacheRoundTrip() {
        val value = JSONObject().put("hash", "favorite-test").put("fileid", 42).put("collecttime", 1_700_000_000L)
        val track = requireNotNull(KugouMapper.track(value))
        assertEquals(1_700_000_000_000L, track.favoriteAddedAtMs)
        assertEquals(track, TrackStorage.decode(TrackStorage.encode(track)))
        value.put("collecttime", 1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, requireNotNull(KugouMapper.track(value)).favoriteAddedAtMs)
        val legacy = TrackStorage.encode(track).apply { remove("favoriteAddedAtMs") }
        assertEquals(0L, TrackStorage.decode(legacy).favoriteAddedAtMs)
        assertEquals(42L, TrackStorage.decode(legacy).cloudFileId)
    }

    // 从真实 MP3 的 APIC 标签提取该文件自己的封面，验证原始图片字节完整保留。
    @Test fun importedArtworkComesFromTheAudioFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = createAudioDocument(context, embeddedArtwork = true)
        try {
            val track = LocalAudioReader(context).read(uri)
            val cover = Uri.parse(requireNotNull(track.coverUrl))
            val actual = requireNotNull(context.contentResolver.openInputStream(cover)).use { it.readBytes() }
            val expected = context.resources.openRawResource(R.drawable.forest_letter).use { it.readBytes() }
            assertArrayEquals(expected, actual)
            assertEquals("file", cover.scheme)
        } finally { context.contentResolver.delete(uri, null, null) }
    }
    // 从真实 Content URI 解析 MP3，并验证本地曲目和收藏在重建存储对象后恢复。
    @Test fun importedAudioMetadataAndLibrarySurviveReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = createAudioDocument(context)
        try {
            val track = LocalAudioReader(context).read(uri)
            assertTrue(track.local)
            assertEquals(uri.toString(), track.mediaUri)
            assertTrue(track.durationMs in 251_000..253_000)
            val store = LibraryStore(context)
            store.saveLocalTracks(listOf(track))
            store.saveFavorites(setOf(track.id))
            val restored = LibraryStore(context)
            assertEquals(track.mediaUri, restored.localTracks().single().mediaUri)
            assertTrue(track.id in restored.favorites)
        } finally {
            context.contentResolver.delete(uri, null, null)
            LibraryStore(context).saveLocalTracks(emptyList())
            LibraryStore(context).saveFavorites(emptySet())
        }
    }

    // 只在测试模拟器中创建自有媒体文档，模拟系统选择器返回的内容 URI。
    private fun createAudioDocument(context: Context, embeddedArtwork: Boolean = false): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Kimo-import-test-${System.nanoTime()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        val audio = context.assets.open("evening_wind.mp3").use { it.readBytes() }
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            output.write(if (embeddedArtwork) embedArtwork(context, audio) else audio)
        }
        return uri
    }

    // 测试将已有原创封面原样封装为 ID3 图片帧，不生成或修改图像内容。
    private fun embedArtwork(context: Context, audio: ByteArray): ByteArray {
        val picture = context.resources.openRawResource(R.drawable.forest_letter).use { it.readBytes() }
        val payload = byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + picture
        val frame = "APIC".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + byteArrayOf(0, 0) + payload
        val size = ByteArray(4) { index -> ((frame.size shr ((3 - index) * 7)) and 0x7f).toByte() }
        val header = byteArrayOf(73, 68, 51, 3, 0, 0) + size
        return header + frame + withoutId3(audio)
    }

    // 替换原有标签时保留全部音频帧，避免连续 ID3 标签影响解码器识别。
    private fun withoutId3(audio: ByteArray): ByteArray {
        if (audio.size < 10 || String(audio, 0, 3) != "ID3") return audio
        val size = (6..9).fold(0) { length, index -> (length shl 7) or (audio[index].toInt() and 0x7f) }
        val footer = if (audio[5].toInt() and 0x10 != 0) 10 else 0
        return audio.copyOfRange((10 + size + footer).coerceAtMost(audio.size), audio.size)
    }
}
