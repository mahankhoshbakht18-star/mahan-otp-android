package ir.smsforwardmanager.data.remote.model

data class AppVersionDto(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String,
    val apkSha256: String,
    val packageName: String = "ir.mahanvip.otp",
    val certificateSha256: String = "aa151db817e67e44f31840b3deb0e5b146723d0586097dd1096dda33c3f20259",
    val publishedAt: String = "",
    val updatePriority: String = "normal"
) {
    fun hasUpdate(installed: Int) = versionCode > installed
    fun isMandatory(installed: Int) = hasUpdate(installed) && installed < minSupportedVersionCode
    fun validate(): AppVersionDto {
        require(versionCode > 0 && minSupportedVersionCode in 1..versionCode)
        require(versionName.isNotBlank() && versionName.length <= 50)
        require(releaseNotes.length <= 4000)
        require(Regex("[a-fA-F0-9]{64}").matches(apkSha256))
        require(Regex("[a-fA-F0-9]{64}").matches(certificateSha256))
        require(packageName in setOf("com.example.codresan", "ir.mahanvip.otp"))
        require(updatePriority in setOf("normal", "critical") && publishedAt.length <= 50)
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
