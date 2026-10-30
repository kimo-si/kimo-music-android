package com.kimo.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kimo.music.ui.KimoApp
import com.kimo.music.ui.theme.KimoTheme
import com.kimo.music.ui.theme.KimoColors

class MainActivity : ComponentActivity() {
    // 使用系统安全区域和原生 Compose 入口，播放状态由生命周期感知模型持有。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            KimoTheme {
                Surface(Modifier.fillMaxSize(), color = KimoColors.Paper) { KimoApp(viewModel()) }
            }
        }
    }
}
