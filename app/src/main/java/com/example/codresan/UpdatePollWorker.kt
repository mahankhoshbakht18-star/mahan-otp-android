package com.example.codresan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import ir.smsforwardmanager.data.repository.AppUpdateRepository
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateReports {
    fun report(context: Context, state: String = "heartbeat"): Boolean {
        val settings = SecureStore(context).settings()
        if (!settings.updateReports || settings.token.isBlank()) return false
        val prefs = context.getSharedPreferences("app_update_metadata", Context.MODE_PRIVATE)
        val previous = prefs.getInt("previous_version", 0)
        val pending = prefs.getInt("pending_install_version", 0)
        val firstRunAfterInstall = pending > 0 && BuildConfig.VERSION_CODE >= pending && previous < BuildConfig.VERSION_CODE
        val event = if (firstRunAfterInstall) "install_verified" else state
        val data = JSONObject().put("device_id", settings.deviceId).put("package_name", BuildConfig.APPLICATION_ID)
            .put("version_code", BuildConfig.VERSION_CODE).put("version_name", BuildConfig.VERSION_NAME)
            .put("reporting_consent", true).put("update_state", event).put("previous_version", previous)
            .put("update_notifications", settings.updateNotifications && NotificationManagerCompat.from(context).areNotificationsEnabled())
        return try {
            val request = Request.Builder().url("https://otp.mahanvip.ir/api/v1/sms/app/report")
                .header("X-DEVICE-KEY", settings.token).header("X-DEVICE-ID", settings.deviceId)
                .post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            OtpApi.client().newCall(request).execute().use {
                if (it.isSuccessful && firstRunAfterInstall) prefs.edit().remove("pending_install_version").remove("previous_version").apply()
                it.isSuccessful
            }
        } catch (_: Exception) { false }
    }
}

class UpdatePollWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        return try {
            UpdateReports.report(applicationContext)
            val version = AppUpdateRepository(applicationContext).latest(force = true)
            val settings = SecureStore(applicationContext).settings()
            if (version.isMandatory(BuildConfig.VERSION_CODE)) {
                val store = SecureStore(applicationContext)
                store.saveSettings(store.settings().copy(active = false))
                DeliveryWorker.cancel(applicationContext)
            }
            if (!version.hasUpdate(BuildConfig.VERSION_CODE)) return Result.success()
            val prefs = applicationContext.getSharedPreferences("app_update_metadata", Context.MODE_PRIVATE)
            if (!settings.updateNotifications || !NotificationManagerCompat.from(applicationContext).areNotificationsEnabled() ||
                prefs.getInt("notified_version", 0) >= version.versionCode) return Result.success()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel("app_updates", applicationContext.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
                if (manager.getNotificationChannel("app_updates").importance == NotificationManager.IMPORTANCE_NONE) return Result.success()
            }
            val intent = PendingIntent.getActivity(applicationContext, 44,
                Intent(applicationContext, MainActivity::class.java).putExtra("check_update", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(44, NotificationCompat.Builder(applicationContext, "app_updates").setSmallIcon(R.drawable.ic_cloud)
                .setContentTitle(applicationContext.getString(R.string.update_notification_title))
                .setContentText(applicationContext.getString(R.string.update_notification_body, version.versionName))
                .setContentIntent(intent).setAutoCancel(true).build())
            prefs.edit().putInt("notified_version", version.versionCode).apply()
            UpdateReports.report(applicationContext, "notification_shown")
            Result.success()
        } catch (_: Exception) { if (runAttemptCount >= 3) Result.success() else Result.retry() }
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("mahan-update-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdatePollWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
