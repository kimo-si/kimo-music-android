package com.kimo.music

import com.kimo.music.data.Track
import com.kimo.music.data.AppearanceMode
import com.kimo.music.core.HotSearch
import com.kimo.music.core.KugouMapper
import com.kimo.music.ui.theme.usesDarkTheme
import com.kimo.music.data.AudioCredits
import com.kimo.music.data.FavoriteMark
import com.kimo.music.data.FavoriteOrdering
import com.kimo.music.data.FavoriteIndex
import com.kimo.music.ui.songFlightPoint
import androidx.compose.ui.geometry.Offset
import com.kimo.music.data.formatDuration
import com.kimo.music.data.matching
import org.junit.Assert.*
import org.junit.Test

// 单元测试使用独立构造的数据，不向正式应用预填歌曲。
private object DemoCatalog {
    val tracks = listOf(
        Track("first", "晚风经过", "林间回声", "DUSK NOTES", "", 252_000),
        Track("second", "山谷来信", "林间回声", "山野手记", "", 198_000),
        Track("third", "午后三点", "林间回声", "慢半拍", "", 216_000),
    )
}

class CatalogTest {
    // 同名热词可以补图，歌手明确时必须匹配歌手，并拒绝无关首条和不同版本。
    @Test fun hotArtworkRequiresAnExactSongLabel() {
        val song = DemoCatalog.tracks.first()
        val unrelated = song.copy(id = "unrelated", title = "晚风经过以后")
        val live = song.copy(id = "live", title = "晚风经过 (Live)")
        assertEquals(song, KugouMapper.hotSearchTrack(HotSearch(song.title), listOf(unrelated, song)))
        assertEquals(song, KugouMapper.hotSearchTrack(HotSearch("林间回声 - 晚风经过"), listOf(live, song)))
        assertEquals(song, KugouMapper.hotSearchTrack(HotSearch("晚风经过 林间回声"), listOf(song)))
        assertNull(KugouMapper.hotSearchTrack(HotSearch("其他歌手 晚风经过"), listOf(song)))
        assertNull(KugouMapper.hotSearchTrack(HotSearch(song.title), listOf(unrelated, live)))
        assertNull(KugouMapper.hotSearchTrack(HotSearch("  "), listOf(song)))
    }

    // 用户显式选择优先于设备设置，旧版本或损坏偏好仍保持原有浅色。
    @Test fun appearanceHonorsExplicitChoiceAndLegacyDefaults() {
        assertFalse(AppearanceMode.Light.usesDarkTheme(systemDark = true))
        assertTrue(AppearanceMode.Dark.usesDarkTheme(systemDark = false))
        assertTrue(AppearanceMode.System.usesDarkTheme(systemDark = true))
        assertFalse(AppearanceMode.System.usesDarkTheme(systemDark = false))
        assertEquals(AppearanceMode.Light, AppearanceMode.fromStoredValue(null))
        assertEquals(AppearanceMode.Light, AppearanceMode.fromStoredValue("invalid-mode"))
    }

    // 旧占位不参与展示和搜索；真实标签恰好同名时，核实后仍可正常显示。
    @Test fun legacyLocalCreditsRequireVerificationBeforeDisplay() {
        val legacy = DemoCatalog.tracks.first().copy(local = true, artist = "本地音乐", album = "本地音乐", metadataVerified = false)
        assertEquals("", legacy.displayArtist)
        assertEquals("", legacy.displayAlbum)
        assertTrue(listOf(legacy).matching("本地音乐").isEmpty())
        val verified = legacy.withVerifiedCredits(AudioCredits("本地音乐", ""))
        assertEquals("本地音乐", verified.displayArtist)
        assertEquals("", verified.displayAlbum)
        assertEquals(listOf(verified), listOf(verified).matching("本地音乐"))
    }

    // 收藏索引保留不同音质的同曲匹配，并排除空 hash 和无效歌曲编号。
    @Test fun favoriteIndexPreservesCloudSongAliases() {
        val original = DemoCatalog.tracks.first().copy(hash = "ABC123", mixSongId = 42)
        val index = FavoriteIndex(listOf(original))
        assertTrue(index.contains(original.copy(hash = "abc123", mixSongId = 0)))
        assertTrue(index.contains(original.copy(hash = "OTHER-HASH")))
        assertFalse(index.contains(original.copy(hash = "OTHER-HASH", mixSongId = 0)))
        assertFalse(index.contains(DemoCatalog.tracks.first()))
    }

    // 飞行动画与屏幕中的实际封面严格对齐，越界进度也不会飞离端点。
    @Test fun flyingNoteUsesExactArtworkCenters() {
        val start = Offset(40f, 200f)
        val end = Offset(45f, 800f)
        assertEquals(start, songFlightPoint(start, end, -1f, 88f, 48f))
        assertEquals(end, songFlightPoint(start, end, 2f, 88f, 48f))
        val middle = songFlightPoint(start, end, 0.5f, 88f, 48f)
        assertTrue(middle.x > start.x && middle.y in start.y..end.y)
    }

    // 跨分页的完整收藏整体倒序，最后一页的新歌也必须排在第一位且没有丢曲。
    @Test fun allFavoritePagesAreReversedTogether() {
        val tracks = (1..601).map { DemoCatalog.tracks.first().copy(id = "song-$it") }
        val ordered = FavoriteOrdering.newestFirst(tracks, emptyList(), emptyList())
        assertEquals(601, ordered.size)
        assertEquals(tracks.reversed(), ordered)
    }

    // 收藏时间优先于目录顺序，未知日期仍维持服务端倒序，不丢失本机收藏。
    @Test fun favoriteDatesSortCloudAndLocalWithoutDroppingUnknownDates() {
        val cloud = (1..3).map { DemoCatalog.tracks.first().copy(id = "cloud-$it") }
        val dated = cloud.first().copy(favoriteAddedAtMs = 1_700_000_000_000L)
        val local = DemoCatalog.tracks.last()
        val result = FavoriteOrdering.newestFirst(listOf(dated) + cloud.drop(1), listOf(local),
            listOf(FavoriteMark(local.id, 1_800_000_000_000L)))
        assertEquals(listOf(local.id, dated.id, "cloud-3", "cloud-2"), result.map { it.id })
    }

    // 时长解析前后的边界不应出现负值或错误分钟数。
    @Test fun durationHandlesUnknownAndLongTracks() {
        assertEquals("00:00", formatDuration(-1))
        assertEquals("04:12", formatDuration(252_000))
        assertEquals("61:01", formatDuration(3_661_000))
    }

    // 验证中文、英文、不存在的关键词和空搜索都能正确筛选可播放对象。
    @Test fun searchRespectsTitlesArtistsAlbumsAndWhitespace() {
        val tracks = DemoCatalog.tracks
        assertEquals(listOf(tracks.first()), tracks.matching(" 晚风 "))
        assertEquals(3, tracks.matching("林间回声").size)
        assertEquals(listOf(tracks.first()), tracks.matching("dusk"))
        assertTrue(tracks.matching("不存在的歌名").isEmpty())
        assertEquals(tracks, tracks.matching("  "))
    }
}
