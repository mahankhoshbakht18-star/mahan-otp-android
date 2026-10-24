package com.example.codresan

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import ir.smsforwardmanager.data.remote.model.AppVersionDto
import ir.smsforwardmanager.data.repository.AppUpdateRepository
import ir.smsforwardmanager.domain.CheckAppUpdateUseCase
import ir.smsforwardmanager.ui.support.SupportScreen
import ir.smsforwardmanager.ui.update.UpdateDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Original single-page interface, with small settings and inline retry controls. */
class MainActivity : AppCompatActivity() {
    private lateinit var store: SecureStore
    private lateinit var nid1: EditText
    private lateinit var nid2: EditText
    private lateinit var rows: LinearLayout
    private lateinit var logScroll: ScrollView
    private val io = Executors.newSingleThreadExecutor()
    private val events = mutableListOf<Pair<Long, String>>()
    private var registered = false
    private var checking = false
    private var connected: Boolean? = null
    private var activateAfterPermission = false
    private var updateChecking = false
    private var requiredUpdate: AppVersionDto? = null
    private var updateDialogShown = false
    private var requiredDialog: androidx.appcompat.app.AlertDialog? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshLogs() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = SecureStore(this)
        try {
            BundledConnection.provision(this, store)
            migrateOldSettings()
            store.records()
        } catch (_: Exception) {
            MaterialAlertDialogBuilder(this).setTitle("اطلاعات برنامه قابل دسترسی نیست")
                .setMessage("برای بازیابی اطلاعات با پشتیبانی تماس بگیرید. ارسال متوقف شده است.")
                .setCancelable(false).setPositiveButton("بستن") { _, _ -> finish() }.show()
            return
        }
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom)); insets
            }
            ViewCompat.requestApplyInsets(root)
        }
        nid1 = findViewById(R.id.etNidSim1); nid2 = findViewById(R.id.etNidSim2)
        rows = findViewById(R.id.logRows); logScroll = findViewById(R.id.scrollLog)
        requiredUpdate = AppUpdateRepository(this).requiredCached(BuildConfig.VERSION_CODE)
        if (requiredUpdate != null) {
            store.saveSettings(store.settings().copy(active = false)); DeliveryWorker.cancel(this)
        }
        val settings = store.settings()
        nid1.setText(settings.nid1); nid2.setText(settings.nid2)
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener { settingsDialog() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveAndActivate() }
        findViewById<MaterialButton>(R.id.btnPermissions).setOnClickListener { discloseAndRequest(false) }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(DeliveryWorker.UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("mahan-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(15, TimeUnit.MINUTES).build())
        refreshStatus(); refreshLogs(); checkConnection(); checkUpdate()
        UpdatePollWorker.schedule(this)
        io.execute { UpdateReports.report(this, if (intent.getBooleanExtra("check_update", false)) "notification_opened" else "first_launch") }
    }

    override fun onResume() {
        super.onResume()
        if (::rows.isInitialized) { refreshStatus(); refreshLogs(); if (requiredUpdate != null) { showRequiredUpdate(); checkUpdate(force = true) } }
    }
    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        requiredDialog?.dismiss()
        io.shutdownNow(); super.onDestroy()
    }
    private fun migrateOldSettings() {
        val old = getSharedPreferences("MySettings", MODE_PRIVATE)
        if (!old.contains("nid_sim1") && !old.contains("service_active")) return
        store.saveSettings(store.settings().copy(active = false, consent = false,
            nid1 = old.getString("nid_sim1", "").orEmpty(), nid2 = old.getString("nid_sim2", "").orEmpty()))
        check(old.edit().clear().commit())
    }
    private fun saveAndActivate() {
        if (requiredUpdate != null) { showRequiredUpdate(); return }
        val a = LoanRules.digits(nid1.text.toString()).trim(); val b = LoanRules.digits(nid2.text.toString()).trim()
        nid1.error = null; nid2.error = null
        if (a.isNotBlank() && !LoanRules.validNid(a)) { nid1.error = "کد ملی معتبر و ده‌رقمی وارد کنید"; return }
        if (b.isNotBlank() && !LoanRules.validNid(b)) { nid2.error = "کد ملی معتبر و ده‌رقمی وارد کنید"; return }
        if (a.isNotBlank() && a == b) { nid2.error = "متقاضی دو سیم نباید تکراری باشد"; return }
        val s = store.settings()
        if (s.nid1 != a || s.nid2 != b) {
            store.saveSettings(s.copy(active = false, nid1 = a, nid2 = b)); DeliveryWorker.cancel(this)
        } else store.saveSettings(s.copy(nid1 = a, nid2 = b))
        if (s.token.isBlank() || !OtpApi.validEndpoint(s.endpoint)) {
            toast(getString(R.string.connection_bundled_missing))
            refreshStatus(); return
        }
        discloseAndRequest(true)
    }
    private fun discloseAndRequest(activate: Boolean) {
        val s = store.settings()
        if (!s.consent) {
            MaterialAlertDialogBuilder(this).setTitle("دسترسی دریافت پیامک")
                .setMessage("Android اجازه دریافت همه پیامک‌های جدید را درخواست می‌کند. ماهان روی گوشی فقط متن «کد تایید مربوط به سامانه ازدواج» را تشخیص می‌دهد؛ فوروارد همین متن از شماره دیگر هم پذیرفته می‌شود.\n\nفقط کد استخراج‌شده و اطلاعات لازم برای اتصال آن به متقاضی، به سرور ماهان ارسال می‌شود. متن کامل و سایر پیامک‌ها ارسال یا ذخیره نمی‌شوند. کدهای مجاز در گزارش همین گوشی نمایش داده می‌شوند. از تنظیمات می‌توانید ارسال را متوقف و رضایت را لغو کنید.")
                .setPositiveButton("موافقم") { _, _ -> store.saveSettings(store.settings().copy(consent = true)); requestPermission(activate) }
                .setNegativeButton("فعلاً نه", null).show()
        } else requestPermission(activate)
    }
    private fun requestPermission(activate: Boolean) {
        if (!DeliveryWorker.hasSmsPermission(this)) {
            activateAfterPermission = activate
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS), 41)
        } else if (activate) activate() else refreshStatus()
    }
    private fun activate() {
        if (requiredUpdate != null) { showRequiredUpdate(); return }
        val s = store.settings()
        if (s.token.isBlank() || !OtpApi.validEndpoint(s.endpoint) || !s.consent || !DeliveryWorker.hasSmsPermission(this)) return
        store.saveSettings(s.copy(active = true)); refreshStatus(); addLog("سامانه فعال شد و آماده دریافت کد تأیید است.")
        if (s.notifications) notificationPermission()
    }
    private fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, result: IntArray) {
        super.onRequestPermissionsResult(code, permissions, result)
        if (code != 41) return
        val start = activateAfterPermission; activateAfterPermission = false
        refreshStatus()
        if (DeliveryWorker.hasSmsPermission(this)) { if (start) activate() else addLog("دسترسی دریافت پیامک فعال شد.") }
        else toast("مجوز دریافت پیامک فعال نشد؛ در تنظیمات گوشی می‌توانید آن را تأیید کنید.")
    }
    private fun refreshStatus() {
        if (!::rows.isInitialized) return
        val s = store.settings(); val granted = DeliveryWorker.hasSmsPermission(this); val active = s.active && granted
        findViewById<TextView>(R.id.txtStatus).text = if (active) "سامانه فعال است" else "آماده راه‌اندازی"
        findViewById<TextView>(R.id.txtStatusDetail).text = if (active) "در انتظار پیامک کد تأیید" else "تنظیمات را ذخیره و سامانه را فعال کنید"
        findViewById<MaterialCardView>(R.id.statusCard).setCardBackgroundColor(color(if (active) R.color.status_card_active else R.color.status_card_idle))
        findViewById<TextView>(R.id.txtPermissionStatus).apply { text = if (granted) "فعال" else "نیاز به تأیید"; setTextColor(color(if (granted) R.color.status_success else R.color.status_warning)) }
        findViewById<MaterialButton>(R.id.btnPermissions).apply { text = if (granted) "دسترسی پیامک فعال است" else "فعال‌سازی دسترسی پیامک"; isEnabled = !granted }
    }
    private fun settingsDialog() {
        val s = store.settings()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(24), dp(4), dp(24), dp(8)) }
        fun toggle(title: String, value: Boolean) = SwitchMaterial(this).apply {
            text = title; textSize = 14f; isChecked = value; minHeight = dp(52); content.addView(this)
        }
        val active = toggle("ارسال خودکار پیامک", s.active)
        val notify = toggle("اعلان نتیجه ارسال", s.notifications)
        val showCodes = toggle("نمایش کدها در گزارش", s.showCodes)
        val typed = toggle(getString(R.string.typed_consent_toggle), s.typedSmsConsent)
        typed.setOnCheckedChangeListener { _, enabled ->
            if (enabled && !store.settings().typedSmsConsent) MaterialAlertDialogBuilder(this)
                .setTitle(R.string.typed_consent_title).setMessage(R.string.typed_consent_body)
                .setPositiveButton(R.string.typed_consent_accept) { _, _ -> store.saveSettings(store.settings().copy(typedSmsConsent = true)) }
                .setNegativeButton(R.string.typed_decline) { _, _ -> typed.isChecked = false }
                .setOnCancelListener { typed.isChecked = false }.show()
        }
        val updateNotify = toggle(getString(R.string.update_notifications_toggle), s.updateNotifications)
        val reports = toggle(getString(R.string.update_reporting_toggle), s.updateReports)
        reports.setOnCheckedChangeListener { _, enabled ->
            if (enabled && !store.settings().updateReports) MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_reporting_toggle).setMessage(R.string.update_reporting_body)
                .setPositiveButton(R.string.typed_consent_accept) { _, _ -> store.saveSettings(store.settings().copy(updateReports = true)) }
                .setNegativeButton(R.string.typed_decline) { _, _ -> reports.isChecked = false }
                .setOnCancelListener { reports.isChecked = false }.show()
        }
        fun smallButton(title: String, action: () -> Unit) {
            content.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = title; textSize = 13f; isAllCaps = false; setTextColor(color(R.color.primary)); setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        smallButton("بررسی اتصال به ماهان") { checkConnection(true) }
        smallButton(getString(R.string.support_title)) { SupportScreen(this).show() }
        smallButton(getString(R.string.support_update_button)) { checkUpdate(true) }
        smallButton("پاک کردن گزارش‌ها") {
            MaterialAlertDialogBuilder(this).setTitle("گزارش‌ها پاک شوند؟").setMessage("ارسال‌های در انتظار هم لغو می‌شوند.")
                .setPositiveButton("پاک کردن") { _, _ -> DeliveryWorker.cancel(this); store.delete(); events.clear(); refreshLogs() }
                .setNegativeButton("بازگشت", null).show()
        }
        smallButton("حریم خصوصی") { privacyDialog { active.isChecked = false } }
        MaterialAlertDialogBuilder(this).setTitle("تنظیمات")
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("ذخیره") { _, _ ->
                val current = store.settings()
                store.saveSettings(current.copy(notifications = notify.isChecked, showCodes = showCodes.isChecked,
                    typedSmsConsent = typed.isChecked, updateNotifications = updateNotify.isChecked, updateReports = reports.isChecked))
                if (updateNotify.isChecked) notificationPermission()
                if (!active.isChecked) {
                    store.saveSettings(store.settings().copy(active = false)); DeliveryWorker.cancel(this); refreshStatus(); refreshLogs()
                } else if (!current.active) saveAndActivate() else { refreshLogs(); if (notify.isChecked) notificationPermission() }
            }
            .setNegativeButton("بستن", null).show()
    }
    private fun privacyDialog(onRevoked: () -> Unit = {}) {
        MaterialAlertDialogBuilder(this).setTitle("حریم خصوصی")
            .setMessage(R.string.typed_privacy_body)
            .setPositiveButton("متوجه شدم", null)
            .setNeutralButton("لغو رضایت و توقف") { _, _ ->
                store.saveSettings(store.settings().copy(active = false, consent = false, typedSmsConsent = false, updateReports = false))
                DeliveryWorker.cancel(this); store.delete(); refreshStatus(); refreshLogs()
                onRevoked()
            }.show()
    }
    private fun checkConnection(showToast: Boolean = false) {
        if (checking) return
        val s = store.settings()
        if (s.token.isBlank()) {
            findViewById<TextView>(R.id.txtServerStatus).text = "نیاز به راه‌اندازی"
            if (showToast) toast(getString(R.string.connection_bundled_missing))
            return
        }
        checking = true; findViewById<TextView>(R.id.txtServerStatus).text = "در حال بررسی…"
        io.execute {
            val success = OtpApi().check(s)
            if (success) store.connected()
            runOnUiThread {
                checking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.txtServerStatus).apply {
                    text = if (success) "متصل و آماده" else "اتصال برقرار نیست"
                    setTextColor(color(if (success) R.color.status_success else R.color.status_warning))
                }
                if (connected != success) addLog(if (success) "اتصال به سرور ماهان برقرار شد." else "اتصال به سرور برقرار نیست؛ اینترنت را بررسی کنید.")
                connected = success
                refreshSummary()
                if (showToast) toast(if (success) "اتصال به ماهان برقرار است." else "اتصال برقرار نشد؛ اینترنت و تنظیمات اتصال را بررسی کنید.")
            }
        }
    }
    private fun refreshLogs() {
        if (!::rows.isInitialized || isFinishing || isDestroyed) return
        val list = store.records().take(40); val s = store.settings()
        rows.removeAllViews()
        fun text(value: String, size: Int = 12, muted: Boolean = false) = TextView(this).apply {
            text = value; textSize = size.toFloat(); setTextColor(if (muted) Color.rgb(150, 183, 171) else color(R.color.log_text))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; gravity = Gravity.START; setLineSpacing(dp(3).toFloat(), 1.1f)
        }
        val lines = events.map { Triple(it.first, it.second, null as OtpRecord?) } + list.map { Triple(it.received, "", it) }
        if (lines.isEmpty()) rows.addView(text(getString(R.string.no_activity)))
        lines.sortedBy { it.first }.forEach { (whenReceived, event, r) ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(9)) }
            if (r == null) line.addView(text("[${time(whenReceived)}] $event"))
            else {
                val origin = if (r.forwarded) "پیامک فورواردشده" else if (r.sim >= 0) "پیامک سیم ${r.sim + 1}" else "پیامک سامانه"
                val code = if (r.code.isEmpty()) "کد منقضی شده" else if (s.showCodes) "کد ${r.code}" else "کد ••••${r.code.takeLast(2)}"
                line.addView(text("[${time(whenReceived)}] $origin ـ ${typeName(r.otpType)} ـ $code"))
                val detail = when (r.state) {
                    "sent", "duplicate" -> "با موفقیت به بات تحویل داده شد."
                    "queued", "sending" -> "در حال ارسال…"
                    "retry" -> "اتصال برقرار نشد؛ در حال تلاش دوباره…"
                    "choose_target", "needs_confirmation" -> getString(R.string.otp_needs_confirmation)
                    "expired" -> "مهلت ارسال این کد تمام شده است."
                    "canceled" -> "ارسال متوقف شده است."
                    else -> "ارسال انجام نشد؛ دوباره تلاش کنید."
                }
                line.addView(text(detail, 11, true))
                if (r.code.isNotEmpty() && r.state in setOf("failed", "canceled", "choose_target", "needs_confirmation")) {
                    val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        this.text = if (r.state in setOf("choose_target", "needs_confirmation")) getString(R.string.otp_confirm_button) else "ارسال مجدد"
                        textSize = 11f; minHeight = 0; minimumHeight = 0; cornerRadius = dp(10)
                        setTextColor(Color.rgb(198, 244, 222)); strokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(88, 145, 123))
                        setOnClickListener { if (r.state in setOf("choose_target", "needs_confirmation")) chooseTarget(r.id) else retry(r.id) }
                    }
                    line.addView(button, LinearLayout.LayoutParams(-2, dp(42)).apply { gravity = Gravity.END; topMargin = dp(3) })
                }
            }
            rows.addView(line)
        }
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        refreshSummary()
    }
    private fun checkUpdate(manual: Boolean = false, force: Boolean = manual) {
        if (updateChecking) return
        updateChecking = true
        if (manual) toast(getString(R.string.update_checking))
        io.execute {
            val version = runCatching { CheckAppUpdateUseCase(AppUpdateRepository(this))(force) }.getOrNull()
            runOnUiThread {
                updateChecking = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (version == null) {
                    if (manual) toast(getString(R.string.update_check_failed))
                    return@runOnUiThread
                }
                if (version.isMandatory(BuildConfig.VERSION_CODE)) {
                    requiredUpdate = version
                    store.saveSettings(store.settings().copy(active = false))
                    DeliveryWorker.cancel(this); refreshStatus(); refreshLogs(); showRequiredUpdate()
                } else {
                    requiredUpdate = null
                    requiredDialog?.dismiss(); requiredDialog = null; updateDialogShown = false
                    findViewById<TextView>(R.id.updateBadge).visibility = if (version.hasUpdate(BuildConfig.VERSION_CODE)) View.VISIBLE else View.GONE
                    findViewById<TextView>(R.id.updateBadge).setOnClickListener { checkUpdate(true) }
                    if (version.hasUpdate(BuildConfig.VERSION_CODE)) UpdateDialog.show(this, version) { finish() }
                    else if (manual) toast(getString(R.string.update_current))
                }
            }
        }
    }
    private fun showRequiredUpdate() {
        val version = requiredUpdate ?: return
        if (updateDialogShown || isFinishing || isDestroyed) return
        updateDialogShown = true
        requiredDialog = UpdateDialog.show(this, version) { finish() }
    }
    private fun retry(id: String) {
        val r = store.records().find { it.id == id } ?: return
        if (!store.settings().active || !DeliveryWorker.hasSmsPermission(this)) { toast("ابتدا سامانه را فعال کنید."); return }
        if (r.code.isEmpty() || System.currentTimeMillis() >= r.expires) { toast("کد منقضی شده است."); refreshLogs(); return }
        if (r.state !in setOf("failed", "canceled")) return
        store.update(id) { it.copy(state = "queued", detail = "ارسال مجدد در صف") }
        DeliveryWorker.enqueue(this, id, manual = true); refreshLogs()
    }
    private fun chooseTarget(id: String) {
        if (!store.settings().active) { toast("ابتدا سامانه را فعال کنید."); return }
        val original = store.records().find { it.id == id } ?: return
        io.execute {
            val targets = try { OtpCorrelation.eligible(original, OtpApi().targets(store.settings()), System.currentTimeMillis()) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (targets.isEmpty()) { toast(getString(R.string.otp_no_eligible_request)); return@runOnUiThread }
                MaterialAlertDialogBuilder(this).setTitle(R.string.otp_confirm_title)
                    .setItems(targets.map { "${typeName(it.otpType)} ـ ${it.nid.take(3)}••••${it.nid.takeLast(3)}" }.toTypedArray()) { _, index ->
                        val record = store.records().find { it.id == id }
                        if (record == null || record.code.isEmpty() || record.state !in setOf("choose_target", "needs_confirmation")) { toast(getString(R.string.otp_no_longer_available)); refreshLogs(); return@setItems }
                        val target = targets[index]
                        val message = getString(R.string.otp_confirm_body, typeName(target.otpType), "${target.nid.take(3)}••••${target.nid.takeLast(3)}") +
                            if (target.otpType == OtpType.REGISTRATION_DELETE_OTP.name) "\n\n" + getString(R.string.otp_delete_warning) else ""
                        MaterialAlertDialogBuilder(this).setTitle(R.string.otp_confirm_title).setMessage(message)
                            .setPositiveButton(R.string.otp_confirm_send) { _, _ ->
                                val current = store.records().find { it.id == id }
                                if (!store.settings().active || current == null || current.state !in setOf("choose_target", "needs_confirmation") ||
                                    OtpCorrelation.eligible(current, listOf(target), System.currentTimeMillis()).isEmpty()) {
                                    toast(getString(R.string.otp_no_longer_available)); return@setPositiveButton
                                }
                                store.update(id) { it.copy(nid = target.nid, requestId = target.requestId, state = "queued",
                                    expires = minOf(it.expires, target.expires), confirmed = true, confirmedType = target.otpType,
                                    protocolVersion = if (target.typedProtocol) 2 else 1) }
                                DeliveryWorker.enqueue(this, id, manual = true); refreshLogs()
                            }.setNegativeButton(R.string.typed_decline, null).show()
                    }.setNegativeButton("بازگشت", null).show()
            }
        }
    }
    private fun typeName(type: String) = getString(when (type) {
        OtpType.TRACKING_RECOVERY_OTP.name -> R.string.type_recovery
        OtpType.REGISTRATION_DELETE_OTP.name -> R.string.type_deletion
        OtpType.UNLABELED_SIX_DIGIT_CODE.name -> R.string.type_unlabeled
        else -> R.string.type_registration
    })
    private fun refreshSummary() {
        if (!::rows.isInitialized) return
        val last = store.lastConnection()
        findViewById<TextView>(R.id.versionSummary).text = getString(R.string.home_technical_summary,
            BuildConfig.VERSION_NAME, store.records().size, if (last > 0) time(last) else getString(R.string.no_connection_yet))
    }
    private fun addLog(value: String) {
        events.add(System.currentTimeMillis() to value)
        while (events.size > 12) events.removeAt(0)
        refreshLogs()
    }
    private fun time(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("fa-IR")).format(Date(value))
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun color(value: Int) = ContextCompat.getColor(this, value)
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
}
