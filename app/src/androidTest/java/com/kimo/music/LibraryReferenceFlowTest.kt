package com.kimo.music

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kimo.music.core.AccountProfile
import com.kimo.music.core.AccountState
import com.kimo.music.data.AppearanceMode
import com.kimo.music.data.DemoCatalog
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.ui.*
import com.kimo.music.ui.theme.KimoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LibraryReferenceFlowTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true, seedTracks = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    // 未登录总览展示完整引导，空收藏不出现无效播放按钮或伪造曲目。
    @Test fun guestOverviewMatchesReferenceAndOpensLogin() {
        val model = openLibrary(AppearanceMode.Dark)
        compose.onNodeWithText("登录，找回你的收藏").assertIsDisplayed()
        compose.onNodeWithText("离线也能听").assertIsDisplayed()
        compose.onNodeWithText("收藏，从一首歌开始").assertIsDisplayed()
        compose.onNodeWithText("播放全部").assertDoesNotExist()
        compose.onNodeWithContentDescription("音乐云盘").assertDoesNotExist()
        compose.onNodeWithContentDescription("我的歌单").assertIsDisplayed()
        val playlists = compose.onNodeWithContentDescription("我的歌单").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("默认空态的歌单行应完整位于导航上方", playlists.bottom <= navigation.top)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        capture("library-reference-guest-dark")
        compose.onNodeWithContentDescription("账号信息").performClick()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回上一页").performClick()
        assertFalse(model.loginRequested)
    }

    // 登录前的本地和下载入口可直接使用，发现按钮真正切换到底部发现页。
    @Test fun guestOfflineAndDiscoveryActionsKeepWorking() {
        openLibrary(AppearanceMode.Light)
        compose.onNodeWithContentDescription("本地音乐").performClick()
        compose.onNodeWithText("导入本地音频").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放歌曲：晚风经过").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回我的音乐").performClick()
        compose.onNodeWithContentDescription("已下载").performClick()
        compose.onNodeWithText("下载管理").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回下载管理").performClick()
        compose.waitUntil { compose.onAllNodesWithContentDescription("返回下载管理").fetchSemanticsNodes().isEmpty() }
        capture("library-reference-guest-light")
        compose.onNodeWithText("去发现音乐").performScrollTo().performClick()
        compose.onNodeWithText("歌单广场").assertIsDisplayed()
    }

    // 本机收藏在未登录时仍显示，进入播放页再返回保留真实列表与歌曲。
    @Test fun localFavoritesReplaceEmptyStateWithoutChangingOverviewStructure() {
        val model = openLibrary(AppearanceMode.Dark)
        compose.runOnIdle { DemoCatalog.tracks.forEach(model::toggleFavorite) }
        compose.onNodeWithText("收藏，从一首歌开始").assertDoesNotExist()
        compose.onNodeWithText("3 首").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放歌曲：山谷来信").performScrollTo().performClick()
        compose.waitUntil(15_000) { model.playback.track?.id == DemoCatalog.tracks[1].id && model.playback.playing }
        compose.onNodeWithContentDescription("打开播放页").performClick()
        compose.onNodeWithContentDescription("收起播放页").performClick()
        compose.onNodeWithText("喜欢的歌曲").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("library-playlists")
        compose.onNodeWithContentDescription("我的歌单").assertIsDisplayed()
        capture("library-reference-local-favorites")
    }

    // 同一生产组件切换真实账号展示数据，卡片高度与离线区域位置保持不变，不写入账号容器。
    @Test fun signedInIdentityKeepsGuestCardAndOfflineLayout() {
        val identity = mutableStateOf(LibraryIdentity.from(AccountState(restoring = false), false))
        var accountClicks = 0
        compose.runOnUiThread { compose.activity.setContent {
            KimoTheme(AppearanceMode.Dark) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        LibraryIdentityCard(identity.value) { accountClicks++ }
                        LibraryOfflineAccess({}, {}, {})
                        LibraryCollectionEmpty(identity.value.authenticated) {}
                        LibraryPlaylistAccess(identity.value.authenticated, if (identity.value.authenticated) 4 else null) {}
                    }
                }
            }
        } }
        val before = compose.onNodeWithTag("library-identity-card").fetchSemanticsNode().boundsInRoot
        val offlineBefore = compose.onNodeWithText("离线也能听").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { identity.value = LibraryIdentity.from(AccountState(
            profile = AccountProfile("ui-fixture", "林间旅人"), restoring = false, verified = true), true) }
        compose.onNodeWithText("林间旅人").assertIsDisplayed()
        compose.onNodeWithText("查看账号").performClick()
        assertEquals(1, accountClicks)
        assertEquals(before, compose.onNodeWithTag("library-identity-card").fetchSemanticsNode().boundsInRoot)
        assertEquals(offlineBefore, compose.onNodeWithText("离线也能听").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("4 个歌单").assertIsDisplayed()
        compose.onNodeWithText("登录后查看").assertDoesNotExist()
        capture("library-reference-signed-components")
    }

    // 使用页面原有模型切换主题，不创建第二个播放器或独立收藏集合。
    private fun openLibrary(appearance: AppearanceMode): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[MusicViewModel::class.java]
            model.changeAppearance(appearance)
        }
        compose.waitUntil(15_000) { !model.account.state.restoring }
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        return model
    }

    // 保存实际系统画面，核对安全区域、固定导航与主题配色。
    private fun capture(name: String) {
        compose.waitForIdle()
        val title = if (compose.onAllNodesWithText("我的音乐").fetchSemanticsNodes().isEmpty()) "离线也能听" else "我的音乐"
        compose.onNodeWithText(title).performTouchInput { click(center) }
        android.os.SystemClock.sleep(400)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "kimo-reference-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
