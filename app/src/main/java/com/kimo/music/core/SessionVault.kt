package com.kimo.music.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AuthCredentials(val token: String, val userId: String, val t1: String = "",
    val vipToken: String = "", val vipType: String = "", val dfid: String = "") {
    // 仅序列化核心所需的标量鉴权字段，昵称不进入协议请求头或 Cookie。
    fun json(): JSONObject = JSONObject().put("token", token).put("userid", userId)
        .put("t1", t1).put("vip_token", vipToken).put("vip_type", vipType).apply {
            if (dfid.isNotBlank()) put("dfid", dfid)
        }
}
data class AccountProfile(val userId: String, val nickname: String, val avatar: String? = null, val vip: Boolean = false,
    val nicknameVerified: Boolean = true) {
    // 旧版本的默认昵称来源不明，在线确认之前不作为真实昵称展示。
    val displayNickname: String get() = nickname.takeIf { nicknameVerified }.orEmpty()

    companion object {
        // 按旧会话格式恢复资料，保留原始昵称并标记无法确认的默认值。
        internal fun restored(userId: String, data: JSONObject): AccountProfile {
            val nickname = data.optString("nickname")
            return AccountProfile(userId, nickname, data.optString("avatar").takeIf { it.isNotBlank() && it != "null" },
                data.optBoolean("vip"), data.optBoolean("nicknameVerified", nickname != LEGACY_NICKNAME))
        }

        private const val LEGACY_NICKNAME = "酷狗用户"
    }
}
data class AccountSession(val credentials: AuthCredentials, val profile: AccountProfile)

// Android Keystore 托管密钥；偏好文件中只保存 AES-GCM 密文，不复用电脑端登录态。
class SessionVault(context: Context) {
    private val preferences = context.getSharedPreferences("kimo_account", Context.MODE_PRIVATE)

    // 读取失败时不把损坏或其他设备的密文当成有效登录。
    fun load(): AccountSession? = runCatching {
        val saved = preferences.getString(SESSION_KEY, null) ?: return null
        val data = Base64.decode(saved, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, IV_LENGTH)))
        decode(JSONObject(String(cipher.doFinal(data.copyOfRange(IV_LENGTH, data.size)), Charsets.UTF_8)))
    }.getOrNull()

    // 以一次偏好写入替换完整会话，避免 token 与用户资料出现跨账号组合。
    fun save(session: AccountSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = session.credentials.json().put("nickname", session.profile.nickname)
            .put("avatar", session.profile.avatar).put("vip", session.profile.vip)
            .put("nicknameVerified", session.profile.nicknameVerified)
        val encrypted = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        preferences.edit { putString(SESSION_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
    }

    // 退出时立即删除账号密文，设备标识与本地音乐保持独立。
    fun clear() { preferences.edit { remove(SESSION_KEY) } }

    // 在系统密钥库中创建不可导出的 AES 密钥。
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    // 密文解码后恢复强类型账号对象，关键鉴权字段缺失时拒绝恢复。
    private fun decode(data: JSONObject): AccountSession {
        val credentials = AuthCredentials(data.getString("token"), data.getString("userid"),
            data.optString("t1"), data.optString("vip_token"), data.optString("vip_type"), data.optString("dfid"))
        require(credentials.token.isNotBlank() && credentials.userId.isNotBlank())
        return AccountSession(credentials, AccountProfile.restored(credentials.userId, data))
    }

    private companion object {
        const val KEY_ALIAS = "kimo-account-aes-v1"
        const val SESSION_KEY = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
