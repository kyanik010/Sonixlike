package com.example

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Lightweight SONIX CONTROL sync. Uses a stable installation ID and the limited
 * sonix-device-api endpoint; it never touches IPTV credentials or playback.
 */
class SonixControlClient(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sonix_control_identity", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val deviceUid: String by lazy {
        prefs.getString("device_uid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_uid", it).apply()
        }
    }

    @Volatile var onSync: ((JSONObject) -> Unit)? = null

    fun sync() {
        executor.execute {
            try {
                val result = request(JSONObject()
                    .put("action", "sync")
                    .put("device_uid", deviceUid)
                    .put("device_name", Build.MODEL ?: "Android")
                    .put("platform", if (app.packageManager.hasSystemFeature("android.software.leanback")) "android_tv" else "android")
                    .put("manufacturer", Build.MANUFACTURER ?: "")
                    .put("model", Build.MODEL ?: "")
                    .put("os_version", Build.VERSION.RELEASE ?: "")
                    .put("app_version", app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""))
                onSync?.invoke(result)
            } catch (e: Exception) {
                android.util.Log.w("SONIXControl", "Control sync failed", e)
            }
        }
    }

    fun acknowledge(messageId: String) {
        executor.execute {
            try {
                request(JSONObject().put("action", "message.ack").put("device_uid", deviceUid).put("message_id", messageId))
            } catch (e: Exception) {
                android.util.Log.w("SONIXControl", "Message acknowledgement failed", e)
            }
        }
    }

    fun setTheme(themeId: String) {
        executor.execute {
            try {
                request(JSONObject().put("action", "theme.set").put("device_uid", deviceUid).put("theme_id", themeId))
            } catch (e: Exception) {
                android.util.Log.w("SONIXControl", "Theme selection failed", e)
            }
        }
    }

    private fun request(body: JSONObject): JSONObject {
        val connection = (URL("https://ganqdgstfriegfwainoz.supabase.co/functions/v1/sonix-device-api").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("apikey", "sb_publishable_yOn2frVZ8pJlzscXrzwZpw_8BeGXa1L")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return JSONObject(text)
    }

    fun shutdown() { executor.shutdownNow() }
}
