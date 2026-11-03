package com.kimo.music

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kimo.music.ui.KimoApp
import com.kimo.music.ui.theme.KimoTheme
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.playback.MusicViewModel
import com.kimo.music.playback.KimoVisibility

class MainActivity : ComponentActivity() {
    private var openDownloads by mutableStateOf(false)
    // 使用系统安全区域和原生 Compose 入口，播放状态由生命周期感知模型持有。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDownloads = intent.getBooleanExtra("open_downloads", false)
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            val model: MusicViewModel = viewModel()
            LaunchedEffect(openDownloads) { if (openDownloads) { model.downloadsRequested = true; openDownloads = false } }
            KimoTheme(model.appearance) {
                // 外观变化同步更新系统图标，避免深色背景上的深色状态栏。
                AppearanceSystemBars(KimoColors.IsDark)
                Surface(Modifier.fillMaxSize(), color = KimoColors.Paper) { KimoApp(model) }
            }
        }
    }

    // 点击下载通知时复用现有页面，并打开同一份下载任务列表。
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openDownloads = intent.getBooleanExtra("open_downloads", false) }

    // 前后台状态只控制桌面歌词展示，不影响后台音频会话。
    override fun onStart() { super.onStart(); KimoVisibility.enter(this) }

    // 离开当前窗口后允许已授权的桌面歌词显示。
    override fun onStop() { KimoVisibility.leave(this); super.onStop() }

    // 主窗口跟随应用外观更新状态栏和导航栏，不重新创建媒体会话。
    @Composable
    private fun AppearanceSystemBars(dark: Boolean) {
        DisposableEffect(dark) {
            val transparent = android.graphics.Color.TRANSPARENT
            val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose { }
        }
    }
}
