package com.kimo.music

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kimo.music.core.LyricLine
import com.kimo.music.ui.AnimatedLyricLines
import com.kimo.music.ui.LyricPreviewColors
import com.kimo.music.ui.theme.KimoColors
import com.kimo.music.ui.theme.KimoTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class LyricMotionTest {
    @get:Rule(order = 0) val library = LocalLibraryFixture(clearOnStart = true)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val current = mutableIntStateOf(0)
    private val lines = (0..29).map { LyricLine(it * 1_000L, "第 $it 行歌词过渡检查") }

    // 使用实际 Compose 帧时钟测试可视歌词组件，不修改正式应用的歌词来源。
    @Before fun showLyrics() {
        compose.runOnUiThread {
            compose.activity.setContent {
                KimoTheme {
                    Surface {
                        AnimatedLyricLines(lines, current.intValue, 3,
                            LyricPreviewColors(KimoColors.Ink, KimoColors.Secondary), Modifier.padding(24.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    // 下一行从原位置平滑接到当前行，过渡期间视口高度保持稳定。
    @Test fun nextLineMovesContinuouslyIntoCurrentPosition() {
        val viewport = bounds("animated-lyrics")
        val currentTop = bounds("lyric-line-0").top
        val start = bounds("lyric-line-1").top
        saveImage("lyrics-before")
        changeLine(1)
        compose.mainClock.advanceTimeBy(160)
        val middle = bounds("lyric-line-1").top
        saveImage("lyrics-middle")
        assertTrue("current=$currentTop start=$start middle=$middle", middle > currentTop && middle < start)
        assertEquals(viewport.height, bounds("animated-lyrics").height, 0.1f)
        compose.mainClock.advanceTimeBy(320)
        assertEquals(currentTop, bounds("lyric-line-1").top, 1f)
        saveImage("lyrics-after")
    }

    // 快速连续换行从当前绘制位置接续，不能先跳到上一次动画的终点。
    @Test fun rapidLineChangesRetargetWithoutPositionJumps() {
        val currentTop = bounds("lyric-line-0").top
        changeLine(1)
        compose.mainClock.advanceTimeBy(160)
        val previous = bounds("lyric-line-1").top
        changeLine(2)
        // 提交目标会推进一帧，允许这一帧的正常位移，但不能跳到旧目标位置。
        assertEquals(previous, bounds("lyric-line-1").top, compose.activity.resources.displayMetrics.density * 6f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(currentTop, bounds("lyric-line-2").top, 1f)
    }

    // 跨多行拖动时短暂淡出后直接定位，不滚过一长串已跳过的歌词。
    @Test fun distantSeekSettlesQuicklyWithoutLongScrolling() {
        val viewport = bounds("animated-lyrics")
        changeLine(20)
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("lyric-line-0").assertExists()
        compose.onNodeWithTag("lyric-line-20").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("lyric-line-20").assertIsDisplayed()
        compose.onNodeWithTag("lyric-line-0").assertDoesNotExist()
        assertEquals(viewport.height, bounds("animated-lyrics").height, 0.1f)
    }

    // 将目标变化提交到第一帧，后续测试自行推进动画时间。
    private fun changeLine(index: Int) {
        compose.runOnIdle { current.intValue = index }
        compose.mainClock.advanceTimeByFrame()
    }

    // 文字子节点包含父图层的实际变换；外层容器的布局坐标在动画中保持不变。
    private fun bounds(tag: String): androidx.compose.ui.geometry.Rect {
        val node = compose.onNodeWithTag(tag)
        return (if (tag.startsWith("lyric-line-")) node.onChild() else node).fetchSemanticsNode().boundsInRoot
    }

    // 保存切换前、中、后的真实画面供视觉核对。
    private fun saveImage(name: String) {
        val file = File(compose.activity.filesDir, "qa/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
