package com.kimo.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.AudioQuality
import com.kimo.music.core.StreamAddress
import com.kimo.music.data.DemoCatalog
import com.kimo.music.downloads.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadPipelineTest {
    @get:Rule val library = LocalLibraryFixture()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DownloadRepository.get(context)
    private val source: ByteArray get() = File(android.net.Uri.parse(DemoCatalog.tracks.first().mediaUri).path!!).readBytes()

    // 完整音频落盘后才出现在离线列表，播放加载器可直接取得相同的缓存文件。
    @Test fun completeAudioIsValidatedAndAvailableOffline() = runBlocking {
        withTask("complete") { item ->
            val bytes = source
            transfer(fakeTransport(bytes)).run(item)
            val completed = repository.item(item.id)!!
            assertEquals(DownloadStatus.Complete, completed.status)
            assertEquals(digest(bytes), digest(repository.cached(item.track, item.quality)!!.readBytes()))
            assertTrue(repository.localTrack(completed).local)
        }
    }

    // 真正启动下载服务收尾完整片段，再通过原在线 URI 播放，证明缓存接入了 Media3 加载器。
    @Test fun resumedServiceFinishesAndOriginalOnlineQueuePlaysFromCache() = runBlocking {
        withTask("service-cache") { item ->
            val bytes = source
            repository.partialFile(item.id).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            repository.metadata(item.id, bytes.size.toLong(), bytes.size.toLong(), "fixture-v1")
            repository.status(item.id, DownloadStatus.Paused)
            val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            lateinit var model: com.kimo.music.playback.MusicViewModel
            try {
                scenario.onActivity {
                    model = androidx.lifecycle.ViewModelProvider(it)[com.kimo.music.playback.MusicViewModel::class.java]
                    AudioDownloadService.command(context, item.id, AudioDownloadService.RESUME)
                }
                repeat(300) { if (repository.item(item.id)?.status != DownloadStatus.Complete) delay(50) }
                assertEquals(DownloadStatus.Complete, repository.item(item.id)?.status)
                instrumentation.runOnMainSync { model.play(item.track, listOf(item.track)) }
                var playing = false
                repeat(300) {
                    instrumentation.runOnMainSync { playing = model.playback.playing }
                    if (!playing) delay(50)
                }
                assertTrue("无真实曲库 hash 的测试曲目只能从已下载缓存播放", playing)
                scenario.onActivity { model.downloadsRequested = true }
                delay(300)
                val directory = File(context.cacheDir, "kimo-expanded-previews").apply { mkdirs() }
                File(directory, "download-complete.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                instrumentation.runOnMainSync { model.clearQueue() }
            } finally { scenario.close() }
        }
    }

    // 只有实体与范围均一致才追加片段，结果必须逐字节等于原始音频。
    @Test fun verifiedEntityResumesWithoutDuplicatingBytes() = runBlocking {
        withTask("resume") { item ->
            val bytes = source; val offset = 90_000
            repository.partialFile(item.id).apply { parentFile!!.mkdirs(); writeBytes(bytes.copyOf(offset)) }
            repository.metadata(item.id, offset.toLong(), bytes.size.toLong(), "fixture-v1")
            val requests = mutableListOf<DownloadRequest>()
            transfer(fakeTransport(bytes, requests)).run(repository.item(item.id)!!)
            assertEquals(offset.toLong(), requests.first().offset)
            assertEquals(digest(bytes), digest(repository.audioFile(item.id).readBytes()))
        }
    }

    // 服务端实体发生变化时从头重取，不能将旧片段拼接为损坏文件。
    @Test fun mismatchedEntityRestartsFromZero() = runBlocking {
        withTask("changed") { item ->
            val bytes = source
            repository.partialFile(item.id).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(30_000) { 7 }) }
            repository.metadata(item.id, 30_000, bytes.size.toLong(), "old-entity")
            val requests = mutableListOf<DownloadRequest>()
            transfer(fakeTransport(bytes, requests)).run(repository.item(item.id)!!)
            assertEquals(listOf(30_000L, 0L), requests.map { it.offset })
            assertEquals(digest(bytes), digest(repository.audioFile(item.id).readBytes()))
        }
    }

    // 弱实体标识不保证字节相同，必须重新取得完整音频而不是追加旧片段。
    @Test fun weakEntityNeverAppendsUnverifiedBytes() = runBlocking {
        withTask("weak-entity") { item ->
            val bytes = source
            repository.partialFile(item.id).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(40_000) { 5 }) }
            repository.metadata(item.id, 40_000, bytes.size.toLong(), "W/\"old\"")
            val requests = mutableListOf<DownloadRequest>()
            transfer(fakeTransport(bytes, requests)).run(repository.item(item.id)!!)
            assertEquals(0L, requests.first().offset)
            assertEquals(digest(bytes), digest(repository.audioFile(item.id).readBytes()))
        }
    }

    // 暂停主动关闭阻塞读取并保留片段，取消任务不会发布为已完成文件。
    @Test fun cancellationClosesTheConnectionAndPreservesPartialAudio() = runBlocking {
        withTask("pause") { item ->
            val entered = CountDownLatch(1); val closed = CountDownLatch(1)
            val connection = object : FixtureConnection(source, 0, "fixture-v1") {
                override val input = object : InputStream() {
                    override fun read(): Int { entered.countDown(); closed.await(5, TimeUnit.SECONDS); return -1 }
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int = read()
                }
                override fun close() { closed.countDown() }
            }
            val transfer = transfer(DownloadTransport { connection })
            val job = launch(Dispatchers.IO) { transfer.run(item) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel(); transfer.interrupt(item.id); job.join()
            assertEquals(0L, closed.count)
            assertFalse(repository.audioFile(item.id).exists())
            assertNotEquals(DownloadStatus.Complete, repository.item(item.id)?.status)
        }
    }

    // 删除只清理该任务自己的文件，其他任务和原始音频均保留。
    @Test fun removingDownloadLeavesOriginalAudioUntouched() = runBlocking {
        val original = File(android.net.Uri.parse(DemoCatalog.tracks.first().mediaUri).path!!)
        val item = repository.enqueue(song("remove"), AudioQuality.Standard)
        repository.partialFile(item.id).apply { parentFile!!.mkdirs(); writeText("partial") }
        repository.remove(item.id)
        assertNull(repository.item(item.id)); assertFalse(repository.partialFile(item.id).exists()); assertTrue(original.exists())
    }

    // 为每项检查创建独立记录，并在结束后仅清理测试自己创建的任务。
    private suspend fun withTask(name: String, block: suspend (DownloadItem) -> Unit) {
        val item = repository.enqueue(song(name), AudioQuality.Standard)
        try { repository.status(item.id, DownloadStatus.Downloading); block(repository.item(item.id)!!) }
        finally { repository.remove(item.id) }
    }

    // 使用自有合成音频作为协议资源，不调用真实账号或会员取流。
    private fun song(name: String) = DemoCatalog.tracks.first().copy(id = "download-test-$name", hash = "download-test-$name", local = false, mediaUri = "")

    // 传输层替身仅替换网络边界，文件校验、状态和续传仍执行生产代码。
    private fun transfer(network: DownloadTransport) = DownloadTransfer(context, repository,
        DownloadAddressResolver { _, _ -> StreamAddress("https://fixture.invalid/audio", 128000) }, network)

    // 模拟标准响应与范围响应，记录每次真正发出的偏移参数。
    private fun fakeTransport(bytes: ByteArray, requests: MutableList<DownloadRequest> = mutableListOf()) = DownloadTransport { request ->
        requests.add(request); FixtureConnection(bytes, request.offset.toInt(), "fixture-v1")
    }

    // 摘要验证最终文件内容，能够识别重复追加或错误片段拼接。
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private open class FixtureConnection(private val bytes: ByteArray, private val offset: Int, override val etag: String) : DownloadConnection {
        override val status = if (offset == 0) 200 else 206
        override val contentRange = "bytes $offset-${bytes.size - 1}/${bytes.size}"
        override val contentType = "audio/mpeg"
        override val length = (bytes.size - offset).toLong()
        override val input: InputStream get() = ByteArrayInputStream(bytes, offset, bytes.size - offset)
        // 内存响应没有网络句柄，生产连接的关闭由另一个取消用例验证。
        override fun close() { }
    }
}
