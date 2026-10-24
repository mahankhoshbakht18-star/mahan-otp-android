package ir.smsforwardmanager.data.remote

import com.example.codresan.OtpApi
import com.example.codresan.SecureStore
import android.content.Context
import ir.smsforwardmanager.data.remote.model.AppVersionDto
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class AppVersionApi(private val context: Context? = null) {
    fun latest(): AppVersionDto {
        val settings = context?.let { SecureStore(it).settings() }
        if (settings != null && settings.token.isNotBlank()) {
            val authenticated = Request.Builder().url("https://otp.mahanvip.ir/api/v1/sms/app/version")
                .header("X-DEVICE-KEY", settings.token).header("X-DEVICE-ID", settings.deviceId)
                .header("Cache-Control", "no-cache").build()
            OtpApi.client().newCall(authenticated).execute().use { response ->
                if (response.isSuccessful) return read(response)
                if (response.code != 404) throw IOException("Version service unavailable")
            }
        }
        // Public metadata request: never includes SMS credentials or personal data.
        val request = Request.Builder().url("https://otp.mahanvip.ir/app/version.json")
            .header("Cache-Control", "no-cache").build()
        return OtpApi.client().newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Version service unavailable")
            read(it)
        }
    }
    private fun read(response: okhttp3.Response): AppVersionDto {
            val source = response.body?.source() ?: throw IOException("Empty version metadata")
            source.request(16385)
            if (source.buffer.size > 16384) throw IOException("Version metadata too large")
            return decode(source.readUtf8()).validate()
    }
    companion object {
        fun decode(raw: String): AppVersionDto {
            val j = JSONObject(raw)
            return AppVersionDto(j.getInt("version_code"), j.getString("version_name"),
                j.getInt("min_supported_version_code"), j.getString("download_url"),
                j.optString("release_notes"), j.getString("apk_sha256"),
                j.optString("package_name", "com.example.codresan"),
                j.optString("certificate_sha256", "aa151db817e67e44f31840b3deb0e5b146723d0586097dd1096dda33c3f20259"),
                j.optString("published_at"), j.optString("update_priority", "normal"))
        }
        fun encode(dto: AppVersionDto): String = JSONObject().put("version_code", dto.versionCode)
            .put("version_name", dto.versionName).put("min_supported_version_code", dto.minSupportedVersionCode)
            .put("download_url", dto.downloadUrl).put("release_notes", dto.releaseNotes)
            .put("apk_sha256", dto.apkSha256).toString()
            .let { JSONObject(it).put("package_name", dto.packageName).put("certificate_sha256", dto.certificateSha256)
                .put("published_at", dto.publishedAt).put("update_priority", dto.updatePriority).toString() }
    }
}
