package com.kimo.music

import com.kimo.music.core.*
import com.kimo.music.data.Track
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackMembershipTest {
    // 同名录音按 hash 和专辑独立核实，返回顺序变化也不能互相借用权益。
    @Test fun membershipUsesEachResourcesStandardQualityAndIdentity() = runBlocking {
        val vip = song(1)
        val free = song(2)
        val resources = listOf(resource(free, 0, 0), resource(vip, 3, 10))
        assertEquals(MembershipStatus.Vip, MembershipMapper.resolve(vip, resources).status)
        assertEquals(MembershipStatus.NotRequired, MembershipMapper.resolve(free, resources).status)
        assertEquals(MembershipStatus.Unknown, MembershipMapper.resolve(vip.copy(albumId = 99), resources).status)
        assertEquals(MembershipStatus.Unknown, MembershipMapper.resolve(song(3), resources).status)
    }

    // 高音质受限、单独购买、无法播放和缺字段都不是会员歌曲的充分依据。
    @Test fun unrelatedRestrictionsAndMissingFieldsNeverBecomeVip() {
        val track = song(1)
        val highOnly = resource(track, 0, 0).apply {
            getJSONArray("relate_goods").put(JSONObject().put("quality", "320").put("level", 4).put("pay_type", 3).put("privilege", 10))
        }
        assertEquals(MembershipStatus.NotRequired, MembershipMapper.resolve(track, listOf(highOnly)).status)
        assertEquals(MembershipStatus.Unknown, MembershipMapper.resolve(track, listOf(resource(track, 1, 10))).status)
        val missing = resource(track, 3, 10).apply { getJSONArray("relate_goods").getJSONObject(0).remove("privilege") }
        assertEquals(MembershipStatus.Unknown, MembershipMapper.resolve(track, listOf(missing)).status)
    }

    // API 只信真实资源数组，忽略上游按请求位置生成但无法核对身份的回显。
    @Test fun apiRejectsUnmatchedEchoedVipResults() = runBlocking {
        val track = song(1)
        val api = KugouApi(CoreTransport { module, parameters, _ ->
            assertEquals("privilege_lite", module)
            assertEquals("128", parameters.getString("qualities"))
            JSONObject().put("status", 1).put("data", JSONArray().put(resource(song(2), 3, 10)))
                .put("quality_vip", JSONArray().put(JSONObject().put("hash", track.hash).put("free_song", false)))
        }) { null }
        assertEquals(MembershipStatus.Unknown, api.memberships(listOf(track)).single().status)
    }

    // 同帧的可见歌曲合并为批次，重复出现的标题复用已核实缓存。
    @Test fun visibleSongsAreBatchedDeduplicatedAndRecheckedAfterExpiry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var now = 100L
        val requests = mutableListOf<Int>()
        val api = fakeApi { parameters ->
            val hashes = parameters.getString("hash").split(",")
            requests.add(hashes.size)
            hashes.map { hash -> resource(song(hash.toInt(16)), 0, 0) }
        }
        val memberships = TrackMemberships(api, scope) { now }
        val tracks = (1..37).map(::song)
        try {
            withContext(Dispatchers.Main) { tracks.forEach { memberships.request(it); memberships.request(it) } }
            await { tracks.all { memberships.status(it) == MembershipStatus.NotRequired } }
            assertEquals(listOf(20, 17), requests)
            withContext(Dispatchers.Main) { tracks.forEach(memberships::request) }
            assertEquals(2, requests.size)
            withContext(Dispatchers.Main) { now += 300_001; memberships.request(tracks.first()) }
            await { requests.size == 3 && memberships.status(tracks.first()) == MembershipStatus.NotRequired }
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    // 查询失败和本地音频都不显示会员标识，网络恢复后允许短时缓存过期再核实。
    @Test fun failedLookupStaysUnknownAndLocalMusicIsNeverQueried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var now = 0L
        var calls = 0
        val track = song(1)
        val api = fakeApi {
            calls++
            if (calls == 1) throw MusicApiException("模拟网络错误")
            listOf(resource(track, 3, 10))
        }
        val memberships = TrackMemberships(api, scope) { now }
        try {
            withContext(Dispatchers.Main) { memberships.request(track.copy(local = true)) }
            assertEquals(0, calls)
            withContext(Dispatchers.Main) { memberships.request(track) }
            await { calls == 1 }
            withContext(Dispatchers.Main) { assertEquals(MembershipStatus.Unknown, memberships.status(track)); now += 30_001; memberships.request(track) }
            await { memberships.status(track) == MembershipStatus.Vip }
            assertEquals(2, calls)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    // 构造与真实权益接口相同的资源结构，不访问账号或云端写入。
    private fun fakeApi(rows: (JSONObject) -> List<JSONObject>) = KugouApi(CoreTransport { _, parameters, _ ->
        JSONObject().put("status", 1).put("data", JSONArray(rows(parameters)))
    }) { null }

    // 每个测试录音保留独立音频与专辑身份，名称刻意相同以检查误匹配。
    private fun song(index: Int): Track = Track("song-$index", "同名歌曲", "测试歌手", "专辑", "", 1_000,
        hash = "%032x".format(index), albumId = index.toLong())

    // 只有标准音质的明确权益参与识别，status 不作为会员判断条件。
    private fun resource(track: Track, payType: Int, privilege: Int): JSONObject = JSONObject()
        .put("hash", track.hash).put("album_id", track.albumId).put("relate_goods", JSONArray().put(JSONObject()
            .put("quality", "128").put("level", 2).put("pay_type", payType).put("privilege", privilege).put("status", 0)))

    // 状态检查在主线程进行，超时暴露查询或缓存处理错误。
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(10)
    }
}
