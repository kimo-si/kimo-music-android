package com.kimo.music.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kimo.music.core.AccountModel
import com.kimo.music.core.QrPhase
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.theme.KimoColors

private object LoginStyle {
    val Background: Color @Composable get() = if (KimoColors.IsDark) KimoColors.Paper else Color(0xFFFBF9F2)
    val Field: Color @Composable get() = if (KimoColors.IsDark) KimoColors.Surface else Color(0xFFF1ECE3)
    val Ink: Color @Composable get() = if (KimoColors.IsDark) KimoColors.Ink else Color(0xFF181918)
    val Secondary: Color @Composable get() = if (KimoColors.IsDark) KimoColors.Secondary else Color(0xFF898A85)
    val Accent: Color @Composable get() = if (KimoColors.IsDark) KimoColors.Orange else Color(0xFFE86C35)
    val Divider: Color @Composable get() = if (KimoColors.IsDark) KimoColors.Rail else Color(0xFFD2CCC3)
    val Inset = 24.dp
    val FieldHeight = 58.dp
    val FieldShape = RoundedCornerShape(14.dp)
    const val TermsUrl = "https://www.kugou.com/about/protocol.html"
    const val PrivacyUrl = "https://m3ws.kugou.com/html/privacy.html"
}

private enum class LoginMode { Phone, Qr }

private class LoginForm {
    var phone by mutableStateOf("")
    var code by mutableStateOf("")
    var agreed by mutableStateOf(false)
    var mode by mutableStateOf(LoginMode.Phone)
    var notice by mutableStateOf<String?>(null)

    // 未同意时保留页面输入并明确提示，不发送手机号或发起登录。
    fun requireAgreement(): Boolean {
        notice = if (agreed) null else "请先阅读并同意酷狗用户服务协议和隐私政策"
        return agreed
    }

    // 切换登录方式时清除本页提示，勾选状态与输入继续保留。
    fun switchMode() {
        mode = if (mode == LoginMode.Phone) LoginMode.Qr else LoginMode.Phone
        notice = null
    }
}

// 已登录显示账号详情，未登录进入全屏登录页；离开时结束扫码轮询。
@Composable
fun AccountDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    val account = model.account
    DisposableEffect(account) { onDispose { account.stopQr() } }
    AccountPanelHost(account.state.verified, onDismiss) { details ->
        if (details) AccountDetailsDialog(model, onDismiss) else LoginDialog(account, onDismiss)
    }
}

// 固定本次打开的入口模式；登录或注销完成后关闭，不自动切换成另一个账号弹层。
@Composable
internal fun AccountPanelHost(verified: Boolean, onDismiss: () -> Unit, content: @Composable (Boolean) -> Unit) {
    val initiallyVerified = rememberSaveable { verified }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(verified) { if (verified != initiallyVerified) dismiss() }
    if (verified == initiallyVerified) content(initiallyVerified)
}

// 已登录账号展示真实资料和注销入口，不改变后台同步流程。
@Composable
private fun AccountDetailsDialog(model: MusicViewModel, onDismiss: () -> Unit) {
    val nickname = model.account.state.profile?.displayNickname?.takeIf(String::isNotBlank)
    KimoActionSheet("账号信息", onDismiss, "账号会话已验证") {
        if (nickname != null) Text(nickname, Modifier.padding(vertical = 12.dp),
            fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = KimoColors.Ink)
        model.account.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        KimoSheetOption("退出当前账号", Icons.AutoMirrored.Outlined.Logout, model.account::logout, showChevron = false)
    }
}

// 全屏窗口独立处理返回和系统栏，避免覆盖底层播放器状态。
@Composable
private fun LoginDialog(account: AccountModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        KimoTipWindow()
        LoginSystemBars()
        LoginPage(account, onDismiss)
    }
}

// 登录页系统图标跟随外观，并关闭导航栏额外的系统遮罩。
@Suppress("DEPRECATION")
@Composable
private fun LoginSystemBars() {
    val view = LocalView.current
    val dark = KimoColors.IsDark
    DisposableEffect(view, dark) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        window?.statusBarColor = Color.Transparent.toArgb()
        window?.navigationBarColor = Color.Transparent.toArgb()
        if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose { }
    }
}

// 登录页自适应键盘与短屏，页面关闭或切换方式时取消二维码轮询。
@Composable
private fun LoginPage(account: AccountModel, onDismiss: () -> Unit) {
    val form = remember { LoginForm() }
    LaunchedEffect(form.mode, form.agreed) {
        // 扫码也只在用户明确同意后启动真实远程登录流程。
        if (form.mode == LoginMode.Qr && form.agreed) account.startQr() else account.stopQr()
    }
    DisposableEffect(account) { onDispose { account.stopQr() } }
    Column(Modifier.fillMaxSize().background(LoginStyle.Background)
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        LoginBackButton(onDismiss)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            LoginBody(account, form, maxHeight < 600.dp || maxWidth > maxHeight)
        }
    }
}

