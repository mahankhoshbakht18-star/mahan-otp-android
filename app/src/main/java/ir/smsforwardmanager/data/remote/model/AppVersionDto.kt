package ir.smsforwardmanager.data.remote.model

data class AppVersionDto(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkSha256: String
) {
    fun hasUpdate(installed: Int) = versionCode > installed
    fun isMandatory(installed: Int) = hasUpdate(installed) && installed < minSupportedVersionCode
    fun validate(): AppVersionDto {
        require(versionCode > 0 && minSupportedVersionCode in 1..versionCode)
        require(versionName.isNotBlank() && versionName.length <= 50)
        require(releaseNotes.length <= 4000)
        require(Regex("[a-fA-F0-9]{64}").matches(apkSha256))
        require(trustedDownloadUrl(downloadUrl))
        return this
    }
    companion object {
        fun trustedDownloadUrl(value: String): Boolean = try {
            val uri = java.net.URI(value)
            uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.fragment == null &&
                uri.query == null && uri.host == "otp.mahanvip.ir" &&
                Regex("/downloads/[A-Za-z0-9._-]+\\.apk").matches(uri.path) && !uri.path.contains("..")
        } catch (_: Exception) { false }
    }
}
