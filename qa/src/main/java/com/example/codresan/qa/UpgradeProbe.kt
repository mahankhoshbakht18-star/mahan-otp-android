package com.example.codresan.qa

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only installed by the isolated emulator workflow. Never shipped to customers. */
class UpgradeProbe : Instrumentation() {
    private var action = ""
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); action = arguments?.getString("action").orEmpty(); start() }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("mahan.v2.aes", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mahan.v2.aes", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    private fun write(data: JSONObject) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(data.toString().toByteArray()), Base64.NO_WRAP)
        check(targetContext.getSharedPreferences("mahan_v2_vault", Context.MODE_PRIVATE).edit().putString("vault", value).commit())
    }
    private fun read(): JSONObject {
        val value = targetContext.getSharedPreferences("mahan_v2_vault", Context.MODE_PRIVATE).getString("vault", null) ?: error("Vault missing")
        val parts = value.split(':')
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return JSONObject(String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))))
    }
    @Suppress("DEPRECATION")
    override fun onStart() {
        val result = Bundle()
        try {
            when (action) {
                "seed" -> {
                    check(targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionCode == 4)
                    val settings = JSONObject().put("active", false).put("consent", false).put("nid1", "0012345679").put("nid2", "")
                        .put("endpoint", "https://test.invalid/api/v1/sms/otp").put("token", "fixture-token-for-isolated-upgrade")
                        .put("device", "5eec4932-57ac-4a64-9db0-3ce40b2f3b01").put("notifications", false).put("show_codes", true)
                    val now = System.currentTimeMillis()
                    val record = JSONObject().put("id", "fixture-preserved-event").put("code", "123456").put("nid", "0012345679")
                        .put("sim", 0).put("received", now).put("expires", now + 120000).put("state", "queued")
                        .put("attempts", 0).put("detail", "fixture").put("forwarded", false).put("sender_hash", "").put("request_id", "")
                    write(JSONObject().put("settings", settings).put("records", JSONArray().put(record)))
                }
                "verify-upgrade", "enable-sms" -> {
                    check(targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionCode == 5)
                    val data = read(); val settings = data.getJSONObject("settings")
                    check(settings.getString("device") == "5eec4932-57ac-4a64-9db0-3ce40b2f3b01")
                    check(settings.getString("nid1") == "0012345679")
                    check(settings.getString("token") == "fixture-token-for-isolated-upgrade")
                    check(settings.getString("endpoint") == "https://test.invalid/api/v1/sms/otp")
                    check(data.getJSONArray("records").getJSONObject(0).getString("id") == "fixture-preserved-event")
                    if (action == "enable-sms") {
                        settings.put("active", true).put("consent", true).put("typed_sms_consent", true).put("update_reports", false)
                        write(data)
                    }
                }
                "verify-sms" -> {
                    val records = read().getJSONArray("records")
                    val rows = (0 until records.length()).map { records.getJSONObject(it) }
                    check(rows.any { it.optString("otp_type") == "TRACKING_RECOVERY_OTP" })
                    check(rows.any { it.optString("otp_type") == "REGISTRATION_DELETE_OTP" })
                    check(rows.any { it.optString("otp_type") == "UNLABELED_SIX_DIGIT_CODE" && it.getString("state") == "needs_confirmation" })
                    check(rows.none { it.optString("code") == "888888" }) // unrelated bank fixture
                    check(rows.none { it.has("body") || it.has("text") || it.has("sender_number") })
                }
                "verify-fresh" -> {
                    check(targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionCode == 5)
                    val settings = read().getJSONObject("settings")
                    check(!settings.getBoolean("active") && !settings.getBoolean("consent"))
                    check(settings.getString("device").isNotBlank())
                    check(!settings.optBoolean("typed_sms_consent") && !settings.optBoolean("update_reports"))
                }
                else -> error("Unknown test action")
            }
            result.putString("result", "QA_SUCCESS:$action")
            finish(Activity.RESULT_OK, result)
        } catch (_: Throwable) {
            result.putString("result", "QA_FAILURE:$action")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
