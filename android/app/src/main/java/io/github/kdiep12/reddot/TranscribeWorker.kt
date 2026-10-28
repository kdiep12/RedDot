package io.github.kdiep12.reddot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kdiep12.reddot.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Notes recorded while the server was unreachable (no signal in the field, PC asleep): Android
 * runs this once there is a network, oldest note first, and again with growing pauses
 * (30 s, 1 min, 2 min...) for as long as the server stays unreachable.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = RedDotApp.of(applicationContext)
        var transcribed = 0
        // Again and again: notes recorded while this runs are picked up too.
        while (true) {
            val next = app.notes.waiting().firstOrNull() ?: break
            when (val outcome = app.dictation.transcribe(next, queueOnFailure = false)) {
                is Dictation.Outcome.Failed -> if (outcome.willRetry) {
                    Notifications.transcribed(applicationContext, transcribed)
                    return Result.retry()
                }
                else -> transcribed++
            }
        }
        Notifications.transcribed(applicationContext, transcribed)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("transcribe-waiting-notes", ExistingWorkPolicy.KEEP, request)
        }
    }
}

object Notifications {
    private const val CHANNEL = "transcribed"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Notes transcribed later", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "When notes recorded without a connection have been transcribed"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun transcribed(context: Context, count: Int) {
        if (count == 0) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (count == 1) "1 note is transcribed" else "$count notes are transcribed"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText("Tap to read, copy or share them")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
    }
}
