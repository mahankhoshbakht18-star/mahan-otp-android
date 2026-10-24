package ir.smsforwardmanager.data.repository

import android.content.Context
import ir.smsforwardmanager.data.remote.AppVersionApi
import ir.smsforwardmanager.data.remote.model.AppVersionDto

class AppUpdateRepository(context: Context, private val api: AppVersionApi = AppVersionApi(context.applicationContext)) {
    private val prefs = context.getSharedPreferences("app_update_metadata", Context.MODE_PRIVATE)
    fun requiredCached(installed: Int): AppVersionDto? = runCatching {
        AppVersionApi.decode(prefs.getString("metadata", "").orEmpty()).validate()
            .takeIf { it.isMandatory(installed) }
    }.getOrNull()
    // A cached result is valid for six hours. A failed forced refresh is reported, never faked.
    fun latest(force: Boolean = false, now: Long = System.currentTimeMillis()): AppVersionDto {
        val age = now - prefs.getLong("checked_at", 0)
        if (!force && age in 0 until 6 * 60 * 60 * 1000L) {
            runCatching { AppVersionApi.decode(prefs.getString("metadata", "").orEmpty()).validate() }
                .getOrNull()?.let { return it }
        }
        return api.latest().also {
            prefs.edit().putString("metadata", AppVersionApi.encode(it)).putLong("checked_at", now).apply()
        }
    }
}
