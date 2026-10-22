package ir.smsforwardmanager.ui.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.codresan.BuildConfig
import com.example.codresan.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.smsforwardmanager.data.remote.model.AppVersionDto

object UpdateDialog {
    fun show(activity: AppCompatActivity, version: AppVersionDto, onRequiredExit: () -> Unit) {
        version.validate()
        val required = version.isMandatory(BuildConfig.VERSION_CODE)
        val message = activity.getString(if (required) R.string.update_required_body else R.string.update_optional_body,
            version.versionName) + "\n\n" + version.releaseNotes + "\n\n" + activity.getString(R.string.update_install_help)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (required) R.string.update_required_title else R.string.update_optional_title)
            .setMessage(message).setCancelable(!required)
            .setPositiveButton(R.string.update_download, null)
            .setNegativeButton(if (required) R.string.update_exit else R.string.update_later) { _, _ ->
                if (required) onRequiredExit()
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(version.downloadUrl)))
                    // Optional updates may be dismissed; required updates stay visible until rechecked.
                    if (!required) dialog.dismiss()
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.link_open_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
}
