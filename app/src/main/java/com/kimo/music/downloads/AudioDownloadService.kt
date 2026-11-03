package com.kimo.music.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kimo.music.MainActivity
import com.kimo.music.R
import com.kimo.music.core.AudioQuality
import com.kimo.music.core.MusicApiException
import com.kimo.music.core.VipLoginRequiredException
import com.kimo.music.data.Track
import com.kimo.music.data.TrackStorage
import kotlinx.coroutines.*
import org.json.JSONObject

class AudioDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private lateinit var repository: DownloadRepository
    private lateinit var transfer: DownloadTransfer
    private var pendingCommands = 0
    private var waitingForFirstCommand = true

    // 建立独立前台下载服务，下载任务不依赖播放页面是否存在。
    override fun onCreate() {
        super.onCreate(); repository = DownloadRepository.get(this); transfer = DownloadTransfer(this, repository)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "音乐下载", NotificationManager.IMPORTANCE_LOW))
        foreground()
        scope.launch { repository.items.collect { rows ->
            if (rows.any { it.status == DownloadStatus.Downloading }) foreground()
            if (!waitingForFirstCommand && pendingCommands == 0) pump()
        } }
    }

    // 只处理应用内部发送的任务命令，并在完成命令后统一调度并发任务。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pendingCommands++
        waitingForFirstCommand = false
        scope.launch {
            try { handle(intent) }
            finally { pendingCommands--; pump() }
        }
        return START_NOT_STICKY
    }

    // 新增、暂停、继续与删除均以任务标识操作，不携带任何临时音频地址。
    private suspend fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(TASK_ID).orEmpty()
        when (intent?.action) {
            ADD -> {
                val track = runCatching { TrackStorage.decode(JSONObject(intent.getStringExtra(TRACK).orEmpty())) }.getOrNull() ?: return
                val quality = AudioQuality.entries.firstOrNull { it.name == intent.getStringExtra(QUALITY) } ?: AudioQuality.Standard
                val item = repository.enqueue(track, quality)
                if (item.status !in listOf(DownloadStatus.Complete, DownloadStatus.Downloading)) repository.status(item.id, DownloadStatus.Queued)
            }
            PAUSE, REMOVE -> {
                jobs[id]?.cancel(); transfer.interrupt(id); jobs[id]?.join()
                if (intent.action == REMOVE) repository.remove(id)
                else if (repository.item(id)?.status != DownloadStatus.Complete) repository.status(id, DownloadStatus.Paused)
            }
            RESUME -> repository.status(id, DownloadStatus.Queued)
        }
    }

    // 预留任务槽后再启动协程，避免快速状态通知重复启动同一下载。
    private fun pump() {
        val queued = repository.items.value.filter { it.status == DownloadStatus.Queued && it.id !in jobs }
        queued.take((MAX_PARALLEL - jobs.size).coerceAtLeast(0)).forEach { item ->
            val job = scope.launch(start = CoroutineStart.LAZY) { perform(item) }
            jobs[item.id] = job; job.start()
        }
        if (!waitingForFirstCommand && pendingCommands == 0 && jobs.isEmpty() && queued.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    // 失败保留片段和原因，用户重试时重新鉴权并尝试安全续传。
    private suspend fun perform(item: DownloadItem) {
        try {
            repository.status(item.id, DownloadStatus.Downloading)
            transfer.run(repository.item(item.id) ?: return)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { if (repository.item(item.id)?.status != DownloadStatus.Complete)
                repository.status(item.id, DownloadStatus.Paused) }
        } catch (error: Exception) {
            val message = when (error) {
                is VipLoginRequiredException -> error.message.orEmpty()
                is MusicApiException -> error.message.orEmpty()
                else -> if (error.message in SAFE_ERRORS) error.message.orEmpty() else "下载失败，请检查网络后重试"
            }
            repository.status(item.id, DownloadStatus.Failed, message)
        } finally { jobs.remove(item.id); if (scope.isActive) pump() }
    }

    // 通知只展示任务进度与返回应用入口，不展示鉴权地址。
    private fun foreground() {
        val active = repository.items.value.filter { it.status in listOf(DownloadStatus.Queued, DownloadStatus.Downloading) }
        val current = active.firstOrNull()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("open_downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_kimo)
            .setContentTitle(if (active.isEmpty()) "正在准备音乐下载" else "正在下载 ${active.size} 首音乐")
            .setContentText(current?.track?.title ?: "请稍候").setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, if (current != null && current.total > 0) (current.received * 100 / current.total).toInt() else 0,
                current == null || current.total <= 0).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    // 达到系统前台服务时限时停止任务，片段由仓库保留供用户稍后继续。
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    // 服务关闭后及时断开网络与协程，取消中的任务会保存为暂停状态。
    override fun onDestroy() { scope.cancel(); transfer.close(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }

    // 下载服务仅接受显式 Intent 命令，不提供绑定接口。
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "kimo_audio_downloads"
        private const val NOTIFICATION_ID = 2201
        private const val MAX_PARALLEL = 2
        private const val TASK_ID = "download_id"
        private const val TRACK = "track"
        private const val QUALITY = "quality"
        private const val ADD = "com.kimo.music.DOWNLOAD_ADD"
        const val PAUSE = "com.kimo.music.DOWNLOAD_PAUSE"
        const val RESUME = "com.kimo.music.DOWNLOAD_RESUME"
        const val REMOVE = "com.kimo.music.DOWNLOAD_REMOVE"
        private val SAFE_ERRORS = setOf("存储空间不足，请清理后重试", "服务器未返回有效音频", "下载服务器返回异常，请重试",
            "下载尚未完成，请继续下载", "下载的音频无法解析，请重新下载", "无法保存下载文件，请检查存储空间")

        // 用户主动加入下载时通过前台服务执行，支持页面离开后的持续下载。
        fun enqueue(context: Context, track: Track, quality: AudioQuality) {
            val intent = Intent(context, AudioDownloadService::class.java).setAction(ADD)
                .putExtra(TRACK, TrackStorage.encode(track).toString()).putExtra(QUALITY, quality.name)
            ContextCompat.startForegroundService(context, intent)
        }

        // 管理页使用同一受控入口暂停、继续或删除指定任务。
        fun command(context: Context, id: String, action: String) {
            require(action in listOf(PAUSE, RESUME, REMOVE))
            ContextCompat.startForegroundService(context, Intent(context, AudioDownloadService::class.java)
                .setAction(action).putExtra(TASK_ID, id))
        }
    }
}
