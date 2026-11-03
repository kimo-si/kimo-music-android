package com.kimo.music.core

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ReleaseInfo(val version: String, val notes: String, val pageUrl: String, val apkUrl: String?)
data class UpdateState(val checking: Boolean = false, val checked: Boolean = false,
    val release: ReleaseInfo? = null, val error: String? = null)

object ReleaseChannel {
    const val REPOSITORY = "https://github.com/kimo-si/kimo-music-android"
    const val API = "https://api.github.com/repos/kimo-si/kimo-music-android/releases/latest"

    // 版本按数值逐段比较，避免将 0.10 误判为低于 0.9。
    fun newer(candidate: String, installed: String): Boolean {
        val next = numbers(candidate) ?: return false
        val current = numbers(installed) ?: return false
        return next.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }

    // 仅接受公开仓库自己的 HTTPS 页面和安装包地址。
    fun trusted(url: String, asset: Boolean): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            uri.path.startsWith(if (asset) "/kimo-si/kimo-music-android/releases/download/" else "/kimo-si/kimo-music-android/releases/tag/")
    }.getOrDefault(false)

    // 兼容常用的 v 前缀与三到四段数字，预发布版不冒充稳定更新。
    private fun numbers(value: String): List<Int>? {
        val clean = value.removePrefix("v")
        if (!Regex("\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?").matches(clean)) return null
        val values = clean.split('.').map { it.toIntOrNull() ?: return null }
        return values + List(4 - values.size) { 0 }
    }
}

class ReleaseUpdates(context: Context, private val scope: CoroutineScope) {
    val installedVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    var state by mutableStateOf(UpdateState()); private set
    private var job: Job? = null
    val available: Boolean get() = state.release?.let { ReleaseChannel.newer(it.version, installedVersion) } == true

    // 检查仅由用户触发，新的请求取消旧请求，界面不会被迟到响应覆盖。
    fun check() {
        job?.cancel(); state = state.copy(checking = true, error = null)
        job = scope.launch {
            try { state = UpdateState(checked = true, release = withContext(Dispatchers.IO) { fetch() }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(checking = false, checked = true, error = "无法检查更新，请检查网络后重试") }
        }
    }

    // 网络返回只用于解析版本信息，不下载或执行远程代码。
    private fun fetch(): ReleaseInfo? {
        val connection = URL(ReleaseChannel.API).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS; connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Kimo-Android/$installedVersion")
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            check(connection.responseCode == HttpURLConnection.HTTP_OK)
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val content = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(content.length + count <= MAX_RESPONSE_CHARS)
                    content.append(buffer, 0, count)
                }
                content.toString()
            }
            decode(JSONObject(text))
        } finally { connection.disconnect() }
    }

    // 根据设备 ABI 选择 APK，没有对应包时只提供发布页，不安装未知来源内容。
    private fun decode(data: JSONObject): ReleaseInfo {
        require(!data.optBoolean("draft") && !data.optBoolean("prerelease"))
        val page = data.getString("html_url"); require(ReleaseChannel.trusted(page, false))
        val assets = data.rows("assets").filter { it.text("name").endsWith(".apk") &&
            ReleaseChannel.trusted(it.text("browser_download_url"), true) }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in listOf("arm64-v8a", "x86_64") }.orEmpty()
        val asset = assets.firstOrNull { abi.isNotBlank() && it.text("name").contains(abi) }
            ?: assets.firstOrNull { it.text("name").contains("universal") }
        return ReleaseInfo(data.getString("tag_name").removePrefix("v"), data.optString("body").take(MAX_NOTES_CHARS),
            page, asset?.text("browser_download_url"))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_CHARS = 512_000
        const val MAX_NOTES_CHARS = 12_000
    }
}
