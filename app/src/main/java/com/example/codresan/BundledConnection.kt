package com.example.codresan

import android.content.Context
import org.json.JSONObject

/** No permission or consent is granted by provisioning the connection. */
object BundledConnection {
    fun provision(context: Context, store: SecureStore): Boolean {
        val current = store.settings()
        if (current.token.isNotBlank() && OtpApi.validEndpoint(current.endpoint)) return true
        return try {
            val data = context.assets.open("mahan-connection.json").bufferedReader().use {
                val text = it.readText()
                require(text.length <= 8192)
                JSONObject(text)
            }
            val endpoint = data.getString("endpoint")
            val token = data.getString("device_key")
            require(OtpApi.validEndpoint(endpoint) && token.length in 16..512 && token.all { it.code in 33..126 })
            store.saveSettings(current.copy(endpoint = endpoint, token = token))
            true
        } catch (_: Exception) { false }
    }
}
