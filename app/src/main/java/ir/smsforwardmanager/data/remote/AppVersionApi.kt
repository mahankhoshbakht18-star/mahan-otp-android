package ir.smsforwardmanager.data.remote

import com.example.codresan.OtpApi
import ir.smsforwardmanager.data.remote.model.AppVersionDto
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class AppVersionApi {
    fun latest(): AppVersionDto {
        // Public metadata request: never includes SMS credentials or personal data.
        val request = Request.Builder().url("https://otp.mahanvip.ir/app/version.json")
            .header("Cache-Control", "no-cache").build()
        return OtpApi.client().newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("Version service unavailable")
            val source = it.body?.source() ?: throw IOException("Empty version metadata")
            source.request(16385)
            if (source.buffer.size > 16384) throw IOException("Version metadata too large")
            decode(source.readUtf8()).validate()
        }
    }
    companion object {
        fun decode(raw: String): AppVersionDto {
            val j = JSONObject(raw)
            return AppVersionDto(j.getInt("version_code"), j.getString("version_name"),
                j.getInt("min_supported_version_code"), j.getString("download_url"),
                j.optString("release_notes"), j.getString("apk_sha256"))
        }
        fun encode(dto: AppVersionDto): String = JSONObject().put("version_code", dto.versionCode)
            .put("version_name", dto.versionName).put("min_supported_version_code", dto.minSupportedVersionCode)
            .put("download_url", dto.downloadUrl).put("release_notes", dto.releaseNotes)
            .put("apk_sha256", dto.apkSha256).toString()
    }
}
