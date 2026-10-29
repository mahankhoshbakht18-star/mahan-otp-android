package com.example.codresan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * راه‌اندازی مجدد خودکار آماده‌باش سامانه ماهان پس از روشن شدن یا ارتقای بسته.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            val store = SecureStore(context)
            if (store.settings().active && DeliveryWorker.hasSmsPermission(context)) {
                MahanKeepAliveService.start(context)
            }
        }
    }
}
