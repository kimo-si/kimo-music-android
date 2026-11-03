package com.kimo.music.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.kimo.music.core.*
import com.kimo.music.data.ListeningPreferences
import com.kimo.music.data.online
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

object KimoVisibility {
    private val activities = mutableSetOf<Any>()
    private val state = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = state

    // 多窗口切换按实际 Activity 数量计算，不因旧窗口关闭误判前后台。
    fun enter(activity: Any) { activities.add(activity); state.value = activities.isNotEmpty() }

    // 离开应用时允许桌面歌词出现，应用内部弹窗不会改变该计数。
    fun leave(activity: Any) { activities.remove(activity); state.value = activities.isNotEmpty() }
}

class DesktopLyricsController(private val context: Context, private val player: Player) : Player.Listener {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val position = context.getSharedPreferences("kimo_lyrics_position", Context.MODE_PRIVATE)
    private val preferences = ListeningPreferences(context) { loadLyrics(); render() }
    private var loadedTrackId: String? = null
    private var panel: LinearLayout? = null
    private var currentText: TextView? = null
    private var subtitle: TextView? = null
    private var lines = emptyList<LyricLine>()
    private var lyricJob: Job? = null
    private var closed = false
    private val tick = object : Runnable {
        // 桌面歌词只更新文字，不重建整个悬浮窗。
        override fun run() { render() }
    }

    init {
        player.addListener(this)
        scope.launch { KimoVisibility.visible.collect { render() } }
        loadLyrics(); render()
    }

    // 换歌时取消旧歌词任务，避免上一首歌词迟到覆盖新歌曲。
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { loadLyrics(); render() }

    // 专用后台线程读取歌词，失败时只展示真实曲名。
    private fun loadLyrics() {
        if (!preferences.options.desktopLyrics) { lyricJob?.cancel(); loadedTrackId = null; lines = emptyList(); return }
        val track = PlaybackItems.track(player.currentMediaItem) ?: return
        if (loadedTrackId == track.id) return
        loadedTrackId = track.id
        lyricJob?.cancel(); lines = emptyList()
        if (!track.online) return
        lyricJob = scope.launch {
            val vault = SessionVault(context)
            try { lines = KugouPlaybackApi(KugouApi(EmbeddedCore.get(context)) { vault.load()?.credentials }).lyrics(track) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lines = emptyList() }
            render()
        }
    }

    // 只在用户授权、开启且应用处于后台时展示，权限撤销后立即移除。
    private fun render() {
        handler.removeCallbacks(tick)
        if (closed) return
        if (!preferences.options.desktopLyrics || KimoVisibility.visible.value || !Settings.canDrawOverlays(context)) { hide(); return }
        val track = PlaybackItems.track(player.currentMediaItem)
        if (track == null) {
            hide(); return
        }
        if (panel == null && !show()) return
        val line = lines.lastOrNull { it.timeMs <= player.currentPosition }?.text
        currentText?.text = line ?: track.title
        subtitle?.text = if (line != null) track.title else listOf(track.displayArtist, if (player.isPlaying) "播放中" else "已暂停")
            .filter(String::isNotBlank).joinToString(" · ")
        handler.postDelayed(tick, TICK_MS)
    }

    // 悬浮层使用非焦点窗口，外部应用的输入与返回操作仍由系统正常处理。
    private fun show(): Boolean = runCatching {
        val view = createPanel()
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(min(metrics.widthPixels - dp(24), dp(560)), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = position.getInt("x", 0); y = position.getInt("y", metrics.heightPixels / 3)
        }
        enableDragging(view, params)
        manager.addView(view, params); panel = view
        true
    }.getOrElse { hide(); false }

    // 高对比文字和橙色强调与应用主题保持一致，关闭按钮保留触控区域。
    private fun createPanel(): LinearLayout {
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(6), dp(12))
            background = GradientDrawable().apply { setColor(Color.argb(235, 37, 40, 37)); cornerRadius = dp(18).toFloat() }
        }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        currentText = TextView(context).apply { textSize = 18f; setTextColor(Color.rgb(241, 138, 83)); setTypeface(null, Typeface.BOLD); maxLines = 3 }
        subtitle = TextView(context).apply { textSize = 12f; setTextColor(Color.rgb(242, 237, 228)); setPadding(0, dp(5), 0, 0); maxLines = 1 }
        text.addView(currentText); text.addView(subtitle)
        view.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT); contentDescription = "关闭桌面歌词"
            setOnClickListener { preferences.update(preferences.options.copy(desktopLyrics = false)); hide() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return view
    }

    // 位置只在手势结束后保存，拖动过程中不反复写入偏好。
    @Suppress("ClickableViewAccessibility")
    private fun enableDragging(view: LinearLayout, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - downX).toInt().coerceIn(-dp(100), dp(100))
                    params.y = (startY + event.rawY - downY).toInt().coerceIn(0, context.resources.displayMetrics.heightPixels - dp(100))
                    runCatching { manager.updateViewLayout(view, params) }
                }
                MotionEvent.ACTION_UP -> position.edit { putInt("x", params.x); putInt("y", params.y) }
            }
            true
        }
    }

    // 移除窗口时容忍系统已经撤销权限或回收窗口的情况。
    private fun hide() {
        panel?.let { runCatching { manager.removeView(it) } }
        panel = null; currentText = null; subtitle = null
    }

    // 释放全部窗口、网络任务与监听，避免后台歌词在退出后残留。
    fun release() {
        closed = true; handler.removeCallbacks(tick); lyricJob?.cancel(); scope.cancel()
        player.removeListener(this); preferences.close(); hide()
    }

    // 所有悬浮层尺寸随设备密度换算。
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object { const val TICK_MS = 300L }
}
