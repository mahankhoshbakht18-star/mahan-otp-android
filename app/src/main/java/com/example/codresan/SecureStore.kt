package com.example.codresan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val active: Boolean = false, val consent: Boolean = false, val nid1: String = "", val nid2: String = "",
    val endpoint: String = "https://otp.mahanvip.ir/api/v1/sms/otp", val token: String = "",
    val allowedSenders: String = "2000411", val keyword: String = "ازدواج", val minLength: Int = 6, val maxLength: Int = 6,
    val maxAttempts: Int = 3, val backoffSeconds: Int = 15, val ttlSeconds: Int = 120, val retentionDays: Int = 7,
    val wifiOnly: Boolean = false, val notifications: Boolean = true,
    val deviceId: String = UUID.randomUUID().toString(), val showCodes: Boolean = true
) {
    fun json(): JSONObject = JSONObject().put("active", active).put("consent", consent)
        .put("nid1", nid1).put("nid2", nid2).put("endpoint", endpoint).put("token", token)
        .put("senders", allowedSenders).put("keyword", keyword).put("min", minLength).put("max", maxLength)
        .put("attempts", maxAttempts).put("backoff", backoffSeconds).put("ttl", ttlSeconds)
        .put("days", retentionDays).put("wifi", wifiOnly).put("notifications", notifications).put("device", deviceId)
        .put("show_codes", showCodes)
    companion object {
        fun from(j: JSONObject) = AppSettings(j.optBoolean("active"), j.optBoolean("consent"),
            j.optString("nid1"), j.optString("nid2"), j.optString("endpoint", "https://otp.mahanvip.ir/api/v1/sms/otp"),
            j.optString("token"), j.optString("senders", "2000411"), j.optString("keyword", "ازدواج"),
            j.optInt("min", 6), j.optInt("max", 6), j.optInt("attempts", 3).coerceIn(1, 5),
            j.optInt("backoff", 15).coerceIn(10, 120), j.optInt("ttl", 120).coerceIn(30, 300),
            j.optInt("days", 7).coerceIn(1, 30), j.optBoolean("wifi"), j.optBoolean("notifications", true),
            j.optString("device", UUID.randomUUID().toString()), j.optBoolean("show_codes", true))
    }
}

data class OtpRecord(val id: String, val code: String, val nid: String, val sim: Int, val received: Long,
    val expires: Long, val state: String = "queued", val attempts: Int = 0, val detail: String = "در صف ارسال",
    val forwarded: Boolean = false, val senderHash: String = "", val requestId: String = "") {
    fun json() = JSONObject().put("id", id).put("code", code).put("nid", nid).put("sim", sim)
        .put("received", received).put("expires", expires).put("state", state).put("attempts", attempts).put("detail", detail)
        .put("forwarded", forwarded).put("sender_hash", senderHash).put("request_id", requestId)
    companion object {
        fun from(j: JSONObject) = OtpRecord(j.getString("id"), j.getString("code"), j.getString("nid"),
            j.getInt("sim"), j.getLong("received"), j.getLong("expires"), j.getString("state"),
            j.getInt("attempts"), j.getString("detail"), j.optBoolean("forwarded"), j.optString("sender_hash"), j.optString("request_id"))
    }
}

/** All persisted values, including OTP and NID, use authenticated AES-GCM encryption. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("mahan_v2_vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("mahan.v2.aes", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mahan.v2.aes", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun read(): JSONObject {
        val encoded = prefs.getString("vault", null) ?: return JSONObject()
        val parts = encoded.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    private fun write(j: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val content = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(j.toString().toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("vault", content).commit()) { "Secure storage unavailable" }
    }
    fun settings(): AppSettings = synchronized(lock) {
        val j = read()
        if (!j.has("settings")) { val s = AppSettings(); j.put("settings", s.json()); write(j); s }
        else AppSettings.from(j.getJSONObject("settings"))
    }
    fun saveSettings(s: AppSettings) = synchronized(lock) { val j = read(); j.put("settings", s.json()); write(j) }
    fun records(): List<OtpRecord> = synchronized(lock) {
        val j = read(); val settings = settings(); val now = System.currentTimeMillis()
        val a = j.optJSONArray("records") ?: JSONArray()
        val list = (0 until a.length()).map { OtpRecord.from(a.getJSONObject(it)) }
            .filter { now - it.received < settings.retentionDays * 86400000L }
            .map { r -> if (now >= r.expires && r.code.isNotEmpty()) r.copy(code = "", state = if (r.state in setOf("sent", "duplicate")) r.state else "expired", detail = if (r.state in setOf("sent", "duplicate")) r.detail else "کد منقضی شده است") else r }
        j.put("records", JSONArray(list.map { it.json() })); write(j)
        list.sortedByDescending { it.received }
    }
    fun insert(record: OtpRecord): Boolean = synchronized(lock) {
        val items = records()
        if (items.any { it.id == record.id }) return false
        val j = read(); j.put("records", JSONArray((listOf(record) + items).take(300).map { it.json() })); write(j); true
    }
    fun update(id: String, transform: (OtpRecord) -> OtpRecord) = synchronized(lock) {
        val list = records().map { if (it.id == id) transform(it) else it }
        val j = read(); j.put("records", JSONArray(list.map { it.json() })); write(j)
    }
    fun delete(id: String? = null) = synchronized(lock) {
        val list = if (id == null) emptyList() else records().filter { it.id != id }
        val j = read(); j.put("records", JSONArray(list.map { it.json() })); write(j)
    }
    fun reject() = synchronized(lock) { val j = read(); j.put("rejected", j.optInt("rejected") + 1); write(j) }
    fun rejected(): Int = synchronized(lock) { read().optInt("rejected") }
    fun lastConnection(): Long = synchronized(lock) { read().optLong("connection") }
    fun connected() = synchronized(lock) { val j = read(); j.put("connection", System.currentTimeMillis()); write(j) }
    companion object { private val lock = Any() }
}
