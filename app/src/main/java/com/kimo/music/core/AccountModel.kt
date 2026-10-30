package com.kimo.music.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.json.JSONObject

enum class QrPhase(val title: String) {
    Idle("使用酷狗 App 扫码登录"), Loading("正在生成二维码"), Waiting("请使用酷狗 App 扫码"),
    Scanned("已扫码，请在酷狗 App 确认"), Failed("二维码加载失败")
}
data class AccountState(val profile: AccountProfile? = null, val restoring: Boolean = true,
    val verified: Boolean = false, val error: String? = null, val qrBase64: String? = null,
    val qrPhase: QrPhase = QrPhase.Idle, val sending: Boolean = false, val cooldown: Int = 0,
    val submitting: Boolean = false)

class AccountModel(context: Context, private val scope: CoroutineScope) {
    private val vault = SessionVault(context)
    private val core = EmbeddedCore.get(context)
    private val storageDispatcher = Dispatchers.IO.limitedParallelism(1)
    var credentials: AuthCredentials? = null; private set
    val api = KugouApi(core) { credentials }
    var state by mutableStateOf(AccountState()); private set
    var onSessionChanged: () -> Unit = {}
    var onRestored: () -> Unit = {}
    private var qrJob: Job? = null
    private var countdownJob: Job? = null
    private var generation = 0
    private var sessionGeneration = 0

    init { restore() }

    // 从密钥库恢复后主动校验服务端会话，失败原因可见且不会冒充同步成功。
    private fun restore() {
        val current = sessionGeneration
        scope.launch {
            val saved = withContext(storageDispatcher) { vault.load() }
            if (current != sessionGeneration) return@launch
            credentials = saved?.credentials
            state = state.copy(profile = saved?.profile, restoring = false)
            if (saved != null) { onRestored(); verify() }
        }
    }

    // 登录有效性使用真实的账号歌单接口验证。
    suspend fun verify() {
        val current = sessionGeneration
        try {
            // 远程验证完成后先检查账号代次，不能恢复已退出的旧会话。
            api.request("user_playlist", JSONObject().put("page", 1).put("pagesize", 1))
            if (current != sessionGeneration) return
            state = state.copy(verified = true, error = null)
            onSessionChanged()
            refreshProfile(current)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (current == sessionGeneration) state = state.copy(verified = false, error = error.message ?: "无法验证登录状态")
        }
    }

    // 用户主动打开登录页时启动轮询；刷新二维码会取消上一轮任务。
    fun startQr() {
        qrJob?.cancel()
        val current = ++generation
        qrJob = scope.launch {
            while (isActive && current == generation) {
                try {
                    val key = issueQr()
                    if (pollQr(key, current)) return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    state = state.copy(qrPhase = QrPhase.Failed, error = error.message ?: "二维码生成失败")
                    return@launch
                }
            }
        }
    }

    // 取 key 后调用 create 获取真正被酷狗 App 识别的二维码内容。
    private suspend fun issueQr(): String {
        state = state.copy(qrPhase = QrPhase.Loading, qrBase64 = null, error = null)
        val key = data(api.request("login_qr_key")).text("qrcode")
        require(key.isNotBlank()) { "二维码接口未返回有效 key" }
        val qr = data(api.request("login_qr_create", JSONObject().put("key", key).put("qrimg", true)))
        state = state.copy(qrBase64 = qr.text("base64"), qrPhase = QrPhase.Waiting)
        return key
    }