// 顶部返回按钮提供完整触控范围，并回到原来的浏览或播放页面。
@Composable
private fun LoginBackButton(onDismiss: () -> Unit) {
    IconButton(onDismiss, Modifier.padding(start = 12.dp).size(48.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一页", tint = LoginStyle.Ink, modifier = Modifier.size(25.dp))
    }
}

// 纵向空间不足时收紧品牌区，内容仍可滚动且不遮挡键盘。
@Composable
private fun LoginBody(account: AccountModel, form: LoginForm, compact: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = LoginStyle.Inset).padding(bottom = 24.dp)) {
        LoginBrand(compact)
        LoginHeading(form.mode)
        Spacer(Modifier.height(if (compact) 24.dp else 34.dp))
        if (form.mode == LoginMode.Phone) PhoneLogin(account, form) else QrLogin(account, form.agreed)
        form.notice?.let { Text(it, Modifier.padding(top = 10.dp), color = LoginStyle.Accent, fontSize = 12.sp, lineHeight = 18.sp) }
        account.state.error?.let { Text(it, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp) }
        LoginAgreement(form)
        LoginModeSwitch(form)
        if (account.state.profile != null) TextButton(account::logout) { Text("退出当前账号") }
    }
}

// 参考图的唱片插画替换为 Kimo 字标，保留原有页面的留白层次。
@Composable
private fun LoginBrand(compact: Boolean) {
    Box(Modifier.fillMaxWidth().height(if (compact) 72.dp else 144.dp), contentAlignment = Alignment.CenterStart) {
        Text("Kimo", color = LoginStyle.Ink, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
    }
}

// 标题和说明随登录方式变化，主标题始终保持参考图的左对齐。
@Composable
private fun LoginHeading(mode: LoginMode) {
    Text("欢迎回来", color = LoginStyle.Ink, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold)
    Text(if (mode == LoginMode.Phone) "手机号验证码登录" else "扫码登录酷狗账号",
        Modifier.padding(top = 4.dp), color = LoginStyle.Secondary, fontSize = 18.sp, lineHeight = 26.sp)
}

// 手机号与验证码仅保存在页面内存，发送和登录由用户主动操作。
@Composable
private fun PhoneLogin(account: AccountModel, form: LoginForm) {
    val codeFocus = remember { FocusRequester() }
    FieldLabel("手机号")
    Spacer(Modifier.height(8.dp))
    PhoneNumberField(form, codeFocus)
    Spacer(Modifier.height(24.dp))
    FieldLabel("验证码")
    Spacer(Modifier.height(8.dp))
    VerificationCodeField(account, form, codeFocus)
    Spacer(Modifier.height(24.dp))
    LoginButton(account, form)
}

// 统一表单标签的文字层级和字段间距。
@Composable
private fun FieldLabel(text: String) {
    Text(text, color = LoginStyle.Ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
}

// 当前验证码接口只支持中国大陆手机号，因此国家代码是明确的固定标识。
@Composable
private fun PhoneNumberField(form: LoginForm, codeFocus: FocusRequester) {
    Row(Modifier.fillMaxWidth().height(LoginStyle.FieldHeight).background(LoginStyle.Field, LoginStyle.FieldShape)
        .padding(start = 16.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+86", color = LoginStyle.Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        LoginDivider(Modifier.padding(horizontal = 16.dp))
        LoginTextField(form.phone, { form.phone = it.filter(Char::isDigit).take(11) }, "请输入手机号", "手机号输入",
            Modifier.weight(1f), KeyboardType.Phone, ImeAction.Next, KeyboardActions(onNext = { codeFocus.requestFocus() }))
    }
}

// 验证码输入与发送按钮共用一行，冷却和发送状态沿用账号模型。
@Composable
private fun VerificationCodeField(account: AccountModel, form: LoginForm, focus: FocusRequester) {
    val focusManager = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().height(LoginStyle.FieldHeight).background(LoginStyle.Field, LoginStyle.FieldShape)
        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LoginTextField(form.code, { form.code = it.filter(Char::isDigit).take(8) }, "请输入验证码", "验证码输入",
            Modifier.weight(1f).focusRequester(focus), KeyboardType.Number, ImeAction.Done,
            KeyboardActions(onDone = {
                focusManager.clearFocus()
                if (form.requireAgreement()) account.loginWithCode(form.phone, form.code)
            }))
        LoginDivider(Modifier.padding(start = 8.dp))
        SendCodeButton(account, form)
    }
}

// 简洁文本输入保留辅助功能名称、数字键盘和清晰的占位提示。
@Composable
private fun LoginTextField(value: String, onChange: (String) -> Unit, placeholder: String, label: String,
    modifier: Modifier, keyboard: KeyboardType, action: ImeAction, keyboardActions: KeyboardActions) {
    BasicTextField(value, onChange, modifier.semantics { contentDescription = label }, singleLine = true,
        textStyle = TextStyle(color = LoginStyle.Ink, fontSize = 16.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(LoginStyle.Accent), keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = action),
        keyboardActions = keyboardActions, decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = LoginStyle.Secondary, fontSize = 16.sp, maxLines = 1)
                field()
            }
        })
}

