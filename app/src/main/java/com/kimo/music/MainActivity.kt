package com.kimo.music

import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    // 使用系统安全区域和原生 Compose 入口，播放状态由生命周期感知模型持有。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            val model: MusicViewModel = viewModel()
            KimoTheme(model.appearance) {
                // 外观变化同步更新系统图标，避免深色背景上的深色状态栏。
                AppearanceSystemBars(KimoColors.IsDark)
                Surface(Modifier.fillMaxSize(), color = KimoColors.Paper) { KimoApp(model) }
            }
        }
    }

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
