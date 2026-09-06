package com.zhukongqwq.hanser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 文档更新前台服务：在通知栏常驻显示下载进度，可切到后台继续下载。
 * 任务完成后发一条结果通知并停止服务。
 */
class DocUpdateService : Service() {

    companion object {
        private const val CHANNEL_ID = "doc_update"
        private const val NOTIFICATION_ID = 1001

        /** 启动前台文档更新服务。 */
        fun start(context: Context, repoUrl: String) {
            val intent = Intent(context, DocUpdateService::class.java)
                .putExtra(EXTRA_REPO, repoUrl)
            ContextCompat.startForegroundService(context, intent)
        }

        private const val EXTRA_REPO = "repoUrl"
    }

    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "文档更新", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "文档库增量更新的后台下载进度" }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repoUrl = intent?.getStringExtra(EXTRA_REPO)
        if (repoUrl.isNullOrBlank() || DocUpdateManager.state.value.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        DocUpdateManager.begin()
        // 持有部分唤醒锁：息屏/后台时保持 CPU 运行，保证下载不中断
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hanser:doc_update")
            .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) } // 最长 1 小时
        startForeground(NOTIFICATION_ID, buildNotification("准备检查更新…", 0, 0, ""))
        Thread {
            try {
                var resultText: String
                try {
                val logs = StringBuilder()
                val r = AppCore.githubSync.run(
                    repoUrl,
                    log = { logs.appendLine(it) },
                    onProgress = { done, total, current ->
                        DocUpdateManager.setProgress(done, total, current)
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification("正在下载 $current", done, total, current)
                        )
                    }
                )
                resultText = buildString {
                    appendLine("文档更新完成：新增 ${r.added}，更新 ${r.updated}，失败 ${r.failed}")
                    appendLine()
                    append(logs)
                    if (r.errors.isNotEmpty()) {
                        appendLine()
                        append("失败明细：\n").append(r.errors.take(3).joinToString("\n"))
                    }
                }
            } catch (e: Exception) {
                resultText = "文档更新失败：${e.message}"
            }
            DocUpdateManager.finish(resultText)
            // 完成通知（点击回到主界面）
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("文档更新")
                    .setContentText(resultText.lineSequence().first().orEmpty())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(resultText))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, done: Int, total: Int, current: String): Notification {
        val progressIndeterminate = total <= 0
        val text = when {
            progressIndeterminate -> title
            current.isNotEmpty() -> "$current（$done/$total）"
            else -> title
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("文档库更新")
            .setContentText(text)
            .setProgress(if (progressIndeterminate) 0 else total, done, progressIndeterminate)
            .setOngoing(true)
            .build()
    }
}
