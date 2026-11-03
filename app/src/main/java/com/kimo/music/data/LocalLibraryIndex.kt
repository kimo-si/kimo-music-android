package com.kimo.music.data

import java.text.Collator
import java.util.Locale

enum class LocalGrouping(val title: String) { Songs("歌曲"), Artists("歌手"), Albums("专辑"), Folders("文件夹") }
data class LocalMusicGroup(val title: String, val tracks: List<Track>)

object LocalLibraryIndex {
    // 搜索同时匹配真实标签、文件名及可确定的目录名称。
    fun filter(tracks: List<Track>, query: String): List<Track> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return tracks.distinctBy(Track::id)
        return tracks.distinctBy(Track::id).filter {
            "${it.title} ${it.displayArtist} ${it.displayAlbum} ${it.fileName} ${it.folderName}".contains(keyword, true)
        }
    }

    // 分组与排序使用独立强类型对象，未知标签不冒充固定歌手或专辑。
    fun groups(tracks: List<Track>, grouping: LocalGrouping): List<LocalMusicGroup> {
        val collator = Collator.getInstance(Locale.CHINA)
        val sorted = tracks.sortedWith { first, second -> collator.compare(first.title, second.title) }
        if (grouping == LocalGrouping.Songs) return listOf(LocalMusicGroup("全部歌曲", sorted))
        val groups = sorted.groupBy { track -> when (grouping) {
            LocalGrouping.Artists -> track.displayArtist.ifBlank { "未知歌手" }
            LocalGrouping.Albums -> track.displayAlbum.ifBlank { "未知专辑" }
            LocalGrouping.Folders -> track.folderName.ifBlank { "其他位置" }
            LocalGrouping.Songs -> "全部歌曲"
        } }
        return groups.map { LocalMusicGroup(it.key, it.value) }.sortedWith { first, second -> collator.compare(first.title, second.title) }
    }
}