    // 110 秒自动换码；离开登录页或切换方式会取消轮询。
    private suspend fun pollQr(key: String, current: Int): Boolean {
        val started = android.os.SystemClock.elapsedRealtime()
        while (current == generation && android.os.SystemClock.elapsedRealtime() - started < QR_LIFETIME_MS) {
            delay(QR_POLL_MS)
            val response = try { data(api.request("login_qr_check", JSONObject().put("key", key).put("timestamp", System.currentTimeMillis()))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
            when (response.number("status")) {
                0L -> return false
                2L -> state = state.copy(qrPhase = QrPhase.Scanned)
                4L -> { authorize(response); return true }
                else -> state = state.copy(qrPhase = QrPhase.Waiting)
            }
        }
        return false
    }

    // 仅在用户点击获取验证码后发送，不自动向手机发起请求。
    fun sendCode(phone: String) {
        if (!validPhone(phone)) { state = state.copy(error = "请输入 11 位手机号"); return }
        if (state.sending || state.cooldown > 0) return
        scope.launch {
            state = state.copy(sending = true, error = null)
            try {
                api.request("captcha_sent", JSONObject().put("mobile", phone))
                startCountdown()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state = state.copy(error = error.message ?: "验证码发送失败") }
            finally { state = state.copy(sending = false) }
        }
    }

    // 验证码由用户输入，成功后保留 token、userid、t1 等完整鉴权标量。
    fun loginWithCode(phone: String, code: String) {
        if (!validPhone(phone) || !code.matches(Regex("\\d{4,8}"))) {
            state = state.copy(error = "请输入有效手机号和验证码"); return
        }
        if (state.submitting) return
        scope.launch {
            state = state.copy(submitting = true, error = null)
            try { authorize(data(api.request("login_cellphone", JSONObject().put("mobile", phone).put("code", code)))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state = state.copy(error = error.message ?: "登录失败") }
            finally { state = state.copy(submitting = false) }
        }
    }

    // 完成登录必须同时保存完整会话并通过在线校验。
    private suspend fun authorize(value: JSONObject) {
        val current = ++sessionGeneration
        val auth = AuthCredentials(value.text("token"), value.text("userid"), value.text("t1"),
            value.text("vip_token"), value.text("vip_type"), value.text("dfid"))
        require(auth.token.isNotBlank() && auth.userId.isNotBlank()) { "登录返回信息不完整，请重试" }
        val profile = AccountProfile(auth.userId, value.text("nickname", "username", "nick_name").ifBlank { "酷狗用户" },
            KugouMapper.sizedImage(value.text("pic", "avatar", "user_pic")), value.number("vip_type", "vipType") > 0)
        // 会话写入与注销清理使用串行工作线程，防止旧保存覆盖退出操作。
        withContext(storageDispatcher) { vault.save(AccountSession(auth, profile)) }
        if (current != sessionGeneration) return
        credentials = auth
        state = state.copy(profile = profile, error = null)
        verify()
    }

    // 账号资料刷新失败时保留登录返回的资料，不能影响已验证会话。
    private suspend fun refreshProfile(currentGeneration: Int) {
        val current = state.profile ?: return
        val details = try { data(api.request("user_detail", JSONObject().put("userid", current.userId))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return }
        if (currentGeneration != sessionGeneration) return
        val profile = current.copy(nickname = details.text("nickname", "username").ifBlank { current.nickname },
            avatar = KugouMapper.sizedImage(details.text("pic", "avatar", "user_pic")) ?: current.avatar,
            vip = details.number("vip_type", "vipType") > 0)
        state = state.copy(profile = profile)
        credentials?.let { withContext(storageDispatcher) { vault.save(AccountSession(it, profile)) } }
    }

    // 关闭弹层就结束二维码请求，后台不会继续轮询登录状态。
    fun stopQr() { generation++; qrJob?.cancel(); qrJob = null }

    // 注销清除凭据并通知曲库丢弃账号数据，不影响独立本地文件。
    fun logout() {
        sessionGeneration++
        stopQr()
        countdownJob?.cancel()
        credentials = null
        state = AccountState(restoring = false)
        scope.launch { withContext(storageDispatcher) { vault.clear() } }
        onSessionChanged()
    }

    // 统一展示账号失效，保留重登入口，不静默切换到其他账号或曲库。
    fun sessionFailed(message: String) { state = state.copy(verified = false, error = message) }

    // 冷却计时只影响再次发送验证码，不保留验证码明文。
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (remaining in SMS_COOLDOWN downTo 1) { state = state.copy(cooldown = remaining); delay(1000) }
            state = state.copy(cooldown = 0)
        }
    }

    // 校验国内手机号长度，协议字段保持用户实际输入。
    private fun validPhone(phone: String): Boolean = phone.matches(Regex("1\\d{10}"))

    // 登录接口有 data 包装和根级两种响应，统一在边界解包。
    private fun data(response: JSONObject): JSONObject = response.optJSONObject("data") ?: response

    private companion object {
        const val QR_LIFETIME_MS = 110_000L
        const val QR_POLL_MS = 1500L
        const val SMS_COOLDOWN = 60
    }
}