// 输入框内的分隔线区分国家代码、验证码和发送操作。
@Composable
private fun LoginDivider(modifier: Modifier = Modifier) {
    VerticalDivider(modifier.height(28.dp), thickness = 1.dp, color = LoginStyle.Divider)
}

// 发送验证码前先检查协议，避免未同意时向远端提交手机号。
@Composable
private fun SendCodeButton(account: AccountModel, form: LoginForm) {
    val state = account.state
    val focus = LocalFocusManager.current
    TextButton(onClick = {
        if (form.requireAgreement()) account.sendCode(form.phone) else focus.clearFocus()
    },
        enabled = !state.restoring && !state.sending && state.cooldown == 0, contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = LoginStyle.Accent, disabledContentColor = LoginStyle.Secondary)) {
        Text(when { state.sending -> "发送中…"; state.cooldown > 0 -> "${state.cooldown}s 后重发"; else -> "获取验证码" },
            fontSize = 14.sp, maxLines = 1)
    }
}

// 登录继续复用原有验证码校验和真实账号同步逻辑。
@Composable
private fun LoginButton(account: AccountModel, form: LoginForm) {
    val focus = LocalFocusManager.current
    Button(onClick = {
        focus.clearFocus()
        if (form.requireAgreement()) account.loginWithCode(form.phone, form.code)
    },
        modifier = Modifier.fillMaxWidth().height(56.dp), enabled = !account.state.restoring && !account.state.submitting,
        shape = LoginStyle.FieldShape, colors = ButtonDefaults.buttonColors(containerColor = LoginStyle.Accent)) {
        Text(if (account.state.submitting) "正在验证…" else "登录", fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

// 二维码只在用户同意后请求，使用最近邻缩放保留实际扫码清晰度。
@Composable
private fun QrLogin(account: AccountModel, agreed: Boolean) {
    val encoded = account.state.qrBase64
    val bitmap = remember(encoded) {
        runCatching { encoded?.substringAfter(',')?.let { Base64.decode(it, Base64.DEFAULT) }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!agreed) Text("同意协议后显示二维码", color = LoginStyle.Secondary, fontSize = 14.sp)
        else if (bitmap != null) Image(bitmap.asImageBitmap(), "酷狗登录二维码", Modifier.size(220.dp), filterQuality = FilterQuality.None)
        else if (account.state.qrPhase == QrPhase.Loading) CircularProgressIndicator(color = LoginStyle.Accent)
        if (agreed) Text(account.state.qrPhase.title, fontSize = 13.sp, color = LoginStyle.Secondary)
        if (agreed) TextButton(account::startQr) { Text("刷新二维码", color = LoginStyle.Accent) }
    }
}

// 用户可以独立勾选协议，并打开酷狗官网提供的实际协议和隐私政策。
@Composable
private fun LoginAgreement(form: LoginForm) {
    val uriHandler = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
        AgreementCheck(form.agreed) { form.agreed = it; form.notice = null }
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text("我已阅读并同意", color = LoginStyle.Secondary, fontSize = 14.sp, lineHeight = 20.sp)
            AgreementLinks { url ->
                runCatching { uriHandler.openUri(url) }.onFailure { form.notice = "无法打开协议，请检查浏览器设置" }
            }
        }
    }
}

// 圆形复选控件保留标准复选框语义，点击文字不会误改勾选状态。
@Composable
private fun AgreementCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.size(48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .semantics { contentDescription = "同意酷狗协议" }, contentAlignment = Alignment.Center) {
        Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            Modifier.size(20.dp), tint = if (checked) LoginStyle.Accent else LoginStyle.Secondary)
    }
}

// 标签明确区分酷狗协议与 Kimo 本身，长文本在小屏和大字号下自动换行。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgreementLinks(onOpen: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth()) {
        Text("酷狗", color = LoginStyle.Secondary, fontSize = 14.sp, lineHeight = 22.sp)
        AgreementLink("《用户服务协议》") { onOpen(LoginStyle.TermsUrl) }
        Text("和", color = LoginStyle.Secondary, fontSize = 14.sp, lineHeight = 22.sp)
        AgreementLink("《隐私政策》") { onOpen(LoginStyle.PrivacyUrl) }
    }
}

// 每个协议链接拥有独立点击语义，不使用不可访问的占位地址。
@Composable
private fun AgreementLink(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clickable(role = Role.Button, onClick = onClick), color = LoginStyle.Accent,
        fontSize = 14.sp, lineHeight = 22.sp)
}

// 次要入口保留原有扫码能力，不打断参考图中的手机号登录主流程。
@Composable
private fun LoginModeSwitch(form: LoginForm) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        TextButton({ focus.clearFocus(); keyboard?.hide(); form.switchMode() }) {
            Text(if (form.mode == LoginMode.Phone) "扫码登录" else "手机号登录", color = LoginStyle.Secondary)
        }
    }
}

// 歌单名称通过对象所属账号的接口写入，空名称不能提交。
@Composable
fun PlaylistNameDialog(title: String, onDismiss: () -> Unit, initial: String = "", onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            KimoTipWindow()
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("歌单名称") }, singleLine = true)
        },
        confirmButton = { TextButton({ onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
