package com.kimo.music.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*

data class FeedPage<T>(val items: List<T>, val nextPage: Int? = null)
data class FeedState<T>(val items: List<T> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val nextPage: Int? = 1)

// 独立栏目分别管理请求与错误，分页失败保留已加载数据。
class PagedFeed<T>(private val scope: CoroutineScope, private val keyOf: (T) -> Any? = { it },
                   private val loader: suspend (PageRequest) -> FeedPage<T>) {
    var state by mutableStateOf(FeedState<T>()); private set
    private var job: Job? = null
    private var generation = 0

    // 刷新先取消旧请求，加载更多只追加当前页，不清空原有曲库。
    fun load(refresh: Boolean = false) {
        if (state.loading && !refresh) return
        val page = if (refresh) 1 else state.nextPage ?: return
        if (refresh) job?.cancel()
        val current = ++generation
        job = scope.launch {
            state = state.copy(loading = true, error = null)
            try {
                val result = loader(PageRequest(page))
                if (current == generation) state = FeedState(
                    (if (page == 1) result.items else state.items + result.items).distinctBy(keyOf), false, null, result.nextPage)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current == generation) state = state.copy(loading = false, error = error.message ?: "内容加载失败，请重试")
            } finally {
                if (current == generation) state = state.copy(loading = false)
            }
        }
    }

    // 首次可见时才加载栏目，返回页面复用已有结果。
    fun ensureLoaded() { if (state.items.isEmpty() && state.nextPage == 1 && !state.loading) load() }

    // 账号变化时取消其旧请求并清除账号专属数据。
    fun reset() { generation++; job?.cancel(); state = FeedState() }

    // 同步结果由账号统一编排后一次性替换，防止局部写入。
    fun replace(items: List<T>) { generation++; job?.cancel(); state = FeedState(items, nextPage = null) }
}
