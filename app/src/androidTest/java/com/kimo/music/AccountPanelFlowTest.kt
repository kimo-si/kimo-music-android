package com.kimo.music

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kimo.music.ui.AccountPanelHost
import com.kimo.music.ui.theme.KimoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountPanelFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val verified = mutableStateOf(false)
    private var dismissals = 0

    // 登录成功关闭原入口，账号详情不会作为新的弹层自动出现。
    @Test fun successfulLoginDismissesWithoutOpeningAccountDetails() {
        showPanel(false)
        compose.onNodeWithText("测试登录页").assertIsDisplayed()
        compose.runOnIdle { verified.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("测试登录页").assertDoesNotExist()
        compose.onNodeWithText("测试账号详情").assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    // 已登录时手动进入账号仍可查看详情，注销后同样关闭，不自动开启登录页。
    @Test fun explicitAccountEntryRemainsAvailableAndClosesAfterLogout() {
        showPanel(true)
        compose.onNodeWithText("测试账号详情").assertIsDisplayed()
        assertEquals(0, dismissals)
        compose.runOnIdle { verified.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("测试账号详情").assertDoesNotExist()
        compose.onNodeWithText("测试登录页").assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    // 使用真实账号入口生命周期组件控制验证状态，不发送短信或修改登录凭据。
    private fun showPanel(initial: Boolean) {
        compose.runOnUiThread {
            verified.value = initial
            compose.activity.setContent {
                KimoTheme {
                    AccountPanelHost(verified.value, { dismissals++ }) { details ->
                        Text(if (details) "测试账号详情" else "测试登录页")
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
