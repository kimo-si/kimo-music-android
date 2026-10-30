package com.kimo.music.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimo.music.core.AccountModel
import com.kimo.music.core.QrPhase
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors

// 账号资料和两种真实登录方式共用入口，退出弹层立即停止扫码轮询。
@Composable
fun AccountDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    val account = model.account
    DisposableEffect(Unit) { onDispose { account.stopQr() } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = KimoColors.Paper,
        title = { Text(if (account.state.verified) "账号与同步" else "登录酷狗") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (account.state.verified) AccountDetails(model) else {
                    LoginMethods(account)
                    if (account.state.profile != null) TextButton(account::logout) { Text("退出当前账号") }
                }
                account.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}

// 已登录账号显示服务端校验结果及同步状态，注销操作只清理该账号。
@Composable
private fun AccountDetails(model: MusicViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(model.account.state.profile?.nickname.orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text("账号会话已验证", color = KimoColors.Secondary)
        Text("${model.online.cloud.playlists.size} 个云歌单 · ${model.online.cloud.favorites.size} 首我喜欢", fontSize = 13.sp)
        if (model.online.cloud.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        model.online.cloud.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(model.online::syncAccount) { Text("同步歌单与收藏") }
        TextButton(model.account::logout) { Text("退出当前账号") }
    }
}

// 在同一部手机上优先使用验证码，也保留与桌面相同的扫码登录流程。
@Composable
private fun LoginMethods(account: AccountModel) {
    var phoneMode by remember { mutableStateOf(true) }
    LaunchedEffect(phoneMode) { if (phoneMode) account.stopQr() else account.startQr() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(phoneMode, { phoneMode = true }, label = { Text("手机号登录") })
            FilterChip(!phoneMode, { phoneMode = false }, label = { Text("扫码登录") })
        }
        if (account.state.restoring) CircularProgressIndicator(Modifier.size(24.dp))
        else if (phoneMode) PhoneLogin(account) else QrLogin(account)
        Text("登录后同步酷狗账号的我喜欢、创建及收藏的歌单，播放权限遵循账号权益。",
            color = KimoColors.Secondary, fontSize = 12.sp, lineHeight = 20.sp)
    }
}

// 手机号和验证码仅存在弹层内存中，发送与登录均由用户主动提交。
@Composable
private fun PhoneLogin(account: AccountModel) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) }, label = { Text("手机号") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("短信验证码") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        OutlinedButton({ account.sendCode(phone) }, enabled = !account.state.sending && account.state.cooldown == 0) {
            Text(when {
                account.state.sending -> "发送中…"
                account.state.cooldown > 0 -> "${account.state.cooldown}s 后重发"
                else -> "获取验证码"
            })
        }
        Button({ account.loginWithCode(phone, code) }, enabled = !account.state.submitting) {
            Text(if (account.state.submitting) "正在验证…" else "登录并同步")
        }
    }
}

// 二维码以最近邻显示，避免缩放插值模糊模块边缘。
@Composable
private fun QrLogin(account: AccountModel) {
    val encoded = account.state.qrBase64
    val bitmap = remember(encoded) {
        runCatching { encoded?.substringAfter(',')?.let { Base64.decode(it, Base64.DEFAULT) }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "酷狗登录二维码", Modifier.size(220.dp), filterQuality = FilterQuality.None)
        else if (account.state.qrPhase == QrPhase.Loading) CircularProgressIndicator()
        Text(account.state.qrPhase.title, fontSize = 13.sp, color = KimoColors.Secondary)
        TextButton(account::startQr) { Text("刷新二维码") }
    }
}

// 歌单名称通过对象所属账号的接口写入，空名称不能提交。
@Composable
fun PlaylistNameDialog(title: String, onDismiss: () -> Unit, initial: String = "", onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("歌单名称") }, singleLine = true) },
        confirmButton = { TextButton({ onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("保存并同步") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
