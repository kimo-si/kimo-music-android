package com.kimo.music

import com.kimo.music.core.ReleaseChannel
import com.kimo.music.data.LocalGrouping
import com.kimo.music.data.LocalLibraryIndex
import com.kimo.music.data.Track
import org.junit.Assert.*
import org.junit.Test

class ExpandedModelsTest {
    // 版本比较覆盖两位数、可选前缀、四段版本、预发布与非法输入。
    @Test fun releasesCompareNumericallyAndRejectUntrustedVersions() {
        assertTrue(ReleaseChannel.newer("v0.10.0", "0.9.9"))
        assertTrue(ReleaseChannel.newer("0.2.0.1", "0.2.0"))
        assertFalse(ReleaseChannel.newer("0.2.0", "0.2.0"))
        assertFalse(ReleaseChannel.newer("0.1.9", "0.2.0"))
        assertFalse(ReleaseChannel.newer("0.3.0-beta", "0.2.0"))
        assertFalse(ReleaseChannel.newer("999999999999999.2.3", "0.2.0"))
    }

    // 发布信息只能打开本仓库的 HTTPS 页面或安装包，不能跳向相似域名。
    @Test fun updateUrlsStayInsideTheConfiguredReleaseChannel() {
        assertTrue(ReleaseChannel.trusted("https://github.com/kimo-si/kimo-music-android/releases/tag/v0.2.0", false))
        assertTrue(ReleaseChannel.trusted("https://github.com/kimo-si/kimo-music-android/releases/download/v0.2.0/app.apk", true))
        assertFalse(ReleaseChannel.trusted("http://github.com/kimo-si/kimo-music-android/releases/tag/v0.2.0", false))
        assertFalse(ReleaseChannel.trusted("https://github.com.evil.test/kimo-si/kimo-music-android/releases/download/file.apk", true))
        assertFalse(ReleaseChannel.trusted("https://github.com/another/repo/releases/tag/v0.2.0", false))
    }

    // 本地搜索匹配文件名与目录，重复曲目只出现一次。
    @Test fun localSearchAndGroupingKeepRealMetadata() {
        val first = Track("a", "海边", "甲", "旅程", "file://a", 1000, fileName = "beach.mp3", local = true, folderName = "Music/旅行")
        val second = first.copy(id = "b", title = "夜色", artist = "", album = "", fileName = "night.mp3", folderName = "")
        assertEquals(listOf(first), LocalLibraryIndex.filter(listOf(first, first, second), "beach"))
        assertEquals(listOf(first), LocalLibraryIndex.filter(listOf(first, second), "旅行"))
        assertEquals(setOf("甲", "未知歌手"), LocalLibraryIndex.groups(listOf(first, second), LocalGrouping.Artists).map { it.title }.toSet())
        assertEquals(setOf("Music/旅行", "其他位置"), LocalLibraryIndex.groups(listOf(first, second), LocalGrouping.Folders).map { it.title }.toSet())
    }
}
