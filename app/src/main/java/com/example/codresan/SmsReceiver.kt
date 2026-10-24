package com.example.codresan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        executor.execute {
            try {
                val store = SecureStore(context)
                val settings = store.settings()
                if (!settings.active || !settings.consent || !DeliveryWorker.hasSmsPermission(context)) return@execute
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@execute
                val sender = parts.first().displayOriginatingAddress.orEmpty()
                if (parts.any { it.displayOriginatingAddress != parts.first().displayOriginatingAddress }) return@execute
                val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
                val parsed = LoanRules.parse(body, settings.minLength, settings.maxLength)
                if (parsed == null || (parsed.type != OtpType.REGISTRATION_OTP && !settings.typedSmsConsent)) { store.reject(); return@execute }
                val trusted = LoanRules.sender(sender) in LoanRules.senders(settings.allowedSenders)
                val senderHash = LoanRules.phoneHash(sender)
                // A bare code from an unknown service sender is not even persisted.
                if (parsed.type == OtpType.UNLABELED_SIX_DIGIT_CODE && !trusted && senderHash.isBlank()) { store.reject(); return@execute }
                val sim = resolveSlot(intent)
                val forwarded = LoanRules.sender(sender) !in LoanRules.senders(settings.allowedSenders)
                val nid = if (forwarded) "" else when (sim) { 0 -> settings.nid1; 1 -> settings.nid2; else -> "" }
                val timestamp = parts.first().timestampMillis
                if (!LoanRules.fresh(timestamp, System.currentTimeMillis(), settings.ttlSeconds)) {
                    store.reject(); DeliveryWorker.announce(context); return@execute
                }
                val id = LoanRules.messageId(body, sender, timestamp, sim)
                val needsConfirmation = parsed.type == OtpType.UNLABELED_SIX_DIGIT_CODE
                if (store.insert(OtpRecord(id, parsed.code, nid, sim, timestamp, timestamp + settings.ttlSeconds * 1000L,
                    state = if (needsConfirmation) "needs_confirmation" else "queued",
                    forwarded = forwarded, senderHash = senderHash, otpType = parsed.type.name, trustedSender = trusted))) {
                    if (!needsConfirmation) DeliveryWorker.enqueue(context, id)
                    DeliveryWorker.announce(context)
                }
            } catch (_: Exception) { /* Fail closed; never log content or bypass secure storage. */ }
            finally { pending.finish() }
        }
    }
    private fun resolveSlot(intent: Intent): Int {
        val b = intent.extras ?: return -1
        for (key in listOf("android.telephony.extra.SLOT_INDEX", "slot", "slot_id", "simId", "phone")) {
            if (b.containsKey(key)) { val slot = b.getInt(key, -1); if (slot in 0..1) return slot }
        }
        for (key in listOf(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, "subscription", "subscription_id")) {
            if (b.containsKey(key)) {
                val sub = b.getInt(key, -1)
                if (sub >= 0) {
                    val slot = if (android.os.Build.VERSION.SDK_INT >= 29) SubscriptionManager.getSlotIndex(sub) else -1
                    if (slot in 0..1) return slot
                }
            }
        }
        // Never guess SIM from which national-ID field is filled.
        return -1
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
