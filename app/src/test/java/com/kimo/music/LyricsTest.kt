package com.kimo.music

import com.kimo.music.core.KugouPlaybackApi
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    // 同一歌词的多个时间戳和正负偏移都必须保留并按时间排序。
    @Test fun multipleTimestampsAndOffsetArePreserved() {
        val result = KugouPlaybackApi.parseLrc("[offset:-100]\\r[00:02.50][00:01.20]同一句\\r[00:00.05]开头".replace("\\r", "\r"))
        assertEquals(listOf(0L, 1100L, 2400L), result.map { it.timeMs })
        assertEquals(listOf("开头", "同一句", "同一句"), result.map { it.text })
    }

    // 空歌词或仅包含元数据时，不能伪造可点击的歌词行。
    @Test fun metadataAndEmptyContentDoNotBecomeLyrics() {
        assertTrue(KugouPlaybackApi.parseLrc("[ar:歌手]\n[00:01.00]   ").isEmpty())
    }
}
