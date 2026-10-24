package ir.smsforwardmanager.ui.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.codresan.BuildConfig
import ir.smsforwardmanager.data.remote.model.AppVersionDto
import java.io.File
import java.security.MessageDigest

object ApkIdentityVerifier {
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File, version: AppVersionDto) {
        val pm = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag) ?: throw UpdateFailure("INVALID_FILE")
        val installed = pm.getPackageInfo(context.packageName, flag)
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { signature -> MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        DownloadPolicy.verifyIdentity(archive.packageName, context.packageName, code, version.versionCode,
            BuildConfig.VERSION_CODE, signers(archive), signers(installed), version.certificateSha256)
        val hash = apk.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!hash.equals(version.apkSha256, true)) throw UpdateFailure("INVALID_FILE")
    }
}
