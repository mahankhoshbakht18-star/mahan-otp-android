package com.example.codresan

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeliveryOutcome(val state: String, val message: String, val retry: Boolean = false)
data class OtpTarget(val nid: String, val requestId: String, val senderHash: String, val waitingSince: Long)
class TargetException(val outcome: DeliveryOutcome) : Exception()

object DeliveryPolicy {
    fun http(code: Int): DeliveryOutcome = when (code) {
        401, 403 -> DeliveryOutcome("failed", "کلید دستگاه معتبر نیست؛ تنظیمات را بررسی کنید")
        404 -> DeliveryOutcome("failed", "متقاضی یا مسیر امن روی سرور پیدا نشد")
        409 -> DeliveryOutcome("failed", "متقاضی در انتظار کد نیست؛ ارسال انجام نشد")
        410 -> DeliveryOutcome("expired", "سرور کد را منقضی تشخیص داد")
        408, 425, 429 -> DeliveryOutcome("retry", "سرور موقتاً آماده نیست؛ تلاش دوباره", true)
        in 500..599 -> DeliveryOutcome("retry", "سرور در دسترس نیست؛ تلاش دوباره", true)
        else -> DeliveryOutcome("failed", "درخواست پذیرفته نشد (خطای $code)")
    }
}

class OtpApi(private val client: OkHttpClient = client()) {
    fun deliver(settings: AppSettings, record: OtpRecord, canceled: () -> Boolean = { false }): DeliveryOutcome {
        if (!validEndpoint(settings.endpoint) || settings.token.isBlank()) return DeliveryOutcome("failed", "آدرس امن یا کلید دستگاه تنظیم نشده است")
        val payload = JSONObject().put("device_id", settings.deviceId).put("message_id", record.id)
            .put("national_id", record.nid).put("otp", record.code).put("received_at", record.received / 1000.0)
            .put("source", "android").put("consent_version", "1").put("request_id", record.requestId)
        val request = Request.Builder().url(settings.endpoint)
            .header("X-DEVICE-KEY", settings.token).header("X-DEVICE-ID", settings.deviceId)
            .header("Idempotency-Key", record.id).header("Cache-Control", "no-store")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return try {
            if (canceled()) return DeliveryOutcome("canceled", "ارسال متوقف شد")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return DeliveryPolicy.http(response.code)
                val body = response.body?.source()?.apply { request(4097) }
                if (body == null || body.buffer.size > 4096) return DeliveryOutcome("failed", "پاسخ سرور معتبر نیست")
                val data = JSONObject(body.readUtf8())
                when {
                    data.optString("status") != "ok" -> DeliveryOutcome("failed", "پاسخ سرور معتبر نیست")
                    data.optBoolean("duplicate") -> DeliveryOutcome("duplicate", "این کد قبلاً به سرور تحویل شده است")
                    data.optBoolean("accepted") -> DeliveryOutcome("sent", "کد با موفقیت به بات تحویل شد")
                    else -> DeliveryOutcome("failed", "تحویل کد توسط سرور تأیید نشد")
                }
            }
        } catch (_: IOException) { DeliveryOutcome("retry", "اتصال برقرار نشد؛ تلاش دوباره", true) }
        catch (_: Exception) { DeliveryOutcome("failed", "پاسخ یا تنظیمات نامعتبر است") }
    }
    fun targets(settings: AppSettings): List<OtpTarget> {
        if (!validEndpoint(settings.endpoint) || settings.token.isBlank()) throw TargetException(DeliveryOutcome("failed", "تنظیمات اتصال کامل نیست"))
        val url = settings.endpoint.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("targets").build()
        try {
            client.newCall(Request.Builder().url(url).header("X-DEVICE-KEY", settings.token)
                .header("X-DEVICE-ID", settings.deviceId).header("Cache-Control", "no-store").build()).execute().use {
                if (!it.isSuccessful) throw TargetException(DeliveryPolicy.http(it.code))
                val data = JSONObject(it.body?.string().orEmpty())
                if (data.optString("status") != "ok") throw TargetException(DeliveryOutcome("failed", "پاسخ مقصد معتبر نیست"))
                val items = data.getJSONArray("targets")
                return (0 until items.length()).map { index -> val j = items.getJSONObject(index)
                    OtpTarget(j.getString("national_id"), j.getString("job_id"), j.optString("sender_hash"), (j.getDouble("waiting_since") * 1000).toLong()) }
            }
        } catch (e: TargetException) { throw e }
        catch (_: IOException) { throw TargetException(DeliveryOutcome("retry", "ارتباط با سرور برقرار نشد", true)) }
        catch (_: Exception) { throw TargetException(DeliveryOutcome("failed", "فهرست متقاضیان معتبر نیست")) }
    }
    fun check(settings: AppSettings): Boolean = try { targets(settings); true } catch (_: Exception) { false }
    companion object {
        fun validEndpoint(value: String): Boolean {
            val url = value.toHttpUrlOrNull() ?: return false
            return url.isHttps && url.username.isBlank() && url.password.isBlank() && url.query == null &&
                url.fragment == null && url.encodedPath.endsWith("/api/v1/sms/otp")
        }
        fun client(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
