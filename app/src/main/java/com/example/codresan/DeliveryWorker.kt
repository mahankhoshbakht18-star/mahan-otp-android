package com.example.codresan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class DeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result { return try {
        val store = SecureStore(applicationContext)
        val id = inputData.getString("record") ?: return Result.failure()
        val settings = store.settings()
        var record = store.records().find { it.id == id } ?: return Result.success()
        if (!settings.active || !settings.consent || !hasSmsPermission(applicationContext) ||
            record.state in setOf("sent", "duplicate", "expired", "canceled")) return Result.success()
        if (System.currentTimeMillis() >= record.expires || record.code.isEmpty()) {
            store.update(id) { it.copy(code = "", state = "expired", detail = "کد منقضی شده است") }; return Result.success()
        }
        if (runAttemptCount >= settings.maxAttempts) {
            store.update(id) { it.copy(state = "failed", detail = "سقف تلاش‌ها تکمیل شد؛ اتصال و تنظیمات را بررسی کنید") }
            return Result.success()
        }
        store.update(id) { it.copy(state = "sending", attempts = it.attempts + 1, detail = "در حال ارسال امن…") }
        announce(applicationContext)
        val api = OtpApi()
        val result = try {
            if (record.requestId.isNotBlank() && record.nid.isNotBlank()) {
                // A lost acknowledgement may be retried after the bot consumed the code.
                // The relay confirms duplicates without delivering the message again.
                api.deliver(settings, record) { isStopped || !store.settings().active || store.records().none { it.id == id } }
            } else {
            val targets = api.targets(settings)
            val target = if (record.nid.isNotBlank()) targets.singleOrNull { it.nid == record.nid }
                else targets.filter { record.senderHash.isNotBlank() && it.senderHash == record.senderHash }.singleOrNull()
                    ?: targets.singleOrNull()
            when {
                target == null && targets.size > 1 && record.nid.isBlank() -> DeliveryOutcome("choose_target", "چند متقاضی منتظرند؛ مقصد کد را انتخاب کنید")
                target == null -> DeliveryOutcome("retry", "متقاضی هنوز در بات منتظر کد نیست", true)
                record.requestId.isNotBlank() && record.requestId != target.requestId -> DeliveryOutcome("expired", "درخواست قبلی پایان یافته؛ کد جدید لازم است")
                record.received + 5000 < target.waitingSince -> DeliveryOutcome("expired", "این کد پیش از درخواست جاری دریافت شده است")
                else -> {
                    record = record.copy(nid = target.nid, requestId = target.requestId)
                    store.update(id) { it.copy(nid = target.nid, requestId = target.requestId) }
                    api.deliver(settings, record) { isStopped || !store.settings().active || store.records().none { it.id == id } }
                }
            }
            }
        } catch (e: TargetException) { e.outcome }
        if (isStopped) return Result.success()
        val retry = result.retry && runAttemptCount + 1 < settings.maxAttempts && System.currentTimeMillis() < record.expires
        store.update(id) { current ->
            if (current.state == "canceled") current else current.copy(
                state = if (result.retry && !retry) "failed" else result.state,
                detail = result.message, code = if (result.state == "expired") "" else current.code)
        }
        if (result.state in setOf("sent", "duplicate")) store.connected()
        announce(applicationContext)
        if (!retry) notifyResult(applicationContext, settings, result)
        if (retry) Result.retry() else Result.success()
    } catch (_: Exception) { Result.failure() } }

    companion object {
        const val TAG = "mahan-otp"
        const val UPDATE = "com.example.codresan.RECORDS_CHANGED"
        fun hasSmsPermission(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        fun enqueue(context: Context, id: String, manual: Boolean = false) {
            val settings = SecureStore(context).settings()
            val work = OneTimeWorkRequestBuilder<DeliveryWorker>().setInputData(workDataOf("record" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, settings.backoffSeconds.toLong(), TimeUnit.SECONDS)
                .addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork("otp-$id", if (manual) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, work)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            val store = SecureStore(context)
            store.records().filter { it.state in setOf("queued", "sending", "retry", "failed", "choose_target") }.forEach { r ->
                store.update(r.id) { it.copy(state = "canceled", detail = "به درخواست شما متوقف شد") }
            }
            announce(context)
        }
        fun announce(c: Context) { c.sendBroadcast(Intent(UPDATE).setPackage(c.packageName)) }
        private fun notifyResult(c: Context, s: AppSettings, result: DeliveryOutcome) {
            if (!s.notifications || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
            val manager = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("delivery", "نتیجه تحویل کد", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).putExtra("history", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(20, NotificationCompat.Builder(c, "delivery").setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("پیامک ماهان").setContentText(result.message).setContentIntent(intent)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        }
    }
}

class CleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try { SecureStore(applicationContext).records(); Result.success() } catch (_: Exception) { Result.failure() }
}
