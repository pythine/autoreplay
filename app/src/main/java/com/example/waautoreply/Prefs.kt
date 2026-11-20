package com.example.waautoreply

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class LogEntry(
    val time: Long,
    val sender: String,
    val incoming: String,
    val reply: String,
    val isError: Boolean
)

object Prefs {
    const val DEFAULT_PROMPT =
        "Kamu adalah asisten AI cerdas di WhatsApp. Jawab pesan dengan singkat, ramah, santai, dan menggunakan bahasa Indonesia yang natural."

    const val PLAIN_NAME = "wa_bot"
    private const val KEY_PROMPT = "system_prompt"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOG = "log"
    private const val KEY_API = "groq_api_key"
    private const val MAX_LOG = 5

    fun plain(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    /** API key disimpan terenkripsi (AES256) memakai Android Keystore. */
    private fun secure(c: Context): SharedPreferences {
        val ctx = c.applicationContext
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "wa_bot_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Cadangan agar aplikasi tidak crash jika Keystore bermasalah di perangkat tertentu
            ctx.getSharedPreferences("wa_bot_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(c: Context): String = secure(c).getString(KEY_API, "").orEmpty()
    fun setApiKey(c: Context, v: String) = secure(c).edit().putString(KEY_API, v.trim()).apply()

    fun getPrompt(c: Context): String =
        plain(c).getString(KEY_PROMPT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT

    fun setPrompt(c: Context, v: String) = plain(c).edit().putString(KEY_PROMPT, v.trim()).apply()

    fun isEnabled(c: Context): Boolean = plain(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) = plain(c).edit().putBoolean(KEY_ENABLED, v).apply()

    // ---------- Log (5 terakhir) ----------
    @Synchronized
    fun addLog(c: Context, e: LogEntry) {
        val list = getLog(c).toMutableList()
        list.add(0, e)
        val arr = JSONArray()
        list.take(MAX_LOG).forEach {
            arr.put(
                JSONObject()
                    .put("t", it.time).put("s", it.sender).put("i", it.incoming)
                    .put("r", it.reply).put("e", it.isError)
            )
        }
        plain(c).edit().putString(KEY_LOG, arr.toString()).apply()
    }

    fun getLog(c: Context): List<LogEntry> = try {
        val arr = JSONArray(plain(c).getString(KEY_LOG, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(o.getLong("t"), o.getString("s"), o.getString("i"), o.getString("r"), o.getBoolean("e"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}
