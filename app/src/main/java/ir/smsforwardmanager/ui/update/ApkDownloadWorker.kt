package ir.smsforwardmanager.ui.update

import android.content.Context
import android.os.StatFs
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.codresan.SecureStore
import ir.smsforwardmanager.data.remote.AppVersionApi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ApkDownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        var partial: File? = null
        return try {
            val version = AppVersionApi.decode(inputData.getString("metadata").orEmpty()).validate()
            val directory = File(applicationContext.cacheDir, "verified-updates").apply { check(exists() || mkdirs()) }
            val path = File(directory, "${version.apkSha256.lowercase()}.apk")
            partial = File(directory, "${version.apkSha256.lowercase()}.$id.part")
            val settings = SecureStore(applicationContext).settings()
            val request = Request.Builder().url(version.downloadUrl).header("X-DEVICE-KEY", settings.token)
                .header("X-DEVICE-ID", settings.deviceId).header("Cache-Control", "no-cache").build()
            val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateFailure(if (response.code == 404 || response.code == 410) "WITHDRAWN" else "NETWORK")
                val body = response.body ?: throw UpdateFailure("INVALID_FILE")
                val length = body.contentLength()
                if (StatFs(directory.path).availableBytes < maxOf(length, 10 * 1024 * 1024L) + 10 * 1024 * 1024)
                    throw UpdateFailure("NO_SPACE")
                var lastProgress = -1
                body.byteStream().use { input -> partial!!.outputStream().use { output ->
                    DownloadPolicy.copy(input, output, version.apkSha256, length, { isStopped }) { received, total ->
                        val progress = if (total > 0) (received * 100 / total).toInt() else -1
                        if (progress != lastProgress) {
                            lastProgress = progress
                            setProgressAsync(workDataOf("percent" to progress, "bytes" to received))
                        }
                    }
                } }
            }
            if (isStopped) throw UpdateFailure("CANCELED")
            ApkIdentityVerifier.verify(applicationContext, partial!!, version)
            if (path.exists() && !path.delete()) throw UpdateFailure("NO_SPACE")
            if (!partial!!.renameTo(path)) throw UpdateFailure("NO_SPACE")
            Result.success(workDataOf("file" to path.absolutePath))
        } catch (e: UpdateFailure) { Result.failure(workDataOf("error" to e.reason)) }
        catch (e: java.io.IOException) {
            val noSpace = e.message.orEmpty().contains("ENOSPC") || StatFs(applicationContext.cacheDir.path).availableBytes < 2 * 1024 * 1024
            Result.failure(workDataOf("error" to if (noSpace) "NO_SPACE" else "NETWORK"))
        }
        catch (_: Exception) { Result.failure(workDataOf("error" to "INVALID_FILE")) }
        finally { partial?.delete() }
    }
}
