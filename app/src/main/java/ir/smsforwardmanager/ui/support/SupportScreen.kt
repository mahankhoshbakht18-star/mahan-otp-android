package ir.smsforwardmanager.ui.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.codresan.BuildConfig
import com.example.codresan.DeliveryWorker
import com.example.codresan.OtpApi
import com.example.codresan.R
import com.example.codresan.SecureStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** A lightweight sheet; never reads the SMS inbox or includes identifiers in diagnostics. */
class SupportScreen(private val activity: AppCompatActivity) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dialog = BottomSheetDialog(activity)
    private lateinit var diagnostic: TextView
    private lateinit var copyButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private var report = ""
    private var checking = false
    private var closed = false
    private fun dp(value: Int) = (activity.resources.displayMetrics.density * value).toInt()
    private fun str(id: Int) = activity.getString(id)
    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    private fun label(value: String, heading: Boolean = false) = TextView(activity).apply {
        text = value; textSize = if (heading) 17f else 14f
        setTextColor(ContextCompat.getColor(activity, if (heading) R.color.text_primary else R.color.text_secondary))
        if (heading) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8)); setLineSpacing(dp(4).toFloat(), 1.1f)
    }
    private fun card(parent: LinearLayout, title: Int): LinearLayout {
        val content = column().apply { setPadding(dp(16), dp(8), dp(16), dp(12)); addView(label(str(title), true)) }
        val card = MaterialCardView(activity).apply {
            radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(activity, R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.surface_card)); addView(content)
        }
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return content
    }
    private fun button(parent: LinearLayout, title: Int, icon: Int? = null, action: () -> Unit): MaterialButton {
        val button = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = str(title); isAllCaps = false; textSize = 13f; cornerRadius = dp(14); maxLines = 3
            minHeight = dp(52); setPadding(dp(12), dp(8), dp(12), dp(8))
            if (icon != null) { setIconResource(icon); iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START }
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2))
        return button
    }
    fun show() {
        val root = column().apply {
            setPadding(dp(20), dp(16), dp(20), dp(28))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_background))
            addView(label(str(R.string.support_title), true)); addView(label(str(R.string.support_subtitle)))
        }
        val contact = card(root, R.string.support_contact)
        val handle = BuildConfig.SUPPORT_TELEGRAM.removePrefix("https://t.me/").removePrefix("@")
        val telegramValid = Regex("[A-Za-z][A-Za-z0-9_]{4,31}").matches(handle)
        val phone = BuildConfig.SUPPORT_PHONE
        val phoneValid = Regex("\\+?[0-9]{7,15}").matches(phone)
        if (telegramValid) button(contact, R.string.support_telegram, R.drawable.ic_telegram) { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$handle"))) }
        if (phoneValid) {
            button(contact, R.string.support_call, R.drawable.ic_support) { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
            button(contact, R.string.support_copy_phone, R.drawable.ic_copy) { copy(str(R.string.support_phone_clip), phone, R.string.support_phone_copied) }
        }
        if (!telegramValid && !phoneValid) contact.addView(label(str(R.string.support_contact_unset)))
        contact.addView(label(if (BuildConfig.SUPPORT_HOURS.isBlank()) str(R.string.support_hours_unset)
            else activity.getString(R.string.support_hours, BuildConfig.SUPPORT_HOURS)))
        val faq = card(root, R.string.support_faq)
        listOf(R.string.faq_delivery_question to R.string.faq_delivery_answer,
            R.string.faq_privacy_question to R.string.faq_privacy_answer,
            R.string.faq_sim_question to R.string.faq_sim_answer).forEach { (question, answer) ->
            val answerView = label(str(answer)).apply { visibility = View.GONE }
            button(faq, question, R.drawable.ic_arrow) {
                answerView.visibility = if (answerView.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            faq.addView(answerView)
        }
        val details = card(root, R.string.diagnostic_title)
        diagnostic = label(str(R.string.diagnostic_checking)).apply { setTextIsSelectable(true) }
        details.addView(diagnostic); details.addView(label(str(R.string.diagnostic_private_notice)))
        copyButton = button(details, R.string.diagnostic_copy, R.drawable.ic_copy) {
            if (report.isNotBlank()) copy(str(R.string.support_report_clip), report, R.string.diagnostic_copied)
        }.apply { isEnabled = false }
        refreshButton = button(details, R.string.diagnostic_refresh, R.drawable.ic_refresh) { refresh() }
        button(details, R.string.support_permissions, R.drawable.ic_shield) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
        button(details, R.string.support_battery_settings, R.drawable.ic_power) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        button(root, R.string.support_close) { dialog.dismiss() }
        dialog.setContentView(ScrollView(activity).apply { addView(root) })
        dialog.setOnDismissListener { closed = true; io.shutdownNow(); main.removeCallbacksAndMessages(null) }
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.layoutParams?.height =
            (activity.resources.displayMetrics.heightPixels * 0.9f).toInt()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        refresh()
    }
    private fun refresh() {
        if (checking || closed) return
        checking = true; report = ""; diagnostic.text = str(R.string.diagnostic_checking)
        refreshButton.isEnabled = false; copyButton.isEnabled = false
        io.execute {
            val settings = runCatching { SecureStore(activity).settings() }.getOrNull()
            val online = settings != null && OtpApi().check(settings)
            val checked = System.currentTimeMillis()
            main.post {
                if (closed || activity.isFinishing || activity.isDestroyed) return@post
                fun yes(value: Boolean) = str(if (value) R.string.diagnostic_yes else R.string.diagnostic_no)
                val battery = (activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(activity.packageName)
                report = listOf(str(R.string.support_report_clip),
                    activity.getString(R.string.diagnostic_model, "${Build.MANUFACTURER} ${Build.MODEL}"),
                    activity.getString(R.string.diagnostic_android, Build.VERSION.RELEASE),
                    activity.getString(R.string.diagnostic_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                    activity.getString(R.string.diagnostic_server, str(if (online) R.string.diagnostic_online else R.string.diagnostic_offline)),
                    activity.getString(R.string.diagnostic_sms, yes(DeliveryWorker.hasSmsPermission(activity))),
                    activity.getString(R.string.diagnostic_battery, yes(battery)),
                    activity.getString(R.string.diagnostic_active, yes(settings?.active == true && settings.consent)),
                    activity.getString(R.string.diagnostic_checked_at,
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.forLanguageTag("fa-IR")).format(Date(checked)))
                ).joinToString("\n")
                diagnostic.text = report; checking = false; refreshButton.isEnabled = true; copyButton.isEnabled = true
            }
        }
    }
    private fun copy(title: String, value: String, message: Int) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, value))
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
    private fun open(intent: Intent) {
        try { activity.startActivity(intent) }
        catch (_: Exception) { Toast.makeText(activity, R.string.link_open_failed, Toast.LENGTH_LONG).show() }
    }
}
