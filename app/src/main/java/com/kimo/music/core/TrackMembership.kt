package com.kimo.music.core

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import com.kimo.music.data.Track
import com.kimo.music.data.online
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal const val MEMBERSHIP_BATCH_SIZE = 20
internal const val MEMBERSHIP_STANDARD_QUALITY = "128"
private const val VIP_PAY_TYPE = 3L
private const val VIP_PRIVILEGE = 10L
private const val FREE_PAY_TYPE = 0L
private const val FREE_PRIVILEGE = 0L
private const val STANDARD_OGG_LEVEL = 1L
private const val STANDARD_MP3_LEVEL = 2L
private const val MEMBERSHIP_DEBOUNCE_MS = 48L
private const val MEMBERSHIP_CACHE_MS = 300_000L
private const val MEMBERSHIP_RETRY_MS = 30_000L
private const val MEMBERSHIP_CACHE_LIMIT = 512

enum class MembershipStatus { Unknown, NotRequired, Vip }
data class MembershipKey(val hash: String, val albumId: Long) {
    companion object {
        // 同名曲、翻唱与不同专辑分别查询，不能互相借用会员状态。
        fun of(track: Track): MembershipKey = MembershipKey(track.hash.lowercase(), track.albumId)
    }
}
data class TrackMembership(val key: MembershipKey, val status: MembershipStatus)
private data class CachedMembership(val status: MembershipStatus, val expiresAtMs: Long)

object MembershipMapper {
    // 必须匹配实际返回资源的音频与专辑，不使用上游按位置回显的推测结果。
    fun resolve(track: Track, resources: List<JSONObject>): TrackMembership {
        val key = MembershipKey.of(track)
        val resource = resources.firstOrNull {
            it.text("hash").equals(track.hash, true) && (track.albumId <= 0 || it.number("album_id") == track.albumId)
        }
        val standard = resource?.rows("relate_goods")?.filter { it.text("quality") == MEMBERSHIP_STANDARD_QUALITY }.orEmpty()
        val selected = standard.firstOrNull { it.number("level") == STANDARD_OGG_LEVEL }
            ?: standard.firstOrNull { it.number("level") == STANDARD_MP3_LEVEL }
        return TrackMembership(key, classify(selected))
    }

    // 只接受标准音质明确的会员组合，购买、版权受限和缺失权益均不标成 VIP。
    private fun classify(standard: JSONObject?): MembershipStatus {
        val payType = standard?.optionalNumber("pay_type")
        val privilege = standard?.optionalNumber("privilege")
        return when {
            payType == VIP_PAY_TYPE && privilege == VIP_PRIVILEGE -> MembershipStatus.Vip
            payType == FREE_PAY_TYPE && privilege == FREE_PRIVILEGE -> MembershipStatus.NotRequired
            else -> MembershipStatus.Unknown
        }
    }
}

// 可见歌曲合并查询权益；结果只驻留内存，旧缓存中的付费提示不作为会员依据。
class TrackMemberships(private val api: KugouApi, private val scope: CoroutineScope,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime) {
    private val cache = mutableStateMapOf<MembershipKey, CachedMembership>()
    private val pending = linkedMapOf<MembershipKey, Track>()
    private val inFlight = mutableSetOf<MembershipKey>()
    private var worker: Job? = null

    // 界面只读取已核实且未过期的结果，未知或查询失败时隐藏标识。
    fun status(track: Track): MembershipStatus {
        if (!track.online || track.cloudDrive) return MembershipStatus.Unknown
        return cache[MembershipKey.of(track)]?.takeIf { it.expiresAtMs > nowMillis() }?.status ?: MembershipStatus.Unknown
    }

    // 每个资源只排队一次，缓存有效期间滚动、切换页面与播放条复用同一结果。
    fun request(track: Track) {
        if (!track.online || track.cloudDrive) return
        val key = MembershipKey.of(track)
        if (cache[key]?.expiresAtMs?.let { it > nowMillis() } == true || key in pending || key in inFlight) return
        pending[key] = track
        if (worker?.isActive == true) return
        // 短暂合并同一帧可见的歌曲，避免为每一行单独发出网络请求。
        worker = scope.launch { drain() }
    }

    // 单个批处理顺序访问核心，列表保持可操作，查询不会阻塞播放或收藏。
    private suspend fun drain() {
        delay(MEMBERSHIP_DEBOUNCE_MS)
        while (pending.isNotEmpty()) {
            val batch = pending.values.take(MEMBERSHIP_BATCH_SIZE)
            val keys = batch.map(MembershipKey::of)
            keys.forEach { pending.remove(it); inFlight.add(it) }
            try { remember(api.memberships(batch)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { remember(keys.map { TrackMembership(it, MembershipStatus.Unknown) }) }
            finally { inFlight.removeAll(keys.toSet()) }
        }
    }

    // 成功结果短时缓存，未知结果更早重试，并限制长期浏览后的内存占用。
    private fun remember(results: List<TrackMembership>) {
        val now = nowMillis()
        results.forEach { result ->
            val lifetime = if (result.status == MembershipStatus.Unknown) MEMBERSHIP_RETRY_MS else MEMBERSHIP_CACHE_MS
            cache[result.key] = CachedMembership(result.status, now + lifetime)
        }
        cache.keys.take((cache.size - MEMBERSHIP_CACHE_LIMIT).coerceAtLeast(0)).forEach(cache::remove)
    }
}
