package com.kimo.music.core

import android.content.Context
import com.caoccao.javet.enums.V8AwaitMode
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8Host
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

// 单线程托管完整 Node 核心，所有 JavaScript 和事件循环操作始终留在同一工作线程。
class EmbeddedCore private constructor(context: Context) : CoreTransport {
    private val assets = context.assets
    private val filesDirectory = context.filesDir
    private val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "Kimo-Music-Core") }.asCoroutineDispatcher()
    private var runtime: NodeRuntime? = null

    // 请求数据仅通过 JSON 编码进入脚本，不拼接未转义的关键词或账号信息。
    override suspend fun execute(module: String, parameters: JSONObject, credentials: JSONObject): JSONObject = withContext(dispatcher) {
        val engine = ensureRuntime()
        val id = UUID.randomUUID().toString()
        engine.getExecutor("kimoBegin(${JSONObject.quote(id)},${JSONObject.quote(module)},$parameters,$credentials)").executeVoid()
        try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { awaitResponse(engine, id) }
                ?: throw MusicApiException(SERVER_ERROR_MESSAGE, retryable = true)
        } finally {
            engine.getExecutor("kimoCancel(${JSONObject.quote(id)})").executeVoid()
        }
    }

    // 每个进程只创建一个核心实例，不随着页面或 Activity 重建而反复加载原生引擎。
    private fun ensureRuntime(): NodeRuntime {
        runtime?.let { return it }
        val directory = unpackCore()
        val engine = V8Host.getNodeInstance().createV8Runtime<NodeRuntime>()
        engine.getExecutor("require(${JSONObject.quote(File(directory, "kugou_bridge.cjs").absolutePath)})").executeVoid()
        runtime = engine
        return engine
    }

    // 非阻塞地推进 Node 事件循环，等待网络时允许同一线程处理其他独立请求。
    private suspend fun awaitResponse(engine: NodeRuntime, id: String): JSONObject {
        while (true) {
            engine.await(V8AwaitMode.RunNoWait)
            val response = engine.getExecutor("kimoTake(${JSONObject.quote(id)})").executeString()
            if (!response.isNullOrEmpty()) return JSONObject(response)
            delay(EVENT_INTERVAL_MS)
        }
    }

    // 资源按内容摘要安装到私有目录，更新应用时不会覆盖独立保存的账号与设备数据。
    private fun unpackCore(): File {
        val digest = assets.open("kugou_core.sha256").bufferedReader().use { it.readText().trim() }
        val directory = File(filesDirectory, "music-core/$digest")
        if (File(directory, ".ready").exists()) return directory
        directory.mkdirs()
        assets.open("kugou_core.zip").use { source ->
            ZipInputStream(source).use { zip -> extract(zip, directory) }
        }
        File(directory, ".ready").writeText(digest)
        return directory
    }

    // 校验每个压缩项的目标路径，禁止核心包解压越过应用私有目录。
    private fun extract(zip: ZipInputStream, directory: File) {
        var entry = zip.nextEntry
        while (entry != null) {
            val target = File(directory, entry.name)
            require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            if (!entry.isDirectory) {
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val EVENT_INTERVAL_MS = 15L
        @Volatile private var instance: EmbeddedCore? = null

        // 共享应用级运行时，账户模型、曲库与播放服务使用同一个接口实现。
        fun get(context: Context): EmbeddedCore = instance ?: synchronized(this) {
            instance ?: EmbeddedCore(context.applicationContext).also { instance = it }
        }
    }
}
