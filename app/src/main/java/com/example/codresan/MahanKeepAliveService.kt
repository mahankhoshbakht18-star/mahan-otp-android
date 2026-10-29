package com.example.codresan

import android.app.Notification
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

/**
 * سرویس پیش‌زمینه جهت نگه‌داشتن همیشگی برنامه در وضعیت آماده‌باش
 * و جلوگیری از توقف خودکار توسط مدیریت باتری یا سیستم‌عامل.
 */
class MahanKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SecureStore(this)
        if (!store.settings().active) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildKeepAliveNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Android 14 security fallback if background start restriction hits
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "آماده‌باش هوشمند ماهان",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش آماده‌باش دائم سامانه برای دریافت و ارسال خودکار پیامک‌ها"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildKeepAliveNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("پیامک خودکار ماهان فعال است")
            .setContentText("سامانه در حال آماده‌باش کامل جهت دریافت و ارسال پیامک‌ها است")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "mahan_keep_alive_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                val store = SecureStore(context)
                if (!store.settings().active) return
                val intent = Intent(context, MahanKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, MahanKeepAliveService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
