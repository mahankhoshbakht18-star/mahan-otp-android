package ir.smsforwardmanager.ui.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.work.*
import com.example.codresan.R
import ir.smsforwardmanager.data.remote.AppVersionApi
import ir.smsforwardmanager.data.remote.model.AppVersionDto
import java.io.File
import java.util.UUID

class UpdateInstallActivity : AppCompatActivity() {
    private lateinit var version: AppVersionDto
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var install: com.google.android.material.button.MaterialButton
    private var file: File? = null
    private var workId: UUID? = null
    private val verifier = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status.setText(if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) R.string.install_permission_ready else R.string.install_permission_denied)
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Some installers do not return a reliable result: never claim success from RESULT_OK alone.
        status.setText(if (it.resultCode == Activity.RESULT_CANCELED) R.string.install_not_completed else R.string.install_check_after_launch)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        version = try { AppVersionApi.decode(intent.getStringExtra("metadata").orEmpty()).validate() }
        catch (_: Exception) { finish(); return }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(padding, padding * 2, padding, padding)
        }
        layout.addView(TextView(this).apply { text = getString(R.string.install_title, version.versionName); textSize = 22f })
        status = TextView(this).apply { setText(R.string.download_in_progress); textSize = 15f; setPadding(0, padding, 0, padding) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        layout.addView(status); layout.addView(progress, LinearLayout.LayoutParams(-1, padding))
        fun button(textId: Int, click: () -> Unit) = com.google.android.material.button.MaterialButton(this).apply {
            setText(textId); isAllCaps = false; setOnClickListener { click() }; layout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        install = button(R.string.install_verified_apk) { install() }.apply { isEnabled = false }
        button(R.string.download_retry) { startDownload() }
        button(R.string.download_cancel) {
            workId?.let { WorkManager.getInstance(this).cancelWorkById(it) }; finish()
        }
        setContentView(layout)
        workId = state?.getString("work")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (workId == null) startDownload() else observe()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("work", workId?.toString()); super.onSaveInstanceState(out) }
    override fun onDestroy() { verifier.shutdownNow(); super.onDestroy() }
    private fun startDownload() {
        workId?.let { WorkManager.getInstance(this).cancelWorkById(it) }
        file = null; install.isEnabled = false; status.setText(R.string.download_in_progress)
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(workDataOf("metadata" to AppVersionApi.encode(version.copy(releaseNotes = ""))))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        workId = request.id; WorkManager.getInstance(this).enqueue(request); observe()
    }
    private fun observe() {
        val observed = workId ?: return
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(observed).observe(this) callback@{ info ->
            if (info == null || observed != workId) return@callback
            val percentage = info.progress.getInt("percent", -1)
            progress.isIndeterminate = percentage < 0 && !info.state.isFinished
            if (percentage >= 0) progress.progress = percentage
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    file = info.outputData.getString("file")?.let(::File)
                    install.isEnabled = file?.exists() == true; progress.progress = 100
                    status.setText(R.string.download_verified)
                }
                WorkInfo.State.FAILED -> status.setText(errorMessage(info.outputData.getString("error")))
                WorkInfo.State.CANCELLED -> status.setText(R.string.download_canceled)
                else -> Unit
            }
        }
    }
    private fun errorMessage(reason: String?) = when (reason) {
        "NO_SPACE" -> R.string.download_no_space
        "WRONG_SIGNATURE" -> R.string.download_bad_signature
        "WRONG_VERSION" -> R.string.download_bad_version
        "WITHDRAWN" -> R.string.download_withdrawn
        "INVALID_FILE" -> R.string.download_invalid
        else -> R.string.download_network_error
    }
    private fun install() {
        val apk = file ?: return
        install.isEnabled = false; status.setText(R.string.download_in_progress)
        verifier.execute {
            val failure = try {
                val current = AppVersionApi(this).latest()
                if (current.versionCode != version.versionCode || current.apkSha256 != version.apkSha256) throw UpdateFailure("WITHDRAWN")
                ApkIdentityVerifier.verify(this, apk, version); null
            } catch (e: UpdateFailure) { e.reason } catch (_: Exception) { "NETWORK" }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                install.isEnabled = true
                if (failure != null) status.setText(errorMessage(failure)) else openInstaller(apk)
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun openInstaller(apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                status.setText(R.string.install_permission_help)
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            getSharedPreferences("app_update_metadata", MODE_PRIVATE).edit()
                .putInt("pending_install_version", version.versionCode).putInt("previous_version", com.example.codresan.BuildConfig.VERSION_CODE).apply()
            val uri = FileProvider.getUriForFile(this, "$packageName.updatefiles", apk)
            installer.launch(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true))
        } catch (e: UpdateFailure) { status.setText(errorMessage(e.reason)) }
        catch (_: Exception) { status.setText(R.string.install_launch_failed) }
    }
}
